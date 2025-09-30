package com.filenori.nebula.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class FileNameGenerationResultDto {
    private String relativePath;
    private String koreanFileName;
    private String englishFileName;
    private String paraBucket;
    private String paraPath;
    private String reason;
}
