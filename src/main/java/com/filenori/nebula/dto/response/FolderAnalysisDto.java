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
public class FolderAnalysisDto {
    private String folderName;
    private int fileCount;
    private int subfolderCount;
    private List<String> sampleFileNames; // 대표 파일명들 (최대 5개)
    private List<String> commonKeywords; // 공통 키워드들
    private String folderPurpose; // 폴더 용도 추론
}