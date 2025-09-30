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
public class PromptController {

    private final PromptService promptService;

    /**
     * 키워드 리스트를 받아 파일 이름을 생성하고 반환하는 API
     * @param requestDto 키워드 리스트를 포함하는 DTO
     * @return 생성된 파일 이름 정보를 담은 DTO
     */

    // 파이썬에서 키워드 리스트 받아지는 함수
    @PostMapping("/generate-filename")
    public ResponseEntity<List<FileNameGenerationResultDto>> generateFileName(@RequestBody KeywordRequestDto requestDto) {

        // @RequestBody 어노테이션으로 JSON 요청 본문을 DTO에 매핑
        List<FileNameGenerationResultDto> responseDto = promptService.generateFileNameFromKeywords(requestDto);

        return ResponseEntity.ok(responseDto);
    }

}
