package com.notificationplatform.application.queue;

import com.notificationplatform.domain.model.Channel;
import java.util.Optional;

public interface DeliveryQueueConsumer {

    Optional<DeliveryMessage> poll(Channel channel);
}
