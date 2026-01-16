package com.filenori.nebula.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filenori.nebula.entity.OrganizedFileDocument;
import com.filenori.nebula.dto.request.KeywordRequestDto;
import com.filenori.nebula.dto.response.FileNameGenerationResultDto;
import com.filenori.nebula.dto.response.FileNameResponseDto;
import com.filenori.nebula.repository.OrganizedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromptService {

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;
    private final OrganizedFileRepository organizedFileRepository;
    // private final FielNameHistoryRepository repository; // MongoDB 저장용

    private static final int BATCH_SIZE = 50;

    public List<FileNameGenerationResultDto> generateFileNameFromKeywords(KeywordRequestDto requestDto) {
        log.info("=== Starting generateFileNameFromKeywords ===");
        List<KeywordRequestDto.Entry> entries = requestDto.getEntries();

        if (entries == null || entries.isEmpty()) {
            log.warn("No entries provided");
            return List.of();
        }

        log.info("Total entries received: {}", entries.size());
        String systemPrompt = "You only respond with JSON that matches the provided schema. Prefer Korean file names when they sound natural, but respond in English when that is clearer or more conventional for technical terms.";
        ObjectId userId = extractValidUserId(requestDto.getUserId());

        // 배치별로 처리: 첫 배치는 기존 폴더 없이, 이후 배치는 업데이트된 폴더 정보 포함
        List<FileNameGenerationResultDto> results = new ArrayList<>();
        List<List<KeywordRequestDto.Entry>> batches = entries.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(entry -> entries.indexOf(entry) / BATCH_SIZE))
                .values()
                .stream()
                .toList();

        log.info("Total batches to process: {}", batches.size());

        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            List<KeywordRequestDto.Entry> batch = batches.get(batchIndex);
            log.info("=== Processing batch {} with {} entries ===", batchIndex, batch.size());

            List<FileNameGenerationResultDto> batchResults = processBatchWithIncrementalFolders(requestDto, batch, systemPrompt, userId)
                    .collectList()
                    .block();

            if (batchResults != null) {
                log.info("Batch {} completed with {} results", batchIndex, batchResults.size());
                batchResults.stream()
                        .filter(Objects::nonNull)
                        .forEach(results::add);
            } else {
                log.warn("Batch {} returned null results", batchIndex);
            }
        }

        log.info("All batches completed. Total results: {}", results.size());

        persistResults(requestDto, results);
        return results;
    }

    private ObjectId extractValidUserId(String userIdStr) {
        if (userIdStr == null || userIdStr.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        try {
            return new ObjectId(userIdStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid userId format: " + userIdStr, e);
        }
    }

    private Map<String, Set<String>> getExistingParaFolders(ObjectId userId) {
        Map<String, Set<String>> paraFolders = new HashMap<>();

        try {
            String[] buckets = {"Projects", "Areas", "Resources", "Archive"};

            for (String bucket : buckets) {
                List<OrganizedFileDocument> documents = organizedFileRepository.findFoldersByUserIdAndBucket(userId, bucket);
                Set<String> folders = documents.stream()
                        .map(OrganizedFileDocument::getParaFolder)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                paraFolders.put(bucket, folders);
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve existing PARA folders for user", e);
        }

        return paraFolders;
    }

    private Flux<FileNameGenerationResultDto> processBatchWithIncrementalFolders(KeywordRequestDto requestDto, List<KeywordRequestDto.Entry> batch, String systemPrompt, ObjectId userId) {
        log.info("=== processBatchWithIncrementalFolders started ===");
        // 현재 PARA 폴더 구조 조회 (이전 배치 처리 후 추가된 폴더 포함)
        Map<String, Set<String>> currentParaFolders = getExistingParaFolders(userId);
        log.info("Current PARA folders: {}", currentParaFolders);

        List<String> fileInfos = batch.stream()
                .map(entry -> buildFileInfo(requestDto, entry))
                .toList();

        String existingFoldersInfo = buildExistingFoldersInfo(currentParaFolders);

        log.info("Built file infos for batch, calling openAiService.requestFileNameToGptBatch with {} entries", fileInfos.size());

        return openAiService.requestFileNameToGptBatch(fileInfos, systemPrompt, existingFoldersInfo)
                .doOnNext(response -> log.info("Received response from OpenAI: {}", response))
                .flatMapMany(responses -> {
                    List<FileNameGenerationResultDto> batchResults = new ArrayList<>();
                    for (int i = 0; i < batch.size() && i < responses.size(); i++) {
                        KeywordRequestDto.Entry entry = batch.get(i);
                        FileNameResponseDto response = responses.get(i);

                        PathInfo sanitized = response.getPara() == null
                                ? new PathInfo(null, null, null)
                                : parseParaPath(response.getPara().getBucket(), response.getPara().getPath());

                        FileNameGenerationResultDto result = new FileNameGenerationResultDto(
                                entry.getRelativePath(),
                                response.getKoName(),
                                response.getEnName(),
                                sanitized.bucket(),
                                sanitized.fullPath(),
                                response.getReason()
                        );
                        batchResults.add(result);
                        logPrettyResult(result);
                    }
                    return Flux.fromIterable(batchResults);
                });
    }

    private String buildFileInfo(KeywordRequestDto requestDto, KeywordRequestDto.Entry entry) {
        String keywords = (entry.getKeywords() == null || entry.getKeywords().isEmpty())
                ? "없음"
                : String.join(", ", entry.getKeywords());

        return """
                - Base Directory: %s
                - Relative Path: %s
                - Is Directory: %s
                - Is Dev Resource: %s
                - File Size (Bytes): %d
                - Modified Time: %s
                - Keywords: %s
                """.formatted(
                requestDto.getDirectory(),
                entry.getRelativePath(),
                entry.isDirectory(),
                entry.isDevelopment(),
                entry.getSizeBytes(),
                entry.getModifiedAt(),
                keywords);
    }

    private String buildExistingFoldersInfo(Map<String, Set<String>> existingParaFolders) {
        if (existingParaFolders == null || existingParaFolders.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("- Existing PARA Folder Structure:\n");

        String[] buckets = {"Projects", "Areas", "Resources", "Archive"};
        for (String bucket : buckets) {
            Set<String> folders = existingParaFolders.get(bucket);
            if (folders != null && !folders.isEmpty()) {
                sb.append("  * ").append(bucket).append(": ");
                sb.append(String.join(", ", folders));
                sb.append("\n");
            }
        }

        return sb.toString();
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
