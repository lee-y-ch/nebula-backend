package com.filenori.nebula.controller;

import com.filenori.nebula.dto.request.OrganizedFileSaveRequestDto;
import com.filenori.nebula.dto.request.OrganizedFileSaveWithGenerationRequestDto;
import com.filenori.nebula.dto.response.OrganizedFileSaveResponseDto;
import com.filenori.nebula.entity.OrganizedFileDocument;
import com.filenori.nebula.service.OrganizedFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/organized-files")
@RequiredArgsConstructor
@Slf4j
public class OrganizedFileController {

    private final OrganizedFileService organizedFileService;

    /**
     * 키워드를 기반으로 파일명을 자동 생성한 후 MongoDB에 저장
     * 
     * @param requestDto 키워드 정보를 담은 파일 DTO
     * @return 저장/업데이트 결과
     */
    @PostMapping("/save")
    public ResponseEntity<OrganizedFileSaveResponseDto> saveOrganizedFilesWithGeneration(
            @RequestBody OrganizedFileSaveWithGenerationRequestDto requestDto) {
        
        log.info("=== Organized Files Save with Generation Request ===");
        log.info("User: {}, Files Count: {}", 
                requestDto.getUserId(), 
                requestDto.getFiles() != null ? requestDto.getFiles().size() : 0);

        try {
            OrganizedFileSaveResponseDto response = organizedFileService.saveOrganizedFilesWithGeneration(requestDto);
            
            log.info("Save with generation completed - Saved: {}, Updated: {}, Failed: {}", 
                    response.getSavedCount(), response.getUpdatedCount(), response.getFailedCount());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error saving organized files with generation", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 정리된 파일들을 MongoDB에 저장 또는 업데이트 (파일명 이미 생성됨)
     * 
     * @param requestDto 정리된 파일 정보를 담은 DTO
     * @return 저장/업데이트 결과
     */
    @PostMapping("/save-direct")
    public ResponseEntity<OrganizedFileSaveResponseDto> saveOrganizedFilesDirect(
            @RequestBody OrganizedFileSaveRequestDto requestDto) {
        
        log.info("=== Organized Files Save Request ===");
        log.info("User: {}, Files Count: {}", 
                requestDto.getUserId(), 
                requestDto.getFiles() != null ? requestDto.getFiles().size() : 0);

        try {
            OrganizedFileSaveResponseDto response = organizedFileService.saveOrganizedFiles(requestDto);
            
            log.info("Save completed - Saved: {}, Updated: {}, Failed: {}", 
                    response.getSavedCount(), response.getUpdatedCount(), response.getFailedCount());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error saving organized files", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 특정 사용자의 모든 정리된 파일 조회
     * 
     * @param userId 사용자 ID
     * @return 정리된 파일 목록
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<OrganizedFileDocument>> getAllOrganizedFiles(@PathVariable String userId) {
        
        log.info("=== Getting all organized files for user: {} ===", userId);

        try {
            List<OrganizedFileDocument> files = organizedFileService.getAllOrganizedFiles(userId);
            
            log.info("Retrieved {} organized files for user: {}", files.size(), userId);
            return ResponseEntity.ok(files);
            
        } catch (Exception e) {
            log.error("Error retrieving organized files for user: {}", userId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 특정 파일 삭제
     * 
     * @param userId 사용자 ID
     * @param fileId 파일 ID
     * @return 삭제 결과
     */
    @DeleteMapping("/user/{userId}/file/{fileId}")
    public ResponseEntity<String> deleteOrganizedFile(@PathVariable String userId, @PathVariable String fileId) {
        
        log.info("=== Delete organized file request ===");
        log.info("User: {}, File ID: {}", userId, fileId);

        try {
            boolean deleted = organizedFileService.deleteOrganizedFile(userId, fileId);
            
            if (deleted) {
                log.info("Successfully deleted file: {}", fileId);
                return ResponseEntity.ok("File deleted successfully");
            } else {
                log.warn("File not found or not owned by user: {}, {}", userId, fileId);
                return ResponseEntity.notFound().build();
            }
            
        } catch (Exception e) {
            log.error("Error deleting file - User: {}, File: {}", userId, fileId, e);
            return ResponseEntity.internalServerError().body("Error deleting file: " + e.getMessage());
        }
    }

    /**
     * 특정 파일 조회
     * 
     * @param userId 사용자 ID
     * @param fileId 파일 ID
     * @return 파일 정보
     */
    @GetMapping("/user/{userId}/file/{fileId}")
    public ResponseEntity<OrganizedFileDocument> getOrganizedFile(@PathVariable String userId, @PathVariable String fileId) {
        
        log.info("=== Get organized file request ===");
        log.info("User: {}, File ID: {}", userId, fileId);

        try {
            List<OrganizedFileDocument> allFiles = organizedFileService.getAllOrganizedFiles(userId);
            
            OrganizedFileDocument file = allFiles.stream()
                    .filter(f -> f.getId().toString().equals(fileId))
                    .findFirst()
                    .orElse(null);
            
            if (file != null) {
                log.info("Found file: {}", file.getOriginalRelativePath());
                return ResponseEntity.ok(file);
            } else {
                log.warn("File not found or not owned by user: {}, {}", userId, fileId);
                return ResponseEntity.notFound().build();
            }
            
        } catch (Exception e) {
            log.error("Error retrieving file - User: {}, File: {}", userId, fileId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 특정 PARA 버킷의 파일 조회
     * 
     * @param userId 사용자 ID
     * @param paraBucket PARA 버킷 (Projects, Areas, Resources, Archive)
     * @return 해당 버킷의 파일 목록
     */
    @GetMapping("/user/{userId}/bucket/{paraBucket}")
    public ResponseEntity<List<OrganizedFileDocument>> getFilesByParaBucket(
            @PathVariable String userId, 
            @PathVariable String paraBucket) {
        
        log.info("=== Getting files by PARA bucket ===");
        log.info("User: {}, PARA Bucket: {}", userId, paraBucket);

        try {
            List<OrganizedFileDocument> allFiles = organizedFileService.getAllOrganizedFiles(userId);
            
            List<OrganizedFileDocument> bucketFiles = allFiles.stream()
                    .filter(file -> paraBucket.equalsIgnoreCase(file.getParaBucket()))
                    .toList();
            
            log.info("Found {} files in bucket '{}' for user: {}", bucketFiles.size(), paraBucket, userId);
            return ResponseEntity.ok(bucketFiles);
            
        } catch (Exception e) {
            log.error("Error retrieving files by bucket - User: {}, Bucket: {}", userId, paraBucket, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 파일 통계 조회
     * 
     * @param userId 사용자 ID
     * @return 파일 통계 정보
     */
    @GetMapping("/user/{userId}/stats")
    public ResponseEntity<FileStatsDto> getFileStats(@PathVariable String userId) {
        
        log.info("=== Getting file stats for user: {} ===", userId);

        try {
            List<OrganizedFileDocument> allFiles = organizedFileService.getAllOrganizedFiles(userId);
            
            long projectsCount = allFiles.stream().filter(f -> "Projects".equalsIgnoreCase(f.getParaBucket())).count();
            long areasCount = allFiles.stream().filter(f -> "Areas".equalsIgnoreCase(f.getParaBucket())).count();
            long resourcesCount = allFiles.stream().filter(f -> "Resources".equalsIgnoreCase(f.getParaBucket())).count();
            long archiveCount = allFiles.stream().filter(f -> "Archive".equalsIgnoreCase(f.getParaBucket())).count();
            long developmentCount = allFiles.stream().filter(OrganizedFileDocument::isDevelopment).count();
            
            FileStatsDto stats = new FileStatsDto(
                    allFiles.size(),
                    projectsCount,
                    areasCount,
                    resourcesCount,
                    archiveCount,
                    developmentCount
            );
            
            log.info("File stats for user {}: Total={}, Projects={}, Areas={}, Resources={}, Archive={}, Development={}", 
                    userId, stats.totalFiles(), stats.projectsCount(), stats.areasCount(), 
                    stats.resourcesCount(), stats.archiveCount(), stats.developmentCount());
            
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            log.error("Error retrieving file stats for user: {}", userId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // 파일 통계 DTO
    public record FileStatsDto(
            long totalFiles,
            long projectsCount,
            long areasCount,
            long resourcesCount,
            long archiveCount,
            long developmentCount
    ) {}
}