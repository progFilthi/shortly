package com.shortly.transcoderservice;

import com.shortly.transcoderservice.config.TranscoderProperties;
import com.shortly.transcoderservice.storage.TranscoderStorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * FFmpeg worker. No business endpoints - the only HTTP surface is actuator, for liveness and
 * readiness. All work arrives over AMQP.
 */
@SpringBootApplication
@EnableConfigurationProperties({TranscoderProperties.class, TranscoderStorageProperties.class})
public class TranscoderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TranscoderServiceApplication.class, args);
    }
}
