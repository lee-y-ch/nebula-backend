package com.filenori.nebula.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filenori.nebula.dto.response.FileNameResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAiService {

    private static final String OPENAI_API_PATH = "/v1/responses";

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.api.model:gpt-5-mini}")
    private String model;

    private final WebClient openAiWebClient;
    private final ObjectMapper objectMapper; // JSON <-> Java 객체 변환기

    public Mono<FileNameResponseDto> requestFileNameToGpt(String prompt) {
        Map<String, Object> systemMessage = createMessage("system",
                "You only respond with JSON that matches the provided schema. Prefer Korean file names when they sound natural, but respond in English when that is clearer or more conventional for technical terms.");
        Map<String, Object> userMessage = createMessage("user", prompt);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", List.of(systemMessage, userMessage));
        requestBody.put("text", buildTextOptions());

        return openAiWebClient.post()
                .uri(OPENAI_API_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(this::extractOutputContent)
                .doOnNext(this::logPrettyResponseContent)
                .flatMap(content -> Mono.fromCallable(() -> objectMapper.readValue(content, FileNameResponseDto.class))
                        .subscribeOn(Schedulers.boundedElastic()))
                .onErrorMap(original -> original instanceof RuntimeException ? original : new RuntimeException("Failed to call OpenAI API", original));
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

    private Map<String, Object> buildTextOptions() {
        Map<String, Object> paraSchema = new HashMap<>();
        paraSchema.put("type", "object");
        paraSchema.put("properties", Map.of(
                "bucket", Map.of(
                        "type", "string",
                        "enum", List.of("Projects", "Areas", "Resources", "Archive")
                ),
                "path", Map.of("type", "string")
        ));
        paraSchema.put("required", List.of("bucket", "path"));
        paraSchema.put("additionalProperties", false);

        Map<String, Object> schemaDefinition = new HashMap<>();
        schemaDefinition.put("type", "object");
        schemaDefinition.put("properties", Map.of(
                "ko_name", Map.of("type", "string"),
                "en_name", Map.of("type", "string"),
                "para", paraSchema,
                "reason", Map.of("type", "string")
        ));
        schemaDefinition.put("required", List.of("ko_name", "en_name", "para", "reason"));
        schemaDefinition.put("additionalProperties", false);

        Map<String, Object> formatOptions = new HashMap<>();
        formatOptions.put("format", Map.of(
                "type", "json_schema",
                "name", "FileNameResponse",
                "schema", schemaDefinition
        ));

        Map<String, Object> textOptions = new HashMap<>();
        textOptions.put("format", formatOptions);
        System.out.println(textOptions.toString());
        return formatOptions;
    }

    private Mono<String> extractOutputContent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode output = root.path("output");

            if (!output.isArray() || output.isEmpty()) {
                return Mono.error(new IllegalStateException("OpenAI did not return any output"));
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
                return Mono.error(new IllegalStateException("OpenAI returned an empty response"));
            }

            return Mono.just(content);
        } catch (Exception e) {
            return Mono.error(new RuntimeException("Failed to parse OpenAI response", e));
        }
    }

    private void logPrettyResponseContent(String content) {
        try {
            JsonNode jsonNode = objectMapper.readTree(content);
            String pretty = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
            log.info("OpenAI response body:\n{}", pretty);
        } catch (Exception e) {
            log.info("OpenAI response body (raw): {}", content);
        }
    }
}
