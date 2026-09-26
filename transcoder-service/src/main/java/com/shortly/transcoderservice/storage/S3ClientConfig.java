package com.shortly.transcoderservice.storage;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * S3 clients.
 * <p>
 * Two clients, deliberately: a synchronous one for control-plane calls (HEAD, delete) where
 * simplicity matters, and an asynchronous one for the hot path. Uploading a 6-rung ladder is
 * ~187 PUTs per video, and doing those sequentially over a blocking client would dominate
 * the job's wall time for no reason.
 */
@Configuration
public class S3ClientConfig {

    /**
     * Optional S3-compatible endpoint, for local runs against MinIO. Unset against real AWS,
     * where the regional endpoint is derived from {@code aws.region}.
     * <p>
     * Injected as an Optional so the same image works in both environments with no profile
     * switch: absent means real AWS, present means path-style addressing against a local store.
     */
    @Bean
    public S3Client s3Client(@Value("${aws.region}") String region,
                             @Value("${aws.credentials.access-key}") String accessKey,
                             @Value("${aws.credentials.secret-key}") String secretKey,
                             @Value("${aws.endpoint-url:}") String endpointUrl) {

        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClient(ApacheHttpClient.builder()
                        .maxConnections(64)
                        .build());

        applyEndpointOverride(builder, endpointUrl);
        return builder.build();
    }

    /**
     * Async client for bulk segment upload.
     * <p>
     * The connection pool is sized for segment concurrency rather than for the whole service: a
     * 24-way upload fan-out over a 16-connection pool would serialise into waves, and a pool far
     * larger than the concurrency just parks idle sockets and threads.
     */
    @Bean
    public S3AsyncClient s3AsyncClient(@Value("${aws.region}") String region,
                                       @Value("${aws.credentials.access-key}") String accessKey,
                                       @Value("${aws.credentials.secret-key}") String secretKey,
                                       @Value("${aws.endpoint-url:}") String endpointUrl,
                                       @Value("${transcoder.storage.max-connections:32}") int maxConnections) {
        SdkAsyncHttpClient httpClient = NettyNioAsyncHttpClient.builder()
                .maxConcurrency(maxConnections)
                .build();

        S3AsyncClientBuilder builder = S3AsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClient(httpClient);

        applyEndpointOverride(builder, endpointUrl);
        return builder.build();
    }

    private void applyEndpointOverride(S3ClientBuilder builder, String endpointUrl) {
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl))
                    .serviceConfiguration(S3Configuration.builder()
                            // MinIO and other S3-compatible stores address buckets in the path.
                            // Virtual-host style would require wildcard DNS that does not exist locally.
                            .pathStyleAccessEnabled(true)
                            .build());
        }
    }

    private void applyEndpointOverride(S3AsyncClientBuilder builder, String endpointUrl) {
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build());
        }
    }
}
