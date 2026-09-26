package com.shortly.transcoderservice.messaging;

import com.shortly.contracts.events.VideoFailedEvent;
import com.shortly.contracts.events.VideoReadyEvent;
import com.shortly.contracts.events.VideoUploadedEvent;
import com.shortly.contracts.messaging.MessagingTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

/** AMQP topology and consumer tuning. Only the exchange this service consumes from plus the dead-
 * converges whichever service starts first. */
@Configuration
public class TranscoderMessagingConfig {

    private static final Logger log = LoggerFactory.getLogger(TranscoderMessagingConfig.class);

    @Bean
    public TopicExchange videoExchange() {
        return new TopicExchange(MessagingTopology.VIDEO_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange videoDeadLetterExchange() {
        return new DirectExchange(MessagingTopology.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue videoUploadedDeadLetterQueue() {
        return QueueBuilder.durable(MessagingTopology.VIDEO_UPLOADED_DLQ).build();
    }

    @Bean
    public Binding videoUploadedDeadLetterBinding(Queue videoUploadedDeadLetterQueue,
                                                  DirectExchange videoDeadLetterExchange) {
        return BindingBuilder.bind(videoUploadedDeadLetterQueue)
                .to(videoDeadLetterExchange)
                .with(MessagingTopology.VIDEO_UPLOADED_DLQ);
    }

    /** The work queue. Deliberately no per-message TTL: dead-lettering on age measures from enqueue,
     * and a 6-rung transcode takes minutes, so an unlucky job would expire mid-flight and vanish. */
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
    public MessageConverter jsonMessageConverter(JsonMapper jsonMapper) {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter(jsonMapper);

        /** Hardening for the __TypeId__ header. */
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

    /** Listener container factory for the ffmpeg worker. */
    @Bean
    public SimpleRabbitListenerContainerFactory transcoderListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter,
            @Value("${transcoder.consumer.concurrency:2}") int concurrency,
            @Value("${transcoder.consumer.max-concurrency:2}") int maxConcurrency,
            @Value("${transcoder.consumer.prefetch:1}") int prefetch,
            @Value("${transcoder.consumer.retry-attempts:3}") long retryAttempts,
            @Value("${transcoder.consumer.retry-initial-interval:PT15S}") Duration retryInitialInterval,
            @Value("${transcoder.consumer.retry-multiplier:2.0}") double retryMultiplier,
            @Value("${transcoder.consumer.retry-max-interval:PT2M}") Duration retryMaxInterval) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);

        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(Math.max(concurrency, maxConcurrency));
        factory.setPrefetchCount(prefetch);

        // AUTO ack: a container that dies mid-job gets it redelivered, which is what we want for multi-
        // minute work. A job is never acked unfinished.
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);

        factory.setAdviceChain(
                RetryInterceptorBuilder.stateless()
                        .maxRetries((int) retryAttempts)
                        .backOffOptions(
                                retryInitialInterval.toMillis(),
                                retryMultiplier,
                                retryMaxInterval.toMillis())
                        .recoverer(new RejectAndDontRequeueRecoverer())
                        .build()
        );

        return factory;
    }

    /** RabbitTemplate for the ready/failed events this service emits. {@code mandatory} plus a returns
     * callback turns a silently dropped publish into a visible log. */
    @Bean
    public RabbitTemplate transcoderRabbitTemplate(ConnectionFactory connectionFactory,
                                                   MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        template.setMandatory(true);

        template.setReturnsCallback((ReturnedMessage returned) -> log.error(
                "Published message was unroutable and was NOT delivered: exchange={} routingKey={} "
                        + "replyCode={} replyText={}",
                returned.getExchange(), returned.getRoutingKey(),
                returned.getReplyCode(), returned.getReplyText()));

        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("Broker NACKed a published message: correlationId={} cause={}",
                        correlationData == null ? "unknown" : correlationData.getId(), cause);
            }
        });

        return template;
    }
}
