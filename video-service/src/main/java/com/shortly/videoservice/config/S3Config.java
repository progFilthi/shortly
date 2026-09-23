package com.shortly.videoservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class S3Config {

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder().build();
    }
}
