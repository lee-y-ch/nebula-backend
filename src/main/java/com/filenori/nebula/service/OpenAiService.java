package com.filenori.nebula.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filenori.nebula.dto.response.FileNameResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
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

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/responses";

    public FileNameResponseDto requestFileNameToGpt(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> systemMessage = createMessage("system", "You return only JSON objects that follow the schema provided in the user request.");
        Map<String, Object> userMessage = createMessage("user", prompt);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gpt-4o-mini");
        requestBody.put("input", List.of(systemMessage, userMessage));
        requestBody.put("response_format", Map.of("type", "json_object"));

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        try {
            String response = restTemplate.postForObject(OPENAI_API_URL, requestEntity, String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode output = root.path("output");

            if (!output.isArray() || output.isEmpty()) {
                throw new IllegalStateException("OpenAI did not return any output");
            }

            StringBuilder contentBuilder = new StringBuilder();
            for (JsonNode messageNode : output) {
                JsonNode contentArray = messageNode.path("content");
                if (!contentArray.isArray()) {
                    continue;
                }

                for (JsonNode contentNode : contentArray) {
                    if ("output_text".equals(contentNode.path("type").asText())) {
                        contentBuilder.append(contentNode.path("text").asText());
                    }
                }
            }

            String content = contentBuilder.toString().trim();
            if (content.isEmpty()) {
                throw new IllegalStateException("OpenAI returned an empty response");
            }

            return objectMapper.readValue(content, FileNameResponseDto.class);

        } catch (Exception e) {
            // 예외 처리
            throw new RuntimeException("Failed to call OpenAI API", e);
        }
    }

    private Map<String, Object> createMessage(String role, String text) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", role);

        Map<String, Object> content = new HashMap<>();
        content.put("type", "input_text");
        content.put("text", text);

        List<Map<String, Object>> contentList = new ArrayList<>();
        contentList.add(content);

        message.put("content", contentList);
        return message;
    }

}
