package com.filenori.nebula.service;

import com.filenori.nebula.dto.request.KeywordRequestDto;
import com.filenori.nebula.dto.request.OrganizedFileSaveRequestDto;
import com.filenori.nebula.dto.request.OrganizedFileSaveWithGenerationRequestDto;
import com.filenori.nebula.dto.response.FileNameGenerationResultDto;
import com.filenori.nebula.dto.response.OrganizedFileSaveResponseDto;
import com.filenori.nebula.entity.OrganizedFileDocument;
import com.filenori.nebula.repository.OrganizedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizedFileService {

    private final OrganizedFileRepository organizedFileRepository;
    private final PromptService promptService;
    private final SageMakerEmbeddingService embeddingService;

    /**
     * 키워드를 기반으로 파일명을 자동 생성한 후 저장
     */
    @Transactional
    public OrganizedFileSaveResponseDto saveOrganizedFilesWithGeneration(OrganizedFileSaveWithGenerationRequestDto requestDto) {
        log.info("=== Starting saveOrganizedFilesWithGeneration ===");
        log.info("User: {}, Base Directory: {}, Files Count: {}", 
                requestDto.getUserId(), requestDto.getBaseDirectory(), 
                requestDto.getFiles() != null ? requestDto.getFiles().size() : 0);

        // 키워드 요청 검증
        ValidationResult validation = validateGenerationRequest(requestDto);
        if (!validation.isValid()) {
            return OrganizedFileSaveResponseDto.builder()
                    .totalProcessed(0)
                    .savedCount(0)
                    .updatedCount(0)
                    .failedCount(0)
                    .errorMessages(validation.getErrorMessages())
                    .savedFiles(List.of())
                    .processedAt(Instant.now())
                    .build();
        }

        try {
            // 1. KeywordRequestDto로 변환하여 파일명 생성
            KeywordRequestDto keywordRequest = convertToKeywordRequest(requestDto);
            log.info("Generating file names for {} files using OpenAI", keywordRequest.getEntries().size());

            List<FileNameGenerationResultDto> generatedResults = promptService.generateFileNameFromKeywords(keywordRequest);
            log.info("Generated {} file name results", generatedResults.size());

            // 2. 생성된 결과를 OrganizedFileSaveRequestDto로 변환
            OrganizedFileSaveRequestDto saveRequest = convertToSaveRequest(requestDto, generatedResults);
            log.info("Converted to save request with {} files", saveRequest.getFiles().size());

            // 3. 기존 저장 로직 사용
            return saveOrganizedFiles(saveRequest);
            
        } catch (Exception e) {
            log.error("Error in saveOrganizedFilesWithGeneration", e);
            return OrganizedFileSaveResponseDto.builder()
                    .totalProcessed(0)
                    .savedCount(0)
                    .updatedCount(0)
                    .failedCount(1)
                    .errorMessages(List.of("File name generation failed: " + e.getMessage()))
                    .savedFiles(List.of())
                    .processedAt(Instant.now())
                    .build();
        }
    }

    /**
     * 기존 저장 메서드 (이미 생성된 파일명으로 저장)
     */
    @Transactional
    public OrganizedFileSaveResponseDto saveOrganizedFiles(OrganizedFileSaveRequestDto requestDto) {
        log.info("=== Starting saveOrganizedFiles ===");
        log.info("User: {}, Base Directory: {}, Files Count: {}", 
                requestDto.getUserId(), requestDto.getBaseDirectory(), 
                requestDto.getFiles() != null ? requestDto.getFiles().size() : 0);

        // 입력 검증
        ValidationResult validation = validateRequest(requestDto);
        if (!validation.isValid()) {
            return OrganizedFileSaveResponseDto.builder()
                    .totalProcessed(0)
                    .savedCount(0)
                    .updatedCount(0)
                    .failedCount(0)
                    .errorMessages(validation.getErrorMessages())
                    .savedFiles(List.of())
                    .processedAt(Instant.now())
                    .build();
        }

        ObjectId userId = new ObjectId(requestDto.getUserId());
        List<OrganizedFileSaveRequestDto.OrganizedFileEntryDto> files = requestDto.getFiles();

        // 기존 파일들 조회 (업데이트 vs 신규 생성 판단용)
        Map<String, OrganizedFileDocument> existingFileMap = getExistingFiles(userId, files);

        // 배치 처리
        List<OrganizedFileSaveResponseDto.SavedFileDto> savedFiles = new ArrayList<>();
        List<String> errorMessages = new ArrayList<>();
        int savedCount = 0;
        int updatedCount = 0;
        int failedCount = 0;

        for (OrganizedFileSaveRequestDto.OrganizedFileEntryDto fileDto : files) {
            try {
                OrganizedFileDocument existing = existingFileMap.get(fileDto.getOriginalRelativePath());
                OrganizedFileDocument document = convertToDocument(requestDto, fileDto, existing, userId);
                
                OrganizedFileDocument saved = organizedFileRepository.save(document);
                
                boolean isUpdate = existing != null;
                if (isUpdate) {
                    updatedCount++;
                } else {
                    savedCount++;
                }

                savedFiles.add(OrganizedFileSaveResponseDto.SavedFileDto.builder()
                        .id(saved.getId().toString())
                        .originalRelativePath(saved.getOriginalRelativePath())
                        .koreanFileName(saved.getKoreanFileName())
                        .englishFileName(saved.getEnglishFileName())
                        .paraBucket(saved.getParaBucket())
                        .paraFolder(saved.getParaFolder())
                        .operation(isUpdate ? "UPDATED" : "CREATED")
                        .build());

                log.debug("Successfully {} file: {}", isUpdate ? "updated" : "saved", 
                         fileDto.getOriginalRelativePath());

            } catch (DuplicateKeyException e) {
                failedCount++;
                String errorMsg = String.format("Duplicate key error for file '%s': %s", 
                                               fileDto.getOriginalRelativePath(), e.getMessage());
                errorMessages.add(errorMsg);
                log.error(errorMsg, e);
                
            } catch (Exception e) {
                failedCount++;
                String errorMsg = String.format("Error processing file '%s': %s", 
                                               fileDto.getOriginalRelativePath(), e.getMessage());
                errorMessages.add(errorMsg);
                log.error(errorMsg, e);
            }
        }

        log.info("=== saveOrganizedFiles completed ===");
        log.info("Total: {}, Saved: {}, Updated: {}, Failed: {}", 
                files.size(), savedCount, updatedCount, failedCount);

        return OrganizedFileSaveResponseDto.builder()
                .totalProcessed(files.size())
                .savedCount(savedCount)
                .updatedCount(updatedCount)
                .failedCount(failedCount)
                .errorMessages(errorMessages)
                .savedFiles(savedFiles)
                .processedAt(Instant.now())
                .build();
    }

    private ValidationResult validateGenerationRequest(OrganizedFileSaveWithGenerationRequestDto requestDto) {
        List<String> errors = new ArrayList<>();

        if (requestDto.getUserId() == null || requestDto.getUserId().isBlank()) {
            errors.add("userId is required");
        } else {
            try {
                new ObjectId(requestDto.getUserId());
            } catch (IllegalArgumentException e) {
                errors.add("Invalid userId format: " + requestDto.getUserId());
            }
        }

        if (requestDto.getBaseDirectory() == null || requestDto.getBaseDirectory().isBlank()) {
            errors.add("baseDirectory is required");
        }

        if (requestDto.getFiles() == null || requestDto.getFiles().isEmpty()) {
            errors.add("files list cannot be empty");
        } else {
            for (int i = 0; i < requestDto.getFiles().size(); i++) {
                OrganizedFileSaveWithGenerationRequestDto.FileEntryForGeneration file = requestDto.getFiles().get(i);
                if (file.getRelativePath() == null || file.getRelativePath().isBlank()) {
                    errors.add(String.format("File at index %d: relativePath is required", i));
                }
                // keywords는 null이거나 비어있어도 허용 (OpenAI가 파일 경로와 기타 정보로 추론)
            }
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    private ValidationResult validateRequest(OrganizedFileSaveRequestDto requestDto) {
        List<String> errors = new ArrayList<>();

        if (requestDto.getUserId() == null || requestDto.getUserId().isBlank()) {
            errors.add("userId is required");
        } else {
            try {
                new ObjectId(requestDto.getUserId());
            } catch (IllegalArgumentException e) {
                errors.add("Invalid userId format: " + requestDto.getUserId());
            }
        }

        if (requestDto.getBaseDirectory() == null || requestDto.getBaseDirectory().isBlank()) {
            errors.add("baseDirectory is required");
        }

        if (requestDto.getFiles() == null || requestDto.getFiles().isEmpty()) {
            errors.add("files list cannot be empty");
        } else {
            for (int i = 0; i < requestDto.getFiles().size(); i++) {
                OrganizedFileSaveRequestDto.OrganizedFileEntryDto file = requestDto.getFiles().get(i);
                if (file.getOriginalRelativePath() == null || file.getOriginalRelativePath().isBlank()) {
                    errors.add(String.format("File at index %d: originalRelativePath is required", i));
                }
                if (file.getParaBucket() == null || file.getParaBucket().isBlank()) {
                    errors.add(String.format("File at index %d: paraBucket is required", i));
                }
            }
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    private Map<String, OrganizedFileDocument> getExistingFiles(ObjectId userId, 
                                                                 List<OrganizedFileSaveRequestDto.OrganizedFileEntryDto> files) {
        List<String> relativePaths = files.stream()
                .map(OrganizedFileSaveRequestDto.OrganizedFileEntryDto::getOriginalRelativePath)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (relativePaths.isEmpty()) {
            return Map.of();
        }

        return organizedFileRepository.findByUserIdAndOriginalRelativePathIn(userId, relativePaths)
                .stream()
                .collect(Collectors.toMap(
                        OrganizedFileDocument::getOriginalRelativePath,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));
    }

    private OrganizedFileDocument convertToDocument(OrganizedFileSaveRequestDto requestDto,
                                                     OrganizedFileSaveRequestDto.OrganizedFileEntryDto fileDto,
                                                     OrganizedFileDocument existing,
                                                     ObjectId userId) {
        
        String paraFullPath = buildParaFullPath(fileDto.getParaBucket(), fileDto.getParaFolder());

        OrganizedFileDocument.OrganizedFileDocumentBuilder builder = OrganizedFileDocument.builder()
                .id(existing != null ? existing.getId() : null)
                .userId(userId)
                .baseDirectory(requestDto.getBaseDirectory())
                .originalRelativePath(fileDto.getOriginalRelativePath())
                .directory(fileDto.isDirectory())
                .development(fileDto.isDevelopment())
                .sizeBytes(fileDto.getSizeBytes())
                .modifiedAt(fileDto.getModifiedAt())
                .keywords(fileDto.getKeywords() != null ? List.copyOf(fileDto.getKeywords()) : null)
                .koreanFileName(fileDto.getKoreanFileName())
                .englishFileName(fileDto.getEnglishFileName())
                .paraBucket(fileDto.getParaBucket())
                .paraFolder(fileDto.getParaFolder())
                .paraFullPath(paraFullPath)
                .reason(fileDto.getReason())
                .createdAt(existing != null && existing.getCreatedAt() != null
                        ? existing.getCreatedAt()
                        : Instant.now());

        enrichWithEmbedding(builder, fileDto, existing);

        return builder.build();
    }

    private void enrichWithEmbedding(OrganizedFileDocument.OrganizedFileDocumentBuilder builder,
                                     OrganizedFileSaveRequestDto.OrganizedFileEntryDto fileDto,
                                     OrganizedFileDocument existing) {
        String embeddingInput = buildEmbeddingText(fileDto);
        embeddingService.embedText(embeddingInput).ifPresentOrElse(embedding -> builder
                        .embedding(embedding)
                        .embeddingUpdatedAt(Instant.now()),
                () -> {
                    if (existing != null && existing.getEmbedding() != null) {
                        builder.embedding(existing.getEmbedding());
                        builder.embeddingUpdatedAt(existing.getEmbeddingUpdatedAt());
                    }
                });
    }

    private String buildEmbeddingText(OrganizedFileSaveRequestDto.OrganizedFileEntryDto fileDto) {
        StringBuilder sb = new StringBuilder();
        if (fileDto.getKoreanFileName() != null) {
            sb.append(fileDto.getKoreanFileName()).append(' ');
        }
        if (fileDto.getEnglishFileName() != null) {
            sb.append(fileDto.getEnglishFileName()).append(' ');
        }
        // if (fileDto.getParaBucket() != null) {
        //     sb.append("bucket:").append(fileDto.getParaBucket()).append(' ');
        // }
        // if (fileDto.getParaFolder() != null) {
        //     sb.append(fileDto.getParaFolder()).append(' ');
        // }
        if (fileDto.getReason() != null) {
            sb.append(fileDto.getReason()).append(' ');
        }
        if (fileDto.getKeywords() != null && !fileDto.getKeywords().isEmpty()) {
            sb.append(String.join(" ", fileDto.getKeywords())).append(' ');
        }
        return sb.toString().trim();
    }

    private String buildParaFullPath(String paraBucket, String paraFolder) {
        if (paraBucket == null || paraBucket.isBlank()) {
            return null;
        }

        String bucketLower = paraBucket.toLowerCase();
        if (paraFolder == null || paraFolder.isBlank()) {
            return bucketLower;
        }

        return bucketLower + "/" + paraFolder.toLowerCase();
    }

    private static class ValidationResult {
        private final boolean valid;
        private final List<String> errorMessages;

        public ValidationResult(boolean valid, List<String> errorMessages) {
            this.valid = valid;
            this.errorMessages = errorMessages;
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getErrorMessages() {
            return errorMessages;
        }
    }

    public boolean deleteOrganizedFile(String userId, String fileId) {
        log.info("=== Deleting organized file ===");
        log.info("User: {}, File ID: {}", userId, fileId);

        try {
            ObjectId userObjectId = new ObjectId(userId);
            ObjectId fileObjectId = new ObjectId(fileId);

            boolean exists = organizedFileRepository.existsByIdAndUserId(fileObjectId, userObjectId);
            if (!exists) {
                log.warn("File not found or not owned by user: {}, {}", userId, fileId);
                return false;
            }

            organizedFileRepository.deleteById(fileObjectId);
            log.info("Successfully deleted file: {}", fileId);
            return true;

        } catch (IllegalArgumentException e) {
            log.error("Invalid ID format - User: {}, File: {}", userId, fileId, e);
            return false;
        } catch (Exception e) {
            log.error("Error deleting file - User: {}, File: {}", userId, fileId, e);
            return false;
        }
    }

    public List<OrganizedFileDocument> getAllOrganizedFiles(String userId) {
        log.info("=== Getting all organized files for user: {} ===", userId);

        try {
            ObjectId userObjectId = new ObjectId(userId);
            return organizedFileRepository.findByUserId(userObjectId);
        } catch (IllegalArgumentException e) {
            log.error("Invalid userId format: {}", userId, e);
            return List.of();
        }
    }

    // ========== 변환 메서드들 ==========

    private KeywordRequestDto convertToKeywordRequest(OrganizedFileSaveWithGenerationRequestDto requestDto) {
        List<KeywordRequestDto.Entry> entries = requestDto.getFiles().stream()
                .map(file -> {
                    KeywordRequestDto.Entry entry = new KeywordRequestDto.Entry();
                    // Reflection을 사용하여 private 필드에 값 설정
                    setFieldValue(entry, "relativePath", file.getRelativePath());
                    setFieldValue(entry, "absolutePath", file.getAbsolutePath());
                    setFieldValue(entry, "isDirectory", file.isDirectory());
                    setFieldValue(entry, "sizeBytes", file.getSizeBytes());
                    setFieldValue(entry, "modifiedAt", file.getModifiedAt());
                    setFieldValue(entry, "isDevelopment", file.isDevelopment());
                    setFieldValue(entry, "keywords", file.getKeywords());
                    return entry;
                })
                .collect(Collectors.toList());

        KeywordRequestDto keywordRequest = new KeywordRequestDto();
        setFieldValue(keywordRequest, "userId", requestDto.getUserId());
        setFieldValue(keywordRequest, "directory", requestDto.getBaseDirectory());
        setFieldValue(keywordRequest, "entries", entries);

        return keywordRequest;
    }

    private OrganizedFileSaveRequestDto convertToSaveRequest(OrganizedFileSaveWithGenerationRequestDto originalRequest,
                                                              List<FileNameGenerationResultDto> generatedResults) {
        
        Map<String, FileNameGenerationResultDto> resultsByPath = generatedResults.stream()
                .collect(Collectors.toMap(
                        FileNameGenerationResultDto::getRelativePath,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));

        List<OrganizedFileSaveRequestDto.OrganizedFileEntryDto> saveEntries = originalRequest.getFiles().stream()
                .map(file -> {
                    FileNameGenerationResultDto generated = resultsByPath.get(file.getRelativePath());
                    if (generated == null) {
                        log.warn("No generated result found for file: {}", file.getRelativePath());
                        return null;
                    }

                    return new OrganizedFileSaveRequestDto.OrganizedFileEntryDto(
                            null, // id는 null (신규 저장)
                            file.getRelativePath(),
                            file.isDirectory(),
                            file.isDevelopment(),
                            file.getSizeBytes(),
                            file.getModifiedAt(),
                            file.getKeywords(),
                            generated.getKoreanFileName(),
                            generated.getEnglishFileName(),
                            generated.getParaBucket(),
                            generated.getParaPath(), // paraFolder로 사용
                            generated.getReason()
                    );
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return new OrganizedFileSaveRequestDto(
                originalRequest.getUserId(),
                originalRequest.getBaseDirectory(),
                saveEntries
        );
    }

    private void setFieldValue(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            log.error("Failed to set field {} on {}: {}", fieldName, target.getClass().getSimpleName(), e.getMessage());
        }
    }
}