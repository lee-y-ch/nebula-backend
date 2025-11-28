package com.filenori.nebula.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.sagemakerruntime.SageMakerRuntimeClient;
import software.amazon.awssdk.services.sagemakerruntime.model.InvokeEndpointRequest;
import software.amazon.awssdk.services.sagemakerruntime.model.InvokeEndpointResponse;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SageMakerEmbeddingService {

    private final SageMakerRuntimeClient runtimeClient;
    private final ObjectMapper objectMapper;

    @Value("${sagemaker.endpointName:}")
    private String endpointName;

    /**
     * 텍스트를 임베딩 벡터로 변환합니다.
     *
     * @param text 질의 또는 문서 텍스트
     * @return 임베딩 벡터(Optional)
     */
    public Optional<List<Double>> embedText(String text) {
        if (!StringUtils.hasText(endpointName)) {
            log.debug("SageMaker endpoint name is not configured. Skipping embedding request.");
            return Optional.empty();
        }

        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }

        try {
            Map<String, String> payload = Map.of("text", text);
            byte[] body = objectMapper.writeValueAsBytes(payload);

            InvokeEndpointRequest request = InvokeEndpointRequest.builder()
                    .endpointName(endpointName)
                    .contentType("application/json")
                    .body(SdkBytes.fromByteArray(body))
                    .build();

            InvokeEndpointResponse response = runtimeClient.invokeEndpoint(request);
            byte[] responseBytes = response.body().asByteArray();

            EmbeddingResponse embeddingResponse = objectMapper.readValue(responseBytes, EmbeddingResponse.class);
            if (embeddingResponse.embedding() == null || embeddingResponse.embedding().isEmpty()) {
                log.warn("SageMaker embedding response did not contain embedding field");
                return Optional.empty();
            }
            return Optional.of(embeddingResponse.embedding());
        } catch (Exception e) {
            log.error("Failed to retrieve embedding from SageMaker endpoint {}", endpointName, e);
            return Optional.empty();
        }
    }

    private record EmbeddingResponse(@JsonProperty("embedding") List<Double> embedding) {
    }

    /**
     * Helper for debug/testing without SageMaker.
     */
    public List<Double> mockEmbedding(String text) {
        if (!StringUtils.hasText(text)) {
            return Collections.emptyList();
        }
        return text.chars()
                .limit(10)
                .mapToDouble(ch -> (double) (ch % 10) / 10.0)
                .boxed()
                .toList();
    }
}

