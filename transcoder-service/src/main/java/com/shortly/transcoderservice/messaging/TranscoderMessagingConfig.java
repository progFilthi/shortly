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

/**
 * AMQP topology and consumer tuning.
 *
 * <p>Only the exchange this service consumes from plus the dead-letter chain. video-service
 * declares the same exchange with the same arguments, so the topology converges whichever
 * service starts first.
 */
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

    /**
     * The work queue.
     *
     * <p>Deliberately no per-message TTL: dead-lettering on age measures from <em>enqueue</em>,
     * and a 6-rung transcode takes minutes, so an unlucky job would expire mid-flight and
     * vanish. Messages reach the DLQ only by being rejected.
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
    public MessageConverter jsonMessageConverter(JsonMapper jsonMapper) {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter(jsonMapper);

        /*
         * Hardening for the __TypeId__ header. JacksonJsonMessageConverter stamps the
         * producer's class name into the headers and tries to resolve it on the way back.
         * The records live in a shared contract module so it resolves today, but a rename would
         * otherwise make this consumer throw on a message it could read from its JSON body.
         *
         * Two defences: an explicit id->class map, so the header is interpreted rather than
         * loaded reflectively; and INFERRED precedence, so an unmappable header falls back to
         * the listener's own parameter type. Every listener takes a contract type, so that
         * fallback is safe.
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
     * Listener container factory for the ffmpeg worker. The three settings that matter:
     * <ul>
     *   <li><b>prefetch = 1</b> - Spring's default is 250. With CPU-bound work taking
     *       minutes per message, a high prefetch means one worker hoards hundreds of
     *       unacked jobs into memory and a crash silently loses every one of them.
     *       One in flight per consumer thread is the only safe setting here.</li>
     *   <li><b>concurrency</b> - bounded, because each ffmpeg process will otherwise try to
     *       use every core. More listeners than cores just makes every job slower.</li>
     *   <li><b>requeueRejected = false</b> - the default (true) turns any unhandled
     *       exception into an infinite hot redelivery loop. Paired with the retry
     *       interceptor below, a poison message lands in the DLQ instead.</li>
     * </ul>
     */
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

        // AUTO ack: a container that dies mid-job gets it redelivered, which is what we want
        // for multi-minute work. A job is never acked unfinished.
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

    /**
     * RabbitTemplate for the ready/failed events this service emits.
     *
     * <p>{@code mandatory} plus a returns callback turns a silently dropped publish into a
     * visible log. Without it {@code convertAndSend} succeeds even when the broker had no route,
     * and the video sits in PROCESSING forever with nobody noticing.
     *
     * <p>Publisher confirms come from {@code spring.rabbitmq.publisher-confirm-type} and arrive
     * as a future on the CorrelationData rather than a direct callback, hence the thenAccept.
     */
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
