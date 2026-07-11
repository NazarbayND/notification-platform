package com.notificationplatform.workersupport;

import com.notificationplatform.contracts.DeliveryRequested;
import com.notificationplatform.contracts.DeliveryResult;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

public final class KafkaWorkerCoordinator {
    private final String channel;
    private final KafkaTemplate<Object,Object> kafka;
    private final MeterRegistry meters;
    private final Semaphore concurrency;
    private final long minimumCallIntervalNanos;
    private final AtomicLong nextProviderCall = new AtomicLong();

    public KafkaWorkerCoordinator(String channel,KafkaTemplate<Object,Object> kafka,MeterRegistry meters,
            int maxConcurrency,int providerRatePerSecond){
        this.channel=channel;this.kafka=kafka;this.meters=meters;
        this.concurrency=new Semaphore(Math.max(1,maxConcurrency));
        this.minimumCallIntervalNanos=1_000_000_000L/Math.max(1,providerRatePerSecond);
        Gauge.builder("worker_active_tasks",concurrency,semaphore->Math.max(0,maxConcurrency-semaphore.availablePermits()))
                .tag("channel",channel).register(meters);
    }

    public void handle(DeliveryRequested delivery,ConsumerRecord<String,String> record,Acknowledgment acknowledgment,
            Runnable providerCall,Supplier<AttemptResult> resultLookup){
        Duration wait=requiredDelay(record.topic()).minus(Duration.ofMillis(Math.max(0,System.currentTimeMillis()-record.timestamp())));
        if(!wait.isNegative()&&!wait.isZero()){acknowledgment.nack(wait);return;}
        if(!concurrency.tryAcquire()){acknowledgment.nack(Duration.ofMillis(250));return;}
        long now=System.nanoTime();long allowed=nextProviderCall.getAndUpdate(current->Math.max(current,now)+minimumCallIntervalNanos);
        if(allowed>now){concurrency.release();acknowledgment.nack(Duration.ofNanos(allowed-now));return;}
        try{
            providerCall.run();
            AttemptResult result=resultLookup.get();
            if(result==null){acknowledgment.acknowledge();return;}
            publishResult(delivery,result);
            if(!result.success()){
                if(result.transientFailure())publishRetry(delivery,record,result.errorCode(),result.errorMessage());
                else publishPermanentFailure(delivery,record,result.errorCode(),result.errorMessage());
            }
            acknowledgment.acknowledge();
        }catch(RuntimeException exception){
            publishRetry(delivery,record,"WORKER_EXCEPTION",exception.getMessage());
            acknowledgment.acknowledge();
        }finally{concurrency.release();}
    }

    public void malformed(ConsumerRecord<String,String> record,String rawPayload,Exception error,Acknowledgment acknowledgment){
        send(topic("dlq"),String.valueOf(record.key()),Map.of("originalTopic",record.topic(),"originalPartition",record.partition(),
                "originalOffset",record.offset(),"errorCode","INVALID_DELIVERY_EVENT","errorMessage",truncate(String.valueOf(error.getMessage())),
                "rawPayload",truncate(String.valueOf(rawPayload)),"failedAt",Instant.now().toString()));
        meters.counter("worker_dlq_total","channel",channel,"reason","invalid_event").increment();
        acknowledgment.acknowledge();
    }

    private void publishResult(DeliveryRequested delivery,AttemptResult result){
        String eventId=UUID.nameUUIDFromBytes(("result:"+delivery.eventId()).getBytes(StandardCharsets.UTF_8)).toString();
        DeliveryResult event=new DeliveryResult(eventId,delivery.notificationId(),delivery.deliveryId(),delivery.tenantId(),
                delivery.channel(),result.success()?"DELIVERED":"FAILED",delivery.attempt(),result.providerMessageId(),
                result.errorCode(),truncate(result.errorMessage()),Instant.now(),1);
        send("notification.delivery-results.v1",delivery.tenantId()+":"+delivery.recipientId(),event);
    }

    private void publishRetry(DeliveryRequested delivery,ConsumerRecord<String,String> record,String code,String message){
        if(delivery.attempt()>=4){
            send(topic("dlq"),delivery.tenantId()+":"+delivery.recipientId(),dlqEvent(delivery,record,code,message));
            meters.counter("worker_dlq_total","channel",channel).increment();return;
        }
        String stage=switch(delivery.attempt()){case 1->"retry-1m";case 2->"retry-5m";default->"retry-30m";};
        send(topic(stage),delivery.tenantId()+":"+delivery.recipientId(),retryEvent(delivery,record,code,message));
        meters.counter("worker_retries_total","channel",channel,"stage",stage).increment();
    }

    private void publishPermanentFailure(DeliveryRequested delivery,ConsumerRecord<String,String> record,String code,String message){
        send(topic("dlq"),delivery.tenantId()+":"+delivery.recipientId(),dlqEvent(delivery,record,code,message));
        meters.counter("worker_dlq_total","channel",channel,"reason","permanent_failure").increment();
    }

    private DeliveryRequested retryEvent(DeliveryRequested d,ConsumerRecord<String,String> record,String code,String message){
        Instant now=Instant.now();return new DeliveryRequested(UUID.randomUUID().toString(),d.notificationId(),d.deliveryId(),
                d.tenantId(),d.recipientId(),d.channel(),d.recipientAddress(),d.subject(),d.body(),d.attempt()+1,now,1,
                d.originalTopic()==null?record.topic():d.originalTopic(),d.originalPartition()==null?record.partition():d.originalPartition(),
                d.originalOffset()==null?record.offset():d.originalOffset(),d.firstFailureAt()==null?now:d.firstFailureAt(),now,code,truncate(message));
    }

    private DeliveryRequested dlqEvent(DeliveryRequested d,ConsumerRecord<String,String> record,String code,String message){
        Instant now=Instant.now();return new DeliveryRequested(UUID.randomUUID().toString(),d.notificationId(),d.deliveryId(),
                d.tenantId(),d.recipientId(),d.channel(),d.recipientAddress(),d.subject(),d.body(),d.attempt(),now,1,
                d.originalTopic()==null?record.topic():d.originalTopic(),d.originalPartition()==null?record.partition():d.originalPartition(),
                d.originalOffset()==null?record.offset():d.originalOffset(),d.firstFailureAt()==null?now:d.firstFailureAt(),now,code,truncate(message));
    }

    private void send(String topic,String key,Object value){
        try{kafka.send(topic,key,value).get(5,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException("Kafka publication failed",e);}
    }
    private String topic(String suffix){return "notification."+channel.toLowerCase(Locale.ROOT).replace('_','-')+"."+suffix+".v1";}
    private Duration requiredDelay(String topic){
        if(topic.contains("retry-1m"))return Duration.ofMinutes(1);
        if(topic.contains("retry-5m"))return Duration.ofMinutes(5);
        if(topic.contains("retry-30m"))return Duration.ofMinutes(30);
        return Duration.ZERO;
    }
    private String truncate(String message){return message==null?null:message.substring(0,Math.min(1000,message.length()));}

    public record AttemptResult(String providerMessageId,String status,String errorCode,String errorMessage){
        public boolean success(){return "SENT".equalsIgnoreCase(status)||"DELIVERED".equalsIgnoreCase(status);}
        public boolean transientFailure(){if(success())return false;String code=errorCode==null?"":errorCode.toUpperCase(Locale.ROOT);
            return !(code.contains("INVALID")||code.contains("UNSUPPORTED")||code.contains("BAD_REQUEST")||code.contains("MISSING"));}
    }
}
