package com.shortly.transcoderservice.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;

/** S3 clients. Two clients, deliberately: a synchronous one for control-plane calls (HEAD, delete)
 * where simplicity matters, and an asynchronous one for the hot path. */
@Configuration
public class S3ClientConfig {

    @Bean
    public S3Client s3Client(@Value("${aws.region}") String region,
                             @Value("${aws.credentials.access-key}") String accessKey,
                             @Value("${aws.credentials.secret-key}") String secretKey) {

        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClient(ApacheHttpClient.builder()
                        .maxConnections(64)
                        .build())
                .build();
    }

    /** Async client for bulk segment upload. */
    @Bean
    public S3AsyncClient s3AsyncClient(@Value("${aws.region}") String region,
                                       @Value("${aws.credentials.access-key}") String accessKey,
                                       @Value("${aws.credentials.secret-key}") String secretKey,
                                       @Value("${transcoder.storage.max-connections:32}") int maxConnections) {
        SdkAsyncHttpClient httpClient = NettyNioAsyncHttpClient.builder()
                .maxConcurrency(maxConnections)
                .build();

        return S3AsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClient(httpClient)
                .build();
    }
}
