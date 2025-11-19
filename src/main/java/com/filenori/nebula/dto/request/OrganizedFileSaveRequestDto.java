package com.filenori.nebula.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class OrganizedFileSaveRequestDto {
    
    private String userId;
    private String baseDirectory;
    private List<OrganizedFileEntryDto> files;
    
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrganizedFileEntryDto {
        private String id; // 업데이트용 (선택사항)
        private String originalRelativePath;
        private boolean directory;
        private boolean development;
        private long sizeBytes;
        private String modifiedAt;
        private List<String> keywords;
        
        // PARA 정리 정보
        private String koreanFileName;
        private String englishFileName;
        private String paraBucket; // Projects, Areas, Resources, Archive
        private String paraFolder; // 하위 폴더 (예: "docs", "config")
        private String reason; // 정리 이유
    }
}