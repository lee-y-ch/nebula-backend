package com.filenori.nebula.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizedFileSearchResponseDto {
    private List<SearchResultDto> results;
    private long total;
    private long tookMs;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchResultDto {
        private String id;
        private String koreanFileName;
        private String englishFileName;
        private String originalRelativePath;
        private String paraBucket;
        private String paraFolder;
        private String reason;
        private List<String> keywords;
        private double similarity;
        private long sizeBytes;
        private String modifiedAt;
    }
}

