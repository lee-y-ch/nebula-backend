package com.filenori.nebula.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sagemakerruntime.SageMakerRuntimeClient;

@Configuration
public class SageMakerConfig {

    @Value("${aws.region:ap-northeast-2}")
    private String awsRegion;

    @Bean
    public SageMakerRuntimeClient sageMakerRuntimeClient() {
        return SageMakerRuntimeClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}

