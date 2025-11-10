package com.filenori.nebula.dto.response;

import java.util.List;

public record ParaFolderTreeResponseDto(String bucket, String path, List<ParaFolderNodeDto> folders) {
}
