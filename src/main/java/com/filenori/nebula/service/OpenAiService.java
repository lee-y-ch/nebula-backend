package com.filenori.nebula.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filenori.nebula.dto.response.FileNameResponseDto;
import com.filenori.nebula.dto.response.FolderRestructureResponseDto;
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

    public Mono<FileNameResponseDto> requestFileNameToGpt(String prompt, String systemPrompt) {
        Map<String, Object> systemMessage = createSimpleMessage("system", systemPrompt);
        Map<String, Object> userMessage = createSimpleMessage("user", prompt);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", List.of(systemMessage, userMessage));
        requestBody.put("text", buildSingleTextFormat());

        try {
            String prettyRequest = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(requestBody);
            log.info("Request body:\n{}", prettyRequest);
        } catch (Exception e) {
            log.info("Request body: {}", requestBody);
        }

        return openAiWebClient.post()
                .uri(OPENAI_API_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response ->
                        response.bodyToMono(String.class)
                                .doOnNext(errorBody -> log.error("OpenAI API Error - {}", errorBody))
                                .then(Mono.error(new RuntimeException("OpenAI API returned error")))
                )
                .bodyToMono(String.class)
                .flatMap(this::extractOutputContent)
                .doOnNext(this::logPrettyResponseContent)
                .flatMap(content -> Mono.fromCallable(() -> objectMapper.readValue(content, FileNameResponseDto.class))
                        .subscribeOn(Schedulers.boundedElastic()))
                .onErrorMap(original -> original instanceof RuntimeException ? original : new RuntimeException("Failed to call OpenAI API", original));
    }

    public Mono<List<FileNameResponseDto>> requestFileNameToGptBatch(List<String> prompts, String systemPrompt, String existingFoldersInfo) {
        // 50개 파일 정보를 모두 하나의 프롬프트에 포함
        String combinedPrompt = buildCombinedBatchPrompt(prompts, existingFoldersInfo);

        Map<String, Object> systemMessage = createSimpleMessage("system", systemPrompt);
        Map<String, Object> userMessage = createSimpleMessage("user", combinedPrompt);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", List.of(systemMessage, userMessage));
        requestBody.put("text", buildBatchTextFormat(prompts.size()));

        log.info("Sending batch request for {} files", prompts.size());

        try {
            String prettyRequest = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(requestBody);
            log.info("Batch request body:\n{}", prettyRequest);
        } catch (Exception e) {
            log.info("Batch request body: {}", requestBody);
        }

        return openAiWebClient.post()
                .uri(OPENAI_API_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response ->
                        response.bodyToMono(String.class)
                                .doOnNext(errorBody -> log.error("OpenAI Batch API Error - {}", errorBody))
                                .then(Mono.error(new RuntimeException("OpenAI API returned error")))
                )
                .bodyToMono(String.class)
                .doOnNext(this::logPrettyBatchResponseContent)
                .flatMap(response -> Mono.fromCallable(() ->
                        extractBatchArrayResponse(response, prompts.size())
                ).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(jsonArray -> Mono.fromCallable(() ->
                        parseBatchResponseArray(jsonArray)
                ).subscribeOn(Schedulers.boundedElastic()))
                .onErrorMap(original -> original instanceof RuntimeException ? original : new RuntimeException("Failed to call OpenAI API batch", original));
    }


    private String buildCombinedBatchPrompt(List<String> fileInfos, String existingFoldersInfo) {
        StringBuilder sb = new StringBuilder();

        // 지시사항
        sb.append("You are an expert assistant specializing in file organization. ")
                .append("Your task is to analyze the provided file metadata for ").append(fileInfos.size())
                .append(" files and provide naming suggestions and P.A.R.A. organization recommendations.\n\n");

        // 기존 PARA 폴더 구조 (한 번만 포함)
        if (existingFoldersInfo != null && !existingFoldersInfo.isBlank()) {
            sb.append("**Existing PARA Folder Structure (prefer using existing folders when appropriate):**\n");
            sb.append(existingFoldersInfo).append("\n");
        }

        sb.append("Rules:\n")
                .append("1. **Propose two filenames for each file:**\n")
                .append("   * **Korean (ko_name):** Use natural and intuitive Korean.\n")
                .append("   * **English (en_name):** Use professional, standard English.\n")
                .append("   * **IMPORTANT:** Do NOT include file extensions (e.g., .pdf, .jpg, .docx) in the proposed filenames.\n")
                .append("2. **Use spaces in filenames:** You must use spaces in the filenames.\n")
                .append("3. **Keyword Priority:** If the provided keywords differ from the original filename, ")
                .append("prioritize the keywords over the filename when determining the new name, bucket, and path. ")
                .append("Keywords represent the actual content of the file extracted via ML analysis.\n")
                .append("4. **P.A.R.A. Recommendation:**\n")
                .append("   * Determine the correct P.A.R.A. bucket: **Projects, Areas, Resources, or Archive**.\n")
                .append("   * Propose a path using only **lowercase** folder names (e.g., `projects/nebula`).\n")
                .append("   * The path **must not** include the filename itself.\n")
                .append("   * The path must be **at most 2 levels deep** (bucket + one subfolder).\n")
                .append("   * **Prefer existing folders** from the structure above when the file fits an existing category.\n")
                .append("5. **Reasoning:** Briefly explain your P.A.R.A. choice and naming logic.\n\n");

        sb.append("**Output Format:**\n")
                .append("Return ONLY a JSON array with exactly ").append(fileInfos.size())
                .append(" objects. Each object must have: ko_name, en_name, para (with bucket and path), and reason.\n")
                .append("Do not provide any text or explanation outside the JSON array.\n\n");

        sb.append("Example format:\n")
                .append("[\n")
                .append("  {\"ko_name\": \"...\", \"en_name\": \"...\", \"para\": {\"bucket\": \"Projects\", \"path\": \"...\"}, \"reason\": \"...\"},\n")
                .append("  {\"ko_name\": \"...\", \"en_name\": \"...\", \"para\": {\"bucket\": \"Areas\", \"path\": \"...\"}, \"reason\": \"...\"}\n")
                .append("]\n\n");

        sb.append("Files to process:\n\n");

        // 각 파일 정보 추가
        for (int i = 0; i < fileInfos.size(); i++) {
            sb.append("FILE ").append(i + 1).append(":\n");
            sb.append(fileInfos.get(i)).append("\n\n");
        }

        return sb.toString();
    }

    private Map<String, Object> buildSingleTextFormat() {
        Map<String, Object> paraSchema = new HashMap<>();
        paraSchema.put("type", "object");
        paraSchema.put("properties", Map.of(
                "bucket", Map.of("type", "string", "enum", List.of("Projects", "Areas", "Resources", "Archive")),
                "path", Map.of("type", "string")
        ));
        paraSchema.put("required", List.of("bucket", "path"));
        paraSchema.put("additionalProperties", false);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "ko_name", Map.of("type", "string"),
                "en_name", Map.of("type", "string"),
                "para", paraSchema,
                "reason", Map.of("type", "string")
        ));
        schema.put("required", List.of("ko_name", "en_name", "para", "reason"));
        schema.put("additionalProperties", false);

        Map<String, Object> format = new HashMap<>();
        format.put("type", "json_schema");
        format.put("name", "FileNameResponse");
        format.put("schema", schema);
        format.put("strict", true);

        Map<String, Object> textFormat = new HashMap<>();
        textFormat.put("format", format);

        return textFormat;
    }

    private Map<String, Object> buildBatchTextFormat(int fileCount) {
        Map<String, Object> paraSchema = new HashMap<>();
        paraSchema.put("type", "object");
        paraSchema.put("properties", Map.of(
                "bucket", Map.of("type", "string", "enum", List.of("Projects", "Areas", "Resources", "Archive")),
                "path", Map.of("type", "string")
        ));
        paraSchema.put("required", List.of("bucket", "path"));
        paraSchema.put("additionalProperties", false);

        Map<String, Object> itemSchema = new HashMap<>();
        itemSchema.put("type", "object");
        itemSchema.put("properties", Map.of(
                "ko_name", Map.of("type", "string"),
                "en_name", Map.of("type", "string"),
                "para", paraSchema,
                "reason", Map.of("type", "string")
        ));
        itemSchema.put("required", List.of("ko_name", "en_name", "para", "reason"));
        itemSchema.put("additionalProperties", false);

        // Top-level must be an object, not array
        Map<String, Object> responsesArraySchema = new HashMap<>();
        responsesArraySchema.put("type", "array");
        responsesArraySchema.put("items", itemSchema);
        responsesArraySchema.put("minItems", fileCount);
        responsesArraySchema.put("maxItems", fileCount);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "responses", responsesArraySchema
        ));
        schema.put("required", List.of("responses"));
        schema.put("additionalProperties", false);

        Map<String, Object> format = new HashMap<>();
        format.put("type", "json_schema");
        format.put("name", "FileNameResponseArray");
        format.put("schema", schema);
        format.put("strict", true);

        Map<String, Object> textFormat = new HashMap<>();
        textFormat.put("format", format);

        return textFormat;
    }

    private String extractBatchArrayResponse(String response, int expectedCount) throws Exception {
        try {
            log.info("Extracting batch array response. Expected {} items", expectedCount);
            log.debug("Full response received: {}", response);

            JsonNode root = objectMapper.readTree(response);
            log.debug("Response root keys: {}", root.fieldNames());
            JsonNode output = root.path("output");

            if (!output.isArray() || output.isEmpty()) {
                log.error("Output is not an array or is empty");
                log.error("Output node: {}", output);
                throw new IllegalStateException("OpenAI did not return any output");
            }

            log.info("Output array size: {}", output.size());
            StringBuilder contentBuilder = new StringBuilder();
            for (int idx = 0; idx < output.size(); idx++) {
                JsonNode messageNode = output.get(idx);
                log.debug("Processing output[{}]", idx);
                log.debug("Output[{}] keys: {}", idx, messageNode.fieldNames());

                String messageType = messageNode.path("type").asText();
                log.debug("Output[{}] type: {}", idx, messageType);

                JsonNode contentArray = messageNode.path("content");
                log.debug("Output[{}] has 'content' field: {}", idx, !contentArray.isMissingNode());
                log.debug("Output[{}] 'content' is array: {}", idx, contentArray.isArray());

                if (contentArray.isArray() && contentArray.size() > 0) {
                    log.debug("Output[{}] found content array with {} items", idx, contentArray.size());
                    for (int i = 0; i < contentArray.size(); i++) {
                        JsonNode contentNode = contentArray.get(i);
                        log.debug("Output[{}].content[{}] keys: {}", idx, i, contentNode.fieldNames());

                        String contentType = contentNode.path("type").asText();
                        log.debug("Output[{}].content[{}] type: {}", idx, i, contentType);

                        if ("output_text".equals(contentType)) {
                            String text = contentNode.path("text").asText();
                            log.debug("Found output_text: {}", text.substring(0, Math.min(100, text.length())));
                            contentBuilder.append(text);
                        } else if ("text".equals(contentType)) {
                            String text = contentNode.path("text").asText();
                            log.debug("Found text (alternative): {}", text.substring(0, Math.min(100, text.length())));
                            contentBuilder.append(text);
                        } else {
                            log.debug("Content type '{}' did not match expected types", contentType);
                            // Log all fields of this content node for debugging
                            log.debug("Output[{}].content[{}] full node: {}", idx, i, contentNode);
                        }
                    }
                } else {
                    log.debug("No content array found or it's empty for output[{}]. Type: {}", idx, messageType);
                }
            }

            String jsonString = contentBuilder.toString().trim();
            log.debug("Extracted JSON (length: {})", jsonString.length());
            log.debug("Extracted JSON content (first 500 chars): {}", jsonString.substring(0, Math.min(500, jsonString.length())));

            // Parse the response which is now {"responses": [...]}
            JsonNode responseNode = objectMapper.readTree(jsonString);

            // Check if this is already the responses array directly (not wrapped in an object)
            if (responseNode.isArray()) {
                log.info("Response is already an array with {} items", responseNode.size());
                return jsonString;
            }

            JsonNode responsesArray = responseNode.path("responses");

            if (!responsesArray.isArray()) {
                log.error("Extracted content does not have 'responses' array. Root keys: {}", responseNode.fieldNames());
                log.error("Full response: {}", jsonString);
                throw new IllegalStateException("Response does not have 'responses' array and is not an array");
            }

            log.info("Successfully extracted array with {} items", responsesArray.size());
            // Return the array part as JSON string
            return objectMapper.writeValueAsString(responsesArray);
        } catch (Exception e) {
            log.error("Failed to extract batch array response", e);
            throw new RuntimeException("Failed to extract batch array response", e);
        }
    }

    private List<FileNameResponseDto> parseBatchResponseArray(String jsonArrayString) throws Exception {
        try {
            log.info("Parsing batch response array");
            JsonNode arrayNode = objectMapper.readTree(jsonArrayString);

            if (!arrayNode.isArray()) {
                throw new IllegalStateException("Response is not a JSON array");
            }

            List<FileNameResponseDto> results = new ArrayList<>();
            for (int i = 0; i < arrayNode.size(); i++) {
                try {
                    FileNameResponseDto dto = objectMapper.treeToValue(arrayNode.get(i), FileNameResponseDto.class);
                    if (dto != null) {
                        log.info("Item {} parsed: koName={}, enName={}, bucket={}",
                                i, dto.getKoName(), dto.getEnName(),
                                dto.getPara() != null ? dto.getPara().getBucket() : "null");
                        results.add(dto);
                    }
                } catch (Exception e) {
                    log.error("Failed to parse array item {}: {}", i, arrayNode.get(i).toString(), e);
                }
            }

            log.info("Array parsing complete: {} items parsed out of {}", results.size(), arrayNode.size());
            return results;
        } catch (Exception e) {
            log.error("Failed to parse batch response array", e);
            throw new RuntimeException("Failed to parse batch response array", e);
        }
    }

    public Mono<FolderRestructureResponseDto> requestFolderRestructureSuggestion(String userPrompt, String systemPrompt) {
        Map<String, Object> systemMessage = createSimpleMessage("system", systemPrompt);
        Map<String, Object> userMessage = createSimpleMessage("user", userPrompt);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", List.of(systemMessage, userMessage));
        requestBody.put("text", buildFolderRestructureTextFormat());

        log.info("Requesting folder restructure suggestion from OpenAI");

        return openAiWebClient.post()
                .uri(OPENAI_API_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response ->
                        response.bodyToMono(String.class)
                                .doOnNext(errorBody -> log.error("OpenAI Folder Restructure API Error - {}", errorBody))
                                .then(Mono.error(new RuntimeException("OpenAI API returned error")))
                )
                .bodyToMono(String.class)
                .doOnNext(this::logPrettyResponse)
                .flatMap(response -> Mono.fromCallable(() ->
                        extractAndParseFolderRestructureResponse(response)
                ).subscribeOn(Schedulers.boundedElastic()))
                .onErrorMap(original -> original instanceof RuntimeException ? original : 
                        new RuntimeException("Failed to call OpenAI folder restructure API", original));
    }

    private Map<String, Object> buildFolderRestructureTextFormat() {
        // MergeSuggestion 스키마
        Map<String, Object> mergeSuggestionSchema = new HashMap<>();
        mergeSuggestionSchema.put("type", "object");
        mergeSuggestionSchema.put("properties", Map.of(
                "target_folder", Map.of("type", "string"),
                "source_folders", Map.of("type", "array", "items", Map.of("type", "string")),
                "suggested_name", Map.of("type", "string"),
                "rationale", Map.of("type", "string")
        ));
        mergeSuggestionSchema.put("required", List.of("target_folder", "source_folders", "suggested_name", "rationale"));
        mergeSuggestionSchema.put("additionalProperties", false);

        // 메인 응답 스키마
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "merge_suggestions", Map.of(
                        "type", "array",
                        "items", mergeSuggestionSchema
                ),
                "reason", Map.of("type", "string")
        ));
        schema.put("required", List.of("merge_suggestions", "reason"));
        schema.put("additionalProperties", false);

        Map<String, Object> format = new HashMap<>();
        format.put("type", "json_schema");
        format.put("name", "FolderRestructureResponse");
        format.put("schema", schema);
        format.put("strict", true);

        Map<String, Object> textFormat = new HashMap<>();
        textFormat.put("format", format);

        return textFormat;
    }

    private FolderRestructureResponseDto extractAndParseFolderRestructureResponse(String response) throws Exception {
        try {
            log.info("Extracting folder restructure response");

            JsonNode root = objectMapper.readTree(response);
            JsonNode output = root.path("output");

            if (!output.isArray() || output.isEmpty()) {
                log.error("Output is not an array or is empty");
                throw new IllegalStateException("OpenAI did not return any output");
            }

            StringBuilder contentBuilder = new StringBuilder();
            for (JsonNode messageNode : output) {
                String messageType = messageNode.path("type").asText();
                log.debug("Processing output message with type: {}", messageType);

                JsonNode contentArray = messageNode.path("content");
                if (contentArray.isArray() && contentArray.size() > 0) {
                    for (JsonNode contentNode : contentArray) {
                        String contentType = contentNode.path("type").asText();
                        if ("output_text".equals(contentType) || "text".equals(contentType)) {
                            String text = contentNode.path("text").asText();
                            contentBuilder.append(text);
                        }
                    }
                }
            }

            String jsonString = contentBuilder.toString().trim();
            log.debug("Extracted JSON content: {}", jsonString);

            if (jsonString.isEmpty()) {
                throw new IllegalStateException("No content extracted from response");
            }

            // Parse the JSON response
            FolderRestructureResponseDto result = objectMapper.readValue(jsonString, FolderRestructureResponseDto.class);
            log.info("Successfully parsed folder restructure response with {} suggestions", 
                    result.getMergeSuggestions().size());

            return result;
        } catch (Exception e) {
            log.error("Failed to extract folder restructure response", e);
            throw new RuntimeException("Failed to extract folder restructure response", e);
        }
    }

    private void logPrettyResponse(String response) {
        try {
            JsonNode jsonNode = objectMapper.readTree(response);
            String prettyResponse = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
            log.info("OpenAI Response:\n{}", prettyResponse);
        } catch (Exception e) {
            log.info("OpenAI Response: {}", response);
        }
    }

    private List<FileNameResponseDto> parseBatchResponses(List<String> contents) throws Exception {
        log.info("Parsing {} batch response contents", contents.size());
        List<FileNameResponseDto> results = new ArrayList<>();

        int index = 0;
        for (String content : contents) {
            try {
                log.debug("Parsing response {}: {}", index, content.substring(0, Math.min(100, content.length())));
                FileNameResponseDto dto = objectMapper.readValue(content, FileNameResponseDto.class);
                if (dto != null) {
                    log.info("Response {} parsed successfully: koName={}, enName={}, bucket={}",
                            index, dto.getKoName(), dto.getEnName(),
                            dto.getPara() != null ? dto.getPara().getBucket() : "null");
                    results.add(dto);
                }
            } catch (Exception e) {
                log.error("Failed to parse response content {}: {}", index, content, e);
            }
            index++;
        }

        log.info("Batch parsing complete: {} responses parsed out of {}", results.size(), contents.size());
        return results;
    }

    private Map<String, Object> createSimpleMessage(String role, String text) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", role);
        message.put("content", text);
        return message;
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
                if (contentArray.isArray()) {
                    for (JsonNode contentNode : contentArray) {
                        if ("text".equals(contentNode.path("type").asText())) {
                            contentBuilder.append(contentNode.path("text").asText());
                        }
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

    private List<String> extractBatchOutputContents(String response, int expectedCount) throws Exception {
        try {
            log.info("Extracting batch outputs from response. Expected: {}", expectedCount);
            log.debug("Full API response: {}", response);

            JsonNode root = objectMapper.readTree(response);
            JsonNode output = root.path("output");

            log.info("Output node type: {}, isArray: {}, size: {}",
                    output.getNodeType(), output.isArray(), output.size());

            if (!output.isArray() || output.isEmpty()) {
                log.error("Output is not an array or is empty");
                throw new IllegalStateException("OpenAI did not return any output");
            }

            List<String> contents = new ArrayList<>();
            int messageIndex = 0;
            for (JsonNode messageNode : output) {
                log.debug("Processing message {}", messageIndex);
                JsonNode contentArray = messageNode.path("content");
                if (!contentArray.isArray()) {
                    log.warn("Message {} content is not an array, skipping", messageIndex);
                    messageIndex++;
                    continue;
                }

                StringBuilder contentBuilder = new StringBuilder();
                for (JsonNode contentNode : contentArray) {
                    if ("output_text".equals(contentNode.path("type").asText())) {
                        String text = contentNode.path("text").asText();
                        log.debug("Message {} extracted text length: {}", messageIndex, text.length());
                        contentBuilder.append(text);
                    }
                }

                String content = contentBuilder.toString().trim();
                if (!content.isEmpty()) {
                    log.info("Message {} content added (length: {})", messageIndex, content.length());
                    contents.add(content);
                } else {
                    log.warn("Message {} had no content", messageIndex);
                }
                messageIndex++;
            }

            log.info("Total outputs extracted: {}, expected: {}", contents.size(), expectedCount);
            if (contents.size() != expectedCount) {
                log.warn("Mismatch in output count! Expected {} but got {}", expectedCount, contents.size());
            }

            return contents;
        } catch (Exception e) {
            log.error("Failed to extract batch output contents", e);
            throw new RuntimeException("Failed to extract batch output contents", e);
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

    private void logPrettyBatchResponseContent(String content) {
        try {
            JsonNode jsonNode = objectMapper.readTree(content);
            String pretty = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
            log.info("OpenAI batch response body:\n{}", pretty);
        } catch (Exception e) {
            log.info("OpenAI batch response body (raw): {}", content);
        }
    }
}
