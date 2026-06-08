package com.notificationplatform.application.queue;

import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.NotificationPriority;
import java.time.Duration;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RabbitMqQueuePublisher implements QueuePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String exchangeName;

    public RabbitMqQueuePublisher(
        RabbitTemplate rabbitTemplate,
        @Value("${notification.rabbitmq.exchange:" + RabbitMqTopology.EXCHANGE + "}") String exchangeName
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchangeName = exchangeName;
    }

    @Override
    public void publish(NotificationPriority priority, DeliveryMessage message) {
        rabbitTemplate.convertAndSend(
            exchangeName,
            routingKeyFor(priority, message.channel()),
            message,
            persistentMessage()
        );
    }

    @Override
    public void publishRetry(DeliveryMessage message, Duration delay) {
        rabbitTemplate.convertAndSend(
            exchangeName,
            RabbitMqTopology.RETRY_EMAIL_ROUTING_KEY,
            message,
            persistentMessage(delay)
        );
    }

    @Override
    public void publishDeadLetter(DeliveryMessage message) {
        rabbitTemplate.convertAndSend(
            exchangeName,
            RabbitMqTopology.DLQ_EMAIL_ROUTING_KEY,
            message,
            persistentMessage()
        );
    }

    private static String routingKeyFor(NotificationPriority priority, Channel channel) {
        if (channel != Channel.EMAIL) {
            throw new IllegalArgumentException("Unsupported RabbitMQ delivery channel: " + channel);
        }

        NotificationPriority effectivePriority = priority == null ? NotificationPriority.NORMAL : priority;
        return switch (effectivePriority) {
            case HIGH -> RabbitMqTopology.HIGH_EMAIL_ROUTING_KEY;
            case NORMAL -> RabbitMqTopology.NORMAL_EMAIL_ROUTING_KEY;
            case LOW -> RabbitMqTopology.LOW_EMAIL_ROUTING_KEY;
        };
    }

    private static MessagePostProcessor persistentMessage() {
        return persistentMessage(null);
    }

    private static MessagePostProcessor persistentMessage(Duration delay) {
        return message -> {
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            if (delay != null && !delay.isNegative() && !delay.isZero()) {
                message.getMessageProperties().setExpiration(String.valueOf(delay.toMillis()));
            }
            return message;
        };
    }
}
