package com.filenori.nebula.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class FileNameResponseDto {

    @JsonProperty("ko_name")
    private String koName;

    @JsonProperty("en_name")
    private String enName;

    @JsonProperty("para")
    private ParaRecommendationDto para;

    private String reason;

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ParaRecommendationDto {

        @JsonProperty("bucket")
        private String bucket;

        @JsonProperty("path")
        private String path;
    }
}
