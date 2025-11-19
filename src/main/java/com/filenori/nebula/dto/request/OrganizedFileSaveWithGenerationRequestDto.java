package com.filenori.nebula.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class OrganizedFileSaveWithGenerationRequestDto {
    
    private String userId;
    private String baseDirectory;
    private List<FileEntryForGeneration> files;
    
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileEntryForGeneration {
        private String relativePath;
        private String absolutePath;
        
        @JsonProperty("isDirectory")
        private boolean isDirectory;
        
        private long sizeBytes;
        private String modifiedAt;
        
        @JsonProperty("isDevelopment")
        private boolean isDevelopment;
        
        private List<String> keywords;
    }
}