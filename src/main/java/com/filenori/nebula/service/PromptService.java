package com.filenori.nebula.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filenori.nebula.entity.OrganizedFileDocument;
import com.filenori.nebula.dto.request.KeywordRequestDto;
import com.filenori.nebula.dto.response.FileNameGenerationResultDto;
import com.filenori.nebula.repository.OrganizedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Set;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromptService {

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;
    private final OrganizedFileRepository organizedFileRepository;
    // private final FielNameHistoryRepository repository; // MongoDB 저장용

    private static final int MAX_CONCURRENT_REQUESTS = 4;

    public List<FileNameGenerationResultDto> generateFileNameFromKeywords(KeywordRequestDto requestDto) {
        List<KeywordRequestDto.Entry> entries = requestDto.getEntries();

        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        List<FileNameGenerationResultDto> results = Flux.fromIterable(entries)
                .filter(Objects::nonNull)
                .flatMapSequential(entry -> {
                    String prompt = createPromptForEntry(requestDto, entry);
                    return Mono.zip(
                                    Mono.just(entry),
                                    openAiService.requestFileNameToGpt(prompt),
                                    (original, response) -> {
                                        PathInfo sanitized = response.getPara() == null
                                                ? new PathInfo(null, null, null)
                                                : parseParaPath(response.getPara().getBucket(), response.getPara().getPath());

                                        return new FileNameGenerationResultDto(
                                                original.getRelativePath(),
                                                response.getKoName(),
                                                response.getEnName(),
                                                sanitized.bucket(),
                                                sanitized.fullPath(),
                                                response.getReason()
                                        );
                                    }
                            )
                            .doOnNext(this::logPrettyResult);
                }, MAX_CONCURRENT_REQUESTS)
                .collectList()
                .block();

        if (results == null) {
            return List.of();
        }

        persistResults(requestDto, results);
        return results;
    }

    private String createPromptForEntry(KeywordRequestDto requestDto, KeywordRequestDto.Entry entry) {
        String keywords = (entry.getKeywords() == null || entry.getKeywords().isEmpty())
                ? "없음"
                : String.join(", ", entry.getKeywords());

        String extensionInstruction = buildExtensionInstruction(entry.getRelativePath(), entry.isDirectory());

        return """
                당신은 개별 파일 메타데이터를 분석해 두 가지 파일명을 제안하고 P.A.R.A. 방법론에 따라 정리 위치를 추천하는 도우미입니다.
                규칙:
                - 한국어와 영어 파일명을 각각 제안합니다. 한국어는 자연스럽고 직관적인 표현을 사용하고, 영어는 국제적/기술적 맥락에 맞는 표현을 사용합니다.
                - 일반적인 파일명 규칙과 다르게 공백을 사용합니다.
                - %s
                - P.A.R.A. 버킷 중 Projects, Areas, Resources, Archive 어디에 속하는지 판단하고, 소문자 폴더명을 사용하는 경로(예: projects/nebula)를 제안합니다.
                - 경로에는 파일명을 포함하지 말고, 버킷 루트 바로 아래 또는 그 다음 depth(최대 2단계)까지만 사용합니다. 예: projects/nebula 허용, projects/nebula/develop 불가.
                - 버킷을 고를 때는 파일의 목적, 사용 빈도, 장기 보존 필요성 등을 고려합니다.
                반드시 다음 JSON 형식만 반환하세요:
                {
                  "ko_name": "...",
                  "en_name": "...",
                  "para": { "bucket": "Projects|Areas|Resources|Archive", "path": "..." },
                  "reason": "..."
                }
                참고 정보:
                - 기준 디렉터리: %s
                - 상대 경로: %s
                - 디렉터리 여부: %s
                - 개발 관련 자원 여부: %s
                - 파일 크기(Byte): %d
                - 수정 시각: %s
                - 키워드(파일 내부 내용에서 추출): %s
                """.formatted(
                extensionInstruction,
                requestDto.getDirectory(),
                entry.getRelativePath(),
                entry.isDirectory(),
                entry.isDevelopment(),
                entry.getSizeBytes(),
                entry.getModifiedAt(),
                keywords);
    }

    private String buildExtensionInstruction(String relativePath, boolean isDirectory) {
        if (isDirectory || relativePath == null) {
            return "디렉터리인 경우 확장자를 추가하지 않습니다.";
        }

        int lastDot = relativePath.lastIndexOf('.');
        if (lastDot == -1 || lastDot == relativePath.length() - 1) {
            return "원본에 확장자가 없으므로 확장자를 붙이지 않습니다.";
        }

        String extension = relativePath.substring(lastDot);
        return "파일일 경우 반드시 '" + extension + "' 확장자를 그대로 유지합니다.";
    }

    private void logPrettyResult(FileNameGenerationResultDto result) {
        try {
            String pretty = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            log.info("OpenAI naming result:\n{}", pretty);
        } catch (Exception e) {
            log.info("OpenAI naming result (raw): relativePath={}, koName={}, enName={}, bucket={}, path={}, reason={}",
                    result.getRelativePath(),
                    result.getKoreanFileName(),
                    result.getEnglishFileName(),
                    result.getParaBucket(),
                    result.getParaPath(),
                    result.getReason());
        }
    }

    private void persistResults(KeywordRequestDto requestDto, List<FileNameGenerationResultDto> results) {
        if (results == null || results.isEmpty()) {
            return;
        }

        String userIdRaw = requestDto.getUserId();
        if (userIdRaw == null || userIdRaw.isBlank()) {
            log.warn("Skipping Mongo persistence: userId is missing in request");
            return;
        }

        ObjectId userId;
        try {
            userId = new ObjectId(userIdRaw);
        } catch (IllegalArgumentException e) {
            log.warn("Skipping Mongo persistence: invalid userId {}", userIdRaw);
            return;
        }

        List<KeywordRequestDto.Entry> requestEntries = requestDto.getEntries();
        if (requestEntries == null || requestEntries.isEmpty()) {
            log.warn("Skipping Mongo persistence: request entries are missing");
            return;
        }

        Map<String, KeywordRequestDto.Entry> entryByPath = requestEntries.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(KeywordRequestDto.Entry::getRelativePath, Function.identity(), (left, right) -> left));

        Set<String> relativePaths = results.stream()
                .map(FileNameGenerationResultDto::getRelativePath)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, OrganizedFileDocument> existingByPath = relativePaths.isEmpty()
                ? Map.of()
                : organizedFileRepository.findByUserIdAndOriginalRelativePathIn(userId, relativePaths).stream()
                        .collect(Collectors.toMap(OrganizedFileDocument::getOriginalRelativePath, Function.identity(), (left, right) -> left));

        List<OrganizedFileDocument> documents = results.stream()
                .map(result -> toDocument(userId,
                        requestDto.getDirectory(),
                        entryByPath.get(result.getRelativePath()),
                        existingByPath.get(result.getRelativePath()),
                        result))
                .filter(Objects::nonNull)
                .toList();

        if (documents.isEmpty()) {
            return;
        }

        organizedFileRepository.saveAll(documents);
    }

    private OrganizedFileDocument toDocument(ObjectId userId,
                                             String baseDirectory,
                                             KeywordRequestDto.Entry entry,
                                             OrganizedFileDocument existing,
                                             FileNameGenerationResultDto result) {
        if (entry == null) {
            log.warn("Unable to persist result: original entry not found for path {}", result.getRelativePath());
            return null;
        }

        PathInfo pathInfo = parseParaPath(result.getParaBucket(), result.getParaPath());

        return OrganizedFileDocument.builder()
                .id(existing != null ? existing.getId() : null)
                .userId(userId)
                .baseDirectory(baseDirectory)
                .originalRelativePath(result.getRelativePath())
                .directory(entry.isDirectory())
                .development(entry.isDevelopment())
                .sizeBytes(entry.getSizeBytes())
                .modifiedAt(entry.getModifiedAt())
                .keywords(entry.getKeywords() == null ? null : List.copyOf(entry.getKeywords()))
                .koreanFileName(result.getKoreanFileName())
                .englishFileName(result.getEnglishFileName())
                .paraBucket(pathInfo.bucket())
                .paraFolder(pathInfo.folder())
                .paraFullPath(pathInfo.fullPath())
                .reason(result.getReason())
                .createdAt(existing != null && existing.getCreatedAt() != null ? existing.getCreatedAt() : Instant.now())
                .build();
    }

    private PathInfo parseParaPath(String bucket, String rawPath) {
        if (bucket == null || bucket.isBlank()) {
            return new PathInfo(null, null, null);
        }

        String normalizedBucket = bucket.trim();
        if (rawPath == null || rawPath.isBlank()) {
            return new PathInfo(normalizedBucket, null, normalizedBucket.toLowerCase());
        }

        String trimmedPath = rawPath.trim();
        String[] segments = trimmedPath.split("/");

        if (segments.length > 2) {
            log.info("Truncating para path '{}' to maintain max depth 2", trimmedPath);
            segments = new String[]{segments[0], segments[1]};
        }

        String bucketSegmentLower = normalizedBucket.toLowerCase();
        String folder = null;

        if (segments.length == 1) {
            if (!segments[0].equalsIgnoreCase(normalizedBucket)) {
                folder = normalizeFolderSegment(segments[0]);
            }
        } else if (segments.length >= 2) {
            if (segments[0].equalsIgnoreCase(normalizedBucket)) {
                folder = normalizeFolderSegment(segments[1]);
            } else {
                folder = normalizeFolderSegment(segments[0]);
            }
        }

        String normalizedPath = folder == null ? bucketSegmentLower : bucketSegmentLower + "/" + folder;

        return new PathInfo(normalizedBucket, folder, normalizedPath);
    }

    private record PathInfo(String bucket, String folder, String fullPath) {
    }

    private String normalizeFolderSegment(String segment) {
        return segment == null ? null : segment.toLowerCase(Locale.ROOT);
    }
}
