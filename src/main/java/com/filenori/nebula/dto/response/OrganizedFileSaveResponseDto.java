package com.filenori.nebula.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizedFileSaveResponseDto {
    
    private int totalProcessed;
    private int savedCount;
    private int updatedCount;
    private int failedCount;
    private List<String> errorMessages;
    private List<SavedFileDto> savedFiles;
    private Instant processedAt;
    
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SavedFileDto {
        private String id;
        private String originalRelativePath;
        private String koreanFileName;
        private String englishFileName;
        private String paraBucket;
        private String paraFolder;
        private String operation; // "CREATED" 또는 "UPDATED"
    }
}