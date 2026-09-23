package com.shortly.videoservice.impls;

import com.shortly.videoservice.services.S3StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class S3StorageServiceImpl implements S3StorageService {

    private String bucketName;

    private String cdnDomain;

    private final S3Presigner s3Presigner;


    @Override
    public String generatePresignedUploadUrl(String s3Key, String contentType, Duration expiration) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .contentType(contentType)
                .build();


        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(expiration)
                .putObjectRequest(putObjectRequest)
                .build();

        return s3Presigner.presignPutObject(presignRequest).url().toString();
    }

    @Override
    public String getPublicVideoUrl(String s3Key) {
        return cdnDomain + "/" + s3Key;
    }
}
