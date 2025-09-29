package com.filenori.nebula.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class KeywordRequestDto {
    private String directory;
    private String generatedAt;
    private Integer page;
    private Integer pageCount;
    private Integer pageSize;
    private Integer totalEntries;
    private List<Entry> entries;

    @Getter
    @NoArgsConstructor
    public static class Entry {
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
