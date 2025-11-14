package com.filenori.nebula.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class FolderRestructureResponseDto {
    
    @JsonProperty("merge_suggestions")
    private List<MergeSuggestion> mergeSuggestions;
    
    private String reason;
    
    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class MergeSuggestion {
        
        @JsonProperty("target_folder")
        private String targetFolder;
        
        @JsonProperty("source_folders")
        private List<String> sourceFolders;
        
        @JsonProperty("suggested_name")
        private String suggestedName;
        
        private String rationale;
    }
}