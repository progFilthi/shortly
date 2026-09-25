package com.shortly.videoservice.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class RabbitMQConfig {

    public static final String VIDEO_EXCHANGE_NAME = "video.exchange";
    public static final String VIDEO_UPLOADED_QUEUE_NAME = "video.uploaded.queue";
    public static final String VIDEO_UPLOADED_ROUTING_KEY = "video.uploaded";

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin rabbitAdmin = new RabbitAdmin(connectionFactory);
        rabbitAdmin.setAutoStartup(true);
        return rabbitAdmin;
    }

    @Bean
    public DirectExchange videoExchange() {
        return new DirectExchange(VIDEO_EXCHANGE_NAME, true, false);
    }

    @Bean
    public Queue videoUploadedQueue() {
        return new Queue(VIDEO_UPLOADED_QUEUE_NAME, true);
    }

    @Bean
    public Binding videoUploadedBinding(Queue videoUploadedQueue, DirectExchange videoExchange) {
        return BindingBuilder.bind(videoUploadedQueue)
                .to(videoExchange)
                .with(VIDEO_UPLOADED_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new JacksonJsonMessageConverter((JsonMapper) objectMapper);
    }
}