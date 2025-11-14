package com.filenori.nebula.controller;


import com.filenori.nebula.dto.request.KeywordRequestDto;
import com.filenori.nebula.dto.response.FileNameGenerationResultDto;
import com.filenori.nebula.service.PromptService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/filenames")
public class PromptController {

    private final PromptService promptService;

    /**
     * 파일 이름을 생성하는 API (내부적으로 배치 처리)
     * 50개씩 묶어서 OpenAI API에 요청하므로 대량의 파일 처리에 효율적입니다.
     *
     * @param requestDto 키워드 리스트를 포함하는 DTO
     *                   - userId: 사용자 ID
     *                   - directory: 기본 디렉토리 경로
     *                   - entries: 파일 정보 리스트 (최대 500개 권장)
     * @return 생성된 파일 이름 정보를 담은 DTO 리스트
     */
    @PostMapping("/generate-filename")
    public ResponseEntity<List<FileNameGenerationResultDto>> generateFileName(@RequestBody KeywordRequestDto requestDto) {
        long startTime = System.currentTimeMillis();

        List<FileNameGenerationResultDto> responseDto = promptService.generateFileNameFromKeywords(requestDto);

        long processingTime = System.currentTimeMillis() - startTime;
        if (!responseDto.isEmpty()) {
            int totalFiles = requestDto.getEntries() != null ? requestDto.getEntries().size() : 0;
            int batchCount = (totalFiles + 49) / 50; // 올림 계산
            System.out.println("Processing complete: " + totalFiles + " files in " + batchCount +
                             " batches, took " + processingTime + "ms");
        }

        return ResponseEntity.ok(responseDto);
    }

}
