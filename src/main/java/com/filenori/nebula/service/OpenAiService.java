package com.filenori.nebula.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filenori.nebula.dto.response.FileNameResponseDto;
import com.filenori.nebula.dto.response.OpenAiApiResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OpenAiService {

    @Value("${openai.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper; // JSON <-> Java 객체 변환기

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";

    public FileNameResponseDto requestFileNameToGpt(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-3.5-turbo");
        requestBody.put("messages", List.of(message));
        //OpenAI가 반드시 JSON 형태로 응답하도록 강제하는 옵션 (최신 모델에서 지원)
        requestBody.put("response_format", Map.of("type", "json_object"));

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        try {
            // OpenAI API 호출
            String response = restTemplate.postForObject(OPENAI_API_URL, requestEntity, String.class);

            // 응답 JSON 파싱
            // DTO를 사용해 한 번에 파싱
            OpenAiApiResponseDto apiResponse = objectMapper.readValue(response, OpenAiApiResponseDto.class);

            // content 문자열 추출
            String content = apiResponse.getChoices().get(0).getMessage().getContent();

            // content 내부의 JSON 문자열을 DTO로 변환
            return objectMapper.readValue(content, FileNameResponseDto.class);

        } catch (Exception e) {
            // 예외 처리
            throw new RuntimeException("Failed to call OpenAI API", e);
        }
    }

}
