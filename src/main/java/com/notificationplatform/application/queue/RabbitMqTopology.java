package com.notificationplatform.application.queue;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@EnableRabbit
@Configuration
public class RabbitMqTopology {

    public static final String EXCHANGE = "notifications.exchange";

    public static final String HIGH_EMAIL_QUEUE = "notifications.high.email";
    public static final String NORMAL_EMAIL_QUEUE = "notifications.normal.email";
    public static final String LOW_EMAIL_QUEUE = "notifications.low.email";
    public static final String RETRY_EMAIL_QUEUE = "notifications.retry.email";
    public static final String DLQ_EMAIL_QUEUE = "notifications.dlq.email";

    public static final String HIGH_EMAIL_ROUTING_KEY = "notification.high.email";
    public static final String NORMAL_EMAIL_ROUTING_KEY = "notification.normal.email";
    public static final String LOW_EMAIL_ROUTING_KEY = "notification.low.email";
    public static final String RETRY_EMAIL_ROUTING_KEY = "notification.retry.email";
    public static final String DLQ_EMAIL_ROUTING_KEY = "notification.dlq.email";

    @Bean
    DirectExchange notificationsExchange(@Value("${notification.rabbitmq.exchange:" + EXCHANGE + "}") String exchangeName) {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    Queue highEmailQueue() {
        return QueueBuilder.durable(HIGH_EMAIL_QUEUE).build();
    }

    @Bean
    Queue normalEmailQueue() {
        return QueueBuilder.durable(NORMAL_EMAIL_QUEUE).build();
    }

    @Bean
    Queue lowEmailQueue() {
        return QueueBuilder.durable(LOW_EMAIL_QUEUE).build();
    }

    @Bean
    Queue retryEmailQueue(@Value("${notification.rabbitmq.exchange:" + EXCHANGE + "}") String exchangeName) {
        return QueueBuilder.durable(RETRY_EMAIL_QUEUE)
            .deadLetterExchange(exchangeName)
            .deadLetterRoutingKey(NORMAL_EMAIL_ROUTING_KEY)
            .build();
    }

    @Bean
    Queue dlqEmailQueue() {
        return QueueBuilder.durable(DLQ_EMAIL_QUEUE).build();
    }

    @Bean
    Binding highEmailBinding(@Qualifier("highEmailQueue") Queue highEmailQueue, DirectExchange notificationsExchange) {
        return BindingBuilder.bind(highEmailQueue).to(notificationsExchange).with(HIGH_EMAIL_ROUTING_KEY);
    }

    @Bean
    Binding normalEmailBinding(@Qualifier("normalEmailQueue") Queue normalEmailQueue, DirectExchange notificationsExchange) {
        return BindingBuilder.bind(normalEmailQueue).to(notificationsExchange).with(NORMAL_EMAIL_ROUTING_KEY);
    }

    @Bean
    Binding lowEmailBinding(@Qualifier("lowEmailQueue") Queue lowEmailQueue, DirectExchange notificationsExchange) {
        return BindingBuilder.bind(lowEmailQueue).to(notificationsExchange).with(LOW_EMAIL_ROUTING_KEY);
    }

    @Bean
    Binding retryEmailBinding(@Qualifier("retryEmailQueue") Queue retryEmailQueue, DirectExchange notificationsExchange) {
        return BindingBuilder.bind(retryEmailQueue).to(notificationsExchange).with(RETRY_EMAIL_ROUTING_KEY);
    }

    @Bean
    Binding dlqEmailBinding(@Qualifier("dlqEmailQueue") Queue dlqEmailQueue, DirectExchange notificationsExchange) {
        return BindingBuilder.bind(dlqEmailQueue).to(notificationsExchange).with(DLQ_EMAIL_ROUTING_KEY);
    }

    @Bean
    MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter rabbitMessageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(rabbitMessageConverter);
        rabbitTemplate.setMandatory(true);
        return rabbitTemplate;
    }

    @Bean
    SimpleRabbitListenerContainerFactory manualRabbitListenerContainerFactory(
        SimpleRabbitListenerContainerFactoryConfigurer configurer,
        ConnectionFactory connectionFactory,
        MessageConverter rabbitMessageConverter
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(rabbitMessageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }
}
