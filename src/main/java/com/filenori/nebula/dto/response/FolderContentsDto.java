package com.filenori.nebula.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FolderContentsDto {
    private String folderPath;      // "Projects/Docs"
    private String paraBucket;      // "Projects"
    private String paraFolder;      // "Docs"
    private int totalFiles;
    private int totalSubfolders;
    private List<FileItemDto> files;
    private List<FolderItemDto> subfolders;
    
    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FileItemDto {
        private String id;
        private String koreanFileName;
        private String englishFileName;
        private String displayName;        // 한글명 우선, 없으면 영문명
        private String originalRelativePath;
        private long sizeBytes;
        private String modifiedAt;
        private List<String> keywords;
        private String reason;
        private boolean isDevelopment;
    }
    
    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FolderItemDto {
        private String folderName;
        private String fullPath;           // "Projects/Docs/Subfolder"
        private int fileCount;
        private int subfolderCount;
        private String lastModified;       // 가장 최근 수정된 파일의 날짜
        private List<String> commonKeywords;
    }
}