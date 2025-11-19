package com.filenori.nebula.controller;

import com.filenori.nebula.dto.request.FolderRestructureRequestDto;
import com.filenori.nebula.dto.response.FolderContentsDto;
import com.filenori.nebula.dto.response.FolderRestructureResponseDto;
import com.filenori.nebula.service.FolderBrowsingService;
import com.filenori.nebula.service.FolderRestructureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/folders")
@RequiredArgsConstructor
@Slf4j
public class FolderRestructureController {

    private final FolderRestructureService folderRestructureService;
    private final FolderBrowsingService folderBrowsingService;

    @PostMapping("/analyze-structure")
    public Mono<ResponseEntity<FolderRestructureResponseDto>> analyzeFolderStructure(
            @RequestBody FolderRestructureRequestDto requestDto) {
        
        log.info("=== Folder Structure Analysis Request ===");
        log.info("User ID: {}, PARA Bucket: {}", requestDto.getUserId(), requestDto.getParaBucket());

        return folderRestructureService.analyzeFolderStructure(requestDto)
                .map(ResponseEntity::ok)
                .doOnNext(response -> log.info("Analysis completed with {} suggestions", 
                        response.getBody().getMergeSuggestions().size()))
                .onErrorResume(error -> {
                    log.error("Error analyzing folder structure", error);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }

    @PostMapping("/apply-merge")
    public Mono<ResponseEntity<String>> applyFolderMerge(
            @RequestParam String userId,
            @RequestParam String paraBucket,
            @RequestBody FolderRestructureResponseDto.MergeSuggestion suggestion) {
        
        log.info("=== Applying Folder Merge ===");
        log.info("User: {}, Bucket: {}, Merging {} folders into '{}'", 
                userId, paraBucket, suggestion.getSourceFolders().size(), suggestion.getSuggestedName());

        return folderRestructureService.applyFolderRestructure(userId, paraBucket, suggestion)
                .map(ResponseEntity::ok)
                .doOnNext(response -> log.info("Folder merge completed: {}", response.getBody()))
                .onErrorResume(error -> {
                    log.error("Error applying folder merge", error);
                    return Mono.just(ResponseEntity.internalServerError()
                            .body("폴더 통합 중 오류가 발생했습니다: " + error.getMessage()));
                });
    }

    @GetMapping("/browse")
    public ResponseEntity<FolderContentsDto> browseFolderContents(
            @RequestParam String userId,
            @RequestParam String paraBucket,
            @RequestParam(required = false, defaultValue = "") String paraFolder) {
        
        log.info("=== Folder Browsing Request ===");
        log.info("User: {}, Bucket: {}, Folder: '{}'", userId, paraBucket, paraFolder);

        try {
            // paraFolder가 비어있으면 루트 폴더로 처리
            String targetFolder = (paraFolder == null || paraFolder.trim().isEmpty()) ? "" : paraFolder.trim();
            
            FolderContentsDto contents = folderBrowsingService.getFolderContents(userId, paraBucket, targetFolder);
            
            log.info("Found {} files and {} subfolders", contents.getTotalFiles(), contents.getTotalSubfolders());
            return ResponseEntity.ok(contents);
            
        } catch (Exception error) {
            log.error("Error browsing folder contents", error);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/breadcrumb")
    public ResponseEntity<List<String>> getFolderBreadcrumb(
            @RequestParam String paraBucket,
            @RequestParam(required = false, defaultValue = "") String paraFolder) {
        
        try {
            List<String> breadcrumb = folderBrowsingService.getFolderBreadcrumb(paraBucket, paraFolder);
            return ResponseEntity.ok(breadcrumb);
        } catch (Exception error) {
            log.error("Error generating breadcrumb", error);
            return ResponseEntity.internalServerError().build();
        }
    }
}