package com.notificationplatform.application.queue;

import com.notificationplatform.domain.model.Channel;
import com.notificationplatform.domain.model.NotificationPriority;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.stereotype.Component;

@Component
public class InMemoryDeliveryQueue implements QueuePublisher, DeliveryQueueConsumer {

    private static final NotificationPriority[] PRIORITY_ORDER = {
        NotificationPriority.HIGH,
        NotificationPriority.NORMAL,
        NotificationPriority.LOW
    };

    private final Map<NotificationPriority, Map<Channel, Queue<DeliveryMessage>>> queues =
        new EnumMap<>(NotificationPriority.class);

    public InMemoryDeliveryQueue() {
        for (NotificationPriority priority : NotificationPriority.values()) {
            Map<Channel, Queue<DeliveryMessage>> channelQueues = new EnumMap<>(Channel.class);
            for (Channel channel : Channel.values()) {
                channelQueues.put(channel, new ConcurrentLinkedQueue<>());
            }
            queues.put(priority, channelQueues);
        }
    }

    @Override
    public void publish(NotificationPriority priority, DeliveryMessage message) {
        NotificationPriority effectivePriority = priority == null ? NotificationPriority.NORMAL : priority;
        queues.get(effectivePriority).get(message.channel()).add(message);
    }

    @Override
    public Optional<DeliveryMessage> poll(Channel channel) {
        for (NotificationPriority priority : PRIORITY_ORDER) {
            DeliveryMessage message = queues.get(priority).get(channel).poll();
            if (message != null) {
                return Optional.of(message);
            }
        }
        return Optional.empty();
    }
}
