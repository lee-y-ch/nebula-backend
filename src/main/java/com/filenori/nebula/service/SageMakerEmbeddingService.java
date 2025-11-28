package com.filenori.nebula.service;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.ArrayList;
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
            Map<String, Object> payload = Map.of("inputs", Collections.singletonList(text));
            byte[] body = objectMapper.writeValueAsBytes(payload);

            InvokeEndpointRequest request = InvokeEndpointRequest.builder()
                    .endpointName(endpointName)
                    .contentType("application/json")
                    .body(SdkBytes.fromByteArray(body))
                    .build();

            InvokeEndpointResponse response = runtimeClient.invokeEndpoint(request);
            byte[] responseBytes = response.body().asByteArray();

            return extractEmbedding(responseBytes);
        } catch (Exception e) {
            log.error("Failed to retrieve embedding from SageMaker endpoint {}", endpointName, e);
            return Optional.empty();
        }
    }

    private Optional<List<Double>> extractEmbedding(byte[] responseBytes) {
        try {
            JsonNode root = objectMapper.readTree(responseBytes);
            JsonNode tokenVectorsNode = findFirstArrayOfVectors(root);
            if (tokenVectorsNode == null) {
                log.warn("Unable to locate token vectors from SageMaker response");
                return Optional.empty();
            }

            List<List<Double>> tokenVectors = toVectorList(tokenVectorsNode);
            if (tokenVectors.isEmpty()) {
                log.warn("Token vectors were empty in SageMaker response");
                return Optional.empty();
            }

            List<Double> averaged = averageTokenVectors(tokenVectors);
            if (averaged.isEmpty()) {
                log.warn("Averaged embedding is empty after processing SageMaker response");
                return Optional.empty();
            }
            return Optional.of(averaged);
        } catch (Exception e) {
            log.error("Failed to parse SageMaker embedding response", e);
            return Optional.empty();
        }
    }

    private JsonNode findFirstArrayOfVectors(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        if (isArrayOfVectors(node)) {
            return node;
        }
        for (JsonNode child : node) {
            JsonNode candidate = findFirstArrayOfVectors(child);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isArrayOfVectors(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return false;
        }
        JsonNode first = node.get(0);
        return first.isArray()
                && first.size() > 0
                && first.get(0).isNumber();
    }

    private List<List<Double>> toVectorList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }

        List<List<Double>> vectors = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isArray()) {
                continue;
            }
            List<Double> values = new ArrayList<>(element.size());
            for (JsonNode valueNode : element) {
                if (valueNode.isNumber()) {
                    values.add(valueNode.asDouble());
                }
            }
            if (!values.isEmpty()) {
                vectors.add(values);
            }
        }
        return vectors;
    }

    private List<Double> averageTokenVectors(List<List<Double>> tokenVectors) {
        if (tokenVectors.isEmpty()) {
            return List.of();
        }

        int dimension = tokenVectors.get(0).size();
        double[] sums = new double[dimension];
        int validTokens = 0;

        for (List<Double> token : tokenVectors) {
            if (token.size() != dimension) {
                log.debug("Skipping token vector due to dimension mismatch. expected={}, actual={}", dimension, token.size());
                continue;
            }
            for (int i = 0; i < dimension; i++) {
                sums[i] += token.get(i);
            }
            validTokens++;
        }

        if (validTokens == 0) {
            return List.of();
        }

        List<Double> averaged = new ArrayList<>(dimension);
        for (double sum : sums) {
            averaged.add(sum / validTokens);
        }
        return averaged;
    }
}

