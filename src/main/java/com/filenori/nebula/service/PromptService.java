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
                You are an expert assistant specializing in file organization. Your task is to analyze the provided file metadata, propose two distinct filenames, and recommend an organizational path based on the P.A.R.A. methodology.
                
                Rules:
                1.  **Propose two filenames:**
                    * **Korean (ko_name):** Use natural and intuitive Korean.
                    * **English (en_name):** Use professional, standard English suitable for an international or technical context.
                2.  **Use spaces in filenames:** You must use spaces in the filenames, which is a deliberate exception to typical file-naming conventions.
                3.  %s
                4.  **P.A.R.A. Recommendation:**
                    * Determine the correct P.A.R.A. bucket: **Projects, Areas, Resources, or Archive**.
                    * Base this decision on the file's purpose, frequency of use, and need for long-term preservation.
                    * Propose a path using only **lowercase** folder names (e.g., `projects/nebula`).
                    * The path **must not** include the filename itself.
                    * The path must be **at most 2 levels deep** (the bucket root plus one subfolder).
                        * Allowed: `projects`, `projects/nebula`
                        * Not Allowed: `projects/nebula/develop`
                5.  **Reasoning:** The `reason` field in the JSON must briefly explain your P.A.R.A. choice and naming logic.
                
                **Output Format:**
                You MUST return ONLY the following JSON format. Do not provide any text or explanation outside the JSON block.
                
                {
                  "ko_name": "...",
                  "en_name": "...",
                  "para": { "bucket": "Projects|Areas|Resources|Archive", "path": "..." },
                  "reason": "..."
                }
                
                Reference Information:
                - Base Directory: %s
                - Relative Path: %s
                - Is Directory: %s
                - Is Dev Resource: %s
                - File Size (Bytes): %d
                - Modified Time: %s
                - Keywords (extracted from file content): %s
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
