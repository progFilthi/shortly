package com.shortly.videoservice.impls;

import com.shortly.videoservice.config.VideoStorageProperties;
import com.shortly.videoservice.dto.UploadTargetMetadata;
import com.shortly.videoservice.services.S3StorageService;
import com.shortly.videoservice.services.UploadVerificationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3StorageServiceImpl implements S3StorageService {

    private final S3Presigner s3Presigner;
    private final S3Client s3Client;
    private final VideoStorageProperties properties;

    @Override
    public String generatePresignedUploadUrl(String s3Key, String contentType, Duration expiration) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(properties.bucketName())
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
    public UploadTargetMetadata verifyUploadedObject(String s3Key) {
        HeadObjectResponse head;
        try {
            head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucketName())
                    .key(s3Key)
                    .build());
        } catch (RuntimeException e) {
            if (isNotFound(e)) {
                throw new UploadVerificationException(UploadVerificationException.Kind.MISSING,
                        "No object was uploaded at " + s3Key, 0, properties.maxUploadBytesValue());
            }
            throw e;
        }

        long size = head.contentLength() == null ? 0L : head.contentLength();

        if (size == 0L) {
            throw new UploadVerificationException(UploadVerificationException.Kind.EMPTY,
                    "Uploaded object is empty", 0, properties.maxUploadBytesValue());
        }

        if (size > properties.maxUploadBytesValue()) {
            throw new UploadVerificationException(UploadVerificationException.Kind.TOO_LARGE,
                    "Uploaded object is " + size + " bytes, the limit is "
                            + properties.maxUploadBytesValue(),
                    size, properties.maxUploadBytesValue());
        }

        return new UploadTargetMetadata(
                s3Key,
                size,
                head.contentType(),
                head.eTag(),
                head.lastModified());
    }

    @Override
    public void deleteObject(String s3Key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucketName())
                    .key(s3Key)
                    .build());
            log.info("Deleted rejected upload at {}", s3Key);
        } catch (RuntimeException e) {
            // Non-fatal: an orphaned oversized object costs storage but breaks nothing, and a
            // lifecycle rule on the raw/ prefix is the durable backstop.
            log.warn("Could not delete rejected upload at {}; a lifecycle rule will collect it", s3Key, e);
        }
    }

    @Override
    public String getPublicVideoUrl(String s3Key) {
        String base = properties.cdnDomain().endsWith("/")
                ? properties.cdnDomain().substring(0, properties.cdnDomain().length() - 1)
                : properties.cdnDomain();
        return base + "/" + s3Key;
    }

    private boolean isNotFound(RuntimeException e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof NoSuchKeyException) {
                return true;
            }
            if (current instanceof S3Exception s3 && s3.statusCode() == 404) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }
}
