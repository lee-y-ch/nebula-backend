package com.filenori.nebula.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.sagemakerruntime.SageMakerRuntimeClient;
import software.amazon.awssdk.services.sagemakerruntime.model.InvokeEndpointRequest;
import software.amazon.awssdk.services.sagemakerruntime.model.InvokeEndpointResponse;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class SageMakerEmbeddingServiceTest {

    @Test
    void embedText_shouldAverageTokenVectorsFromResponse() throws Exception {
        SageMakerRuntimeClient runtimeClient = Mockito.mock(SageMakerRuntimeClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        SageMakerEmbeddingService service = new SageMakerEmbeddingService(runtimeClient, objectMapper);
        ReflectionTestUtils.setField(service, "endpointName", "clip-vit-large-p14-embedding");

        List<List<Double>> tokenVectors = List.of(
                List.of(1.0d, 2.0d, 3.0d),
                List.of(4.0d, 5.0d, 6.0d)
        );
        List<List<List<Double>>> levelThree = List.of(tokenVectors);
        List<List<List<List<Double>>>> responsePayload = List.of(levelThree);
        byte[] responseBytes = objectMapper.writeValueAsBytes(responsePayload);

        InvokeEndpointResponse response = InvokeEndpointResponse.builder()
                .body(SdkBytes.fromByteArray(responseBytes))
                .build();
        when(runtimeClient.invokeEndpoint(any(InvokeEndpointRequest.class))).thenReturn(response);

        Optional<List<Double>> result = service.embedText("키워드");

        assertThat(result).isPresent();
        assertThat(result.get()).containsExactly(2.5d, 3.5d, 4.5d);
    }
}

