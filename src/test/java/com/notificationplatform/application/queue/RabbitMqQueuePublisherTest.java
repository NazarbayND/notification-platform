package com.notificationplatform.application.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.NotificationPriority;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@ExtendWith(MockitoExtension.class)
class RabbitMqQueuePublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Test
    void publishRoutesHighEmailMessageAsPersistent() throws Exception {
        RabbitMqQueuePublisher publisher = new RabbitMqQueuePublisher(rabbitTemplate, RabbitMqTopology.EXCHANGE);
        DeliveryMessage message = message(NotificationPriority.HIGH);

        publisher.publish(NotificationPriority.HIGH, message);

        ArgumentCaptor<MessagePostProcessor> postProcessorCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(
            eq(RabbitMqTopology.EXCHANGE),
            eq(RabbitMqTopology.HIGH_EMAIL_ROUTING_KEY),
            eq(message),
            postProcessorCaptor.capture()
        );

        Message processedMessage = postProcessorCaptor.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertThat(processedMessage.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(processedMessage.getMessageProperties().getExpiration()).isNull();
    }

    @Test
    void publishRetryRoutesToRetryQueueWithDelay() throws Exception {
        RabbitMqQueuePublisher publisher = new RabbitMqQueuePublisher(rabbitTemplate, RabbitMqTopology.EXCHANGE);
        DeliveryMessage message = message(NotificationPriority.NORMAL);

        publisher.publishRetry(message, Duration.ofMinutes(2));

        ArgumentCaptor<MessagePostProcessor> postProcessorCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(
            eq(RabbitMqTopology.EXCHANGE),
            eq(RabbitMqTopology.RETRY_EMAIL_ROUTING_KEY),
            eq(message),
            postProcessorCaptor.capture()
        );

        Message processedMessage = postProcessorCaptor.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertThat(processedMessage.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(processedMessage.getMessageProperties().getExpiration()).isEqualTo("120000");
    }

    @Test
    void publishDeadLetterRoutesToDlqAsPersistent() throws Exception {
        RabbitMqQueuePublisher publisher = new RabbitMqQueuePublisher(rabbitTemplate, RabbitMqTopology.EXCHANGE);
        DeliveryMessage message = message(NotificationPriority.NORMAL);

        publisher.publishDeadLetter(message);

        ArgumentCaptor<MessagePostProcessor> postProcessorCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(
            eq(RabbitMqTopology.EXCHANGE),
            eq(RabbitMqTopology.DLQ_EMAIL_ROUTING_KEY),
            eq(message),
            postProcessorCaptor.capture()
        );

        Message processedMessage = postProcessorCaptor.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertThat(processedMessage.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    private static DeliveryMessage message(NotificationPriority priority) {
        return new DeliveryMessage(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Channel.EMAIL,
            priority,
            1
        );
    }
}
