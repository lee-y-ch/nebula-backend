package com.filenori.nebula.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sagemakerruntime.SageMakerRuntimeClient;
import software.amazon.awssdk.services.sagemakerruntime.SageMakerRuntimeClientBuilder;

@Configuration
public class SageMakerConfig {

    @Value("${aws.region:ap-southeast-2}")
    private String awsRegion;

    @Value("${sagemaker.accessKey:}")
    private String accessKey;

    @Value("${sagemaker.secretKey:}")
    private String secretKey;

    @Bean
    public SageMakerRuntimeClient sageMakerRuntimeClient() {
        SageMakerRuntimeClientBuilder builder = SageMakerRuntimeClient.builder()
                .region(Region.of(awsRegion));

        if (StringUtils.hasText(accessKey) && StringUtils.hasText(secretKey)) {
            builder = builder.credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
            );
        }

        return builder.build();
    }
}

