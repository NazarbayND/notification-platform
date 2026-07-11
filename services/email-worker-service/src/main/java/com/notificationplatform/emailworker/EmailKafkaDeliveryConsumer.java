package com.notificationplatform.emailworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notificationplatform.contracts.DeliveryRequested;
import com.notificationplatform.workersupport.KafkaWorkerCoordinator;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
class EmailKafkaDeliveryConsumer {
    private final ObjectMapper mapper;private final JdbcTemplate jdbc;
    private final EmailWorkerServiceApplication.EmailDeliveryConsumer delegate;private final KafkaWorkerCoordinator coordinator;
    EmailKafkaDeliveryConsumer(ObjectMapper mapper,JdbcTemplate jdbc,EmailWorkerServiceApplication.EmailDeliveryConsumer delegate,
            KafkaTemplate<Object,Object> kafka,MeterRegistry meters,@Value("${worker.provider.max-concurrency:32}")int concurrency,
            @Value("${worker.provider.rate-per-second:100}")int rate){this.mapper=mapper;this.jdbc=jdbc;this.delegate=delegate;
        this.coordinator=new KafkaWorkerCoordinator("EMAIL",kafka,meters,concurrency,rate);}
    @KafkaListener(topics={"notification.email.v1","notification.email.retry-1m.v1","notification.email.retry-5m.v1","notification.email.retry-30m.v1"},
            groupId="${notification.kafka.group:email-worker-v1}")
    void consume(ConsumerRecord<String,String> record,Acknowledgment ack){String json=record.value();
        DeliveryRequested d;try{d=mapper.readValue(json,DeliveryRequested.class);}catch(Exception e){coordinator.malformed(record,json,e,ack);return;}
        try{var job=new EmailWorkerServiceApplication.DeliveryJob(UUID.fromString(d.eventId()),UUID.fromString(d.notificationId()),
                UUID.fromString(d.deliveryId()),d.channel(),d.recipientAddress(),d.subject(),d.body(),"NORMAL",d.notificationId());
            coordinator.handle(d,record,ack,()->delegate.consume(job,null),()->lookup(job.eventId()));}
        catch(IllegalArgumentException e){coordinator.malformed(record,json,e,ack);}}
    private KafkaWorkerCoordinator.AttemptResult lookup(UUID event){return jdbc.query("""
        SELECT provider_message_id,status,error_code,error_message FROM delivery_attempts WHERE event_id=? ORDER BY created_at DESC LIMIT 1
        """,rs->rs.next()?new KafkaWorkerCoordinator.AttemptResult(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)):null,event);}
}
