package com.filenori.nebula.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class FileNameResponseDto {
    private String generatedFileName;
    private String reason;
}
