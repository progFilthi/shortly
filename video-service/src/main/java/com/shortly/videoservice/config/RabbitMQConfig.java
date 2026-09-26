package com.shortly.videoservice.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.shortly.contracts.events.VideoFailedEvent;
import com.shortly.contracts.events.VideoReadyEvent;
import com.shortly.contracts.events.VideoUploadedEvent;
import com.shortly.contracts.messaging.MessagingTopology;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * AMQP topology for video-service.
 * <p>
 * Declares the shared exchange, the work queue the transcoder consumes, and the two queues
 * this service consumes outcomes from. Constants come from {@code common-contracts} so there
 * is exactly one definition of each name.
 */
@Configuration
public class RabbitMQConfig {

    @Bean
    public TopicExchange videoExchange() {
        return new TopicExchange(MessagingTopology.VIDEO_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange videoDeadLetterExchange() {
        return new DirectExchange(MessagingTopology.DEAD_LETTER_EXCHANGE, true, false);
    }

    /**
     * The work queue, declared here as well as in the transcoder.
     * <p>
     * Both ends declare it with identical arguments so the topology converges whichever service
     * starts first. If only the producer declared it, a transcoder-only deployment would fail
     * with a missing-queue error, and if only the consumer declared it, work published before
     * the transcoder started would be silently dropped.
     */
    @Bean
    public Queue videoUploadedQueue() {
        return QueueBuilder.durable(MessagingTopology.VIDEO_UPLOADED_QUEUE)
                .deadLetterExchange(MessagingTopology.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(MessagingTopology.VIDEO_UPLOADED_DLQ)
                .build();
    }

    @Bean
    public Binding videoUploadedBinding(Queue videoUploadedQueue, TopicExchange videoExchange) {
        return BindingBuilder.bind(videoUploadedQueue)
                .to(videoExchange)
                .with(MessagingTopology.VIDEO_UPLOADED_ROUTING_KEY);
    }

    @Bean
    public Queue videoReadyQueue() {
        return QueueBuilder.durable(MessagingTopology.VIDEO_READY_QUEUE)
                .deadLetterExchange(MessagingTopology.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(MessagingTopology.VIDEO_READY_DLQ)
                .build();
    }

    @Bean
    public Binding videoReadyBinding(Queue videoReadyQueue, TopicExchange videoExchange) {
        return BindingBuilder.bind(videoReadyQueue)
                .to(videoExchange)
                .with(MessagingTopology.VIDEO_READY_ROUTING_KEY);
    }

    @Bean
    public Queue videoFailedQueue() {
        return QueueBuilder.durable(MessagingTopology.VIDEO_FAILED_QUEUE)
                .deadLetterExchange(MessagingTopology.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(MessagingTopology.VIDEO_FAILED_DLQ)
                .build();
    }

    @Bean
    public Binding videoFailedBinding(Queue videoFailedQueue, TopicExchange videoExchange) {
        return BindingBuilder.bind(videoFailedQueue)
                .to(videoExchange)
                .with(MessagingTopology.VIDEO_FAILED_ROUTING_KEY);
    }

    @Bean
    public Queue videoReadyDeadLetterQueue() {
        return QueueBuilder.durable(MessagingTopology.VIDEO_READY_DLQ).build();
    }

    @Bean
    public Binding videoReadyDeadLetterBinding(Queue videoReadyDeadLetterQueue,
                                                DirectExchange videoDeadLetterExchange) {
        return BindingBuilder.bind(videoReadyDeadLetterQueue)
                .to(videoDeadLetterExchange)
                .with(MessagingTopology.VIDEO_READY_DLQ);
    }

    @Bean
    public Queue videoFailedDeadLetterQueue() {
        return QueueBuilder.durable(MessagingTopology.VIDEO_FAILED_DLQ).build();
    }

    @Bean
    public Binding videoFailedDeadLetterBinding(Queue videoFailedDeadLetterQueue,
                                                DirectExchange videoDeadLetterExchange) {
        return BindingBuilder.bind(videoFailedDeadLetterQueue)
                .to(videoDeadLetterExchange)
                .with(MessagingTopology.VIDEO_FAILED_DLQ);
    }

    @Bean
    public MessageConverter jsonMessageConverter(JsonMapper jsonMapper) {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter(jsonMapper);

        /*
         * Both services interpret the __TypeId__ header through an explicit map instead of
         * resolving it reflectively, and fall back to the listener's declared parameter type
         * when a header cannot be mapped. A producer that renames a contract record therefore
         * degrades to "deserialize from the JSON body" rather than dead-lettering every message.
         */
        DefaultJacksonJavaTypeMapper typeMapper = new DefaultJacksonJavaTypeMapper();
        typeMapper.setIdClassMapping(Map.of(
                VideoUploadedEvent.class.getName(), VideoUploadedEvent.class,
                VideoReadyEvent.class.getName(), VideoReadyEvent.class,
                VideoFailedEvent.class.getName(), VideoFailedEvent.class));
        typeMapper.addTrustedPackages("com.shortly.contracts");
        typeMapper.setTypePrecedence(JacksonJavaTypeMapper.TypePrecedence.INFERRED);
        converter.setJavaTypeMapper(typeMapper);

        return converter;
    }

    /**
     * Listener tuning for the two outcome queues.
     * <p>
     * These handlers are a single indexed UPDATE, not minutes of CPU work, so the prefetch here
     * is deliberately higher than the transcoder's. A low prefetch here would throttle status
     * updates for no benefit - the opposite trade-off from the ffmpeg worker, where prefetch 1
     * is a correctness requirement.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory videoListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setConcurrentConsumers(2);
        factory.setMaxConcurrentConsumers(6);
        factory.setPrefetchCount(50);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(
                RetryInterceptorBuilder.stateless()
                        .maxRetries(3)
                        .backOffOptions(2_000, 2.0, 30_000)
                        .recoverer(new RejectAndDontRequeueRecoverer())
                        .build());
        return factory;
    }
}
