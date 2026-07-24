package com.notificationplatform.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notificationplatform.contracts.DeliveryResult;
import com.notificationplatform.contracts.NotificationRequested;
import com.notificationplatform.contracts.NotificationStatusChanged;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@SpringBootApplication
public class NotificationProjectionApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationProjectionApplication.class, args);
    }
}

@Component
class ProjectionConsumers {
    private final ObjectMapper mapper;
    private final NotificationProjectionRepository repository;
    private final MeterRegistry meters;

    ProjectionConsumers(ObjectMapper mapper, NotificationProjectionRepository repository, MeterRegistry meters) {
        this.mapper=mapper;this.repository=repository;this.meters=meters;
    }

    @KafkaListener(topics="notification.requests.v1",groupId="${projection.groups.requests:notification-projection-requests-v1}")
    void requested(String json) throws Exception {
        timed("requested",()->repository.upsertAcceptedNotification(mapper.readValue(json,NotificationRequested.class)));
    }
    @KafkaListener(topics="notification.status-events.v1",groupId="${projection.groups.status:notification-projection-status-v1}")
    void status(String json) throws Exception {
        timed("status",()->repository.updateNotificationStatus(mapper.readValue(json,NotificationStatusChanged.class)));
    }
    @KafkaListener(topics="notification.delivery-results.v1",groupId="${projection.groups.results:notification-projection-results-v1}")
    void result(String json) throws Exception {
        timed("result",()->repository.appendDeliveryAttempt(mapper.readValue(json,DeliveryResult.class)));
    }
    private void timed(String type,CheckedTask task) throws Exception {Timer.Sample sample=Timer.start(meters);try{task.run();}
        finally{sample.stop(Timer.builder("projection_update_latency").tag("type",type).register(meters));}}
    @FunctionalInterface interface CheckedTask {void run() throws Exception;}
}

interface NotificationProjectionRepository {
    void upsertAcceptedNotification(NotificationRequested event);
    void updateNotificationStatus(NotificationStatusChanged event);
    void upsertDelivery(DeliveryResult event);
    void appendDeliveryAttempt(DeliveryResult event);
    Optional<NotificationView> findById(String notificationId);
    PageView<NotificationView> findAll(String tenantId,String productId,String status,String channel,int page,int size);
    PageView<NotificationView> findByUser(String tenantId,String userId,int page,int size);
    List<DeliveryView> findDeliveries(String notificationId);
    PageView<DeliveryView> findDeliveries(String notificationId,String status,String channel,int page,int size);
    Map<String,Object> stats();
    void clearForRebuild();
}

@Repository
class PostgresNotificationProjectionRepository implements NotificationProjectionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    PostgresNotificationProjectionRepository(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}

    @Override @Transactional
    public void upsertAcceptedNotification(NotificationRequested e){
        if(!mark("requests",e.eventId()))return;
        jdbc.update("""
            INSERT INTO notifications(notification_id,request_id,tenant_id,product_id,user_id,template_id,requested_channels,status,requested_at,updated_at)
            VALUES (?,?,?,?,?,?,?::jsonb,'ACCEPTED',?,?) ON CONFLICT(notification_id) DO UPDATE SET
              request_id=EXCLUDED.request_id,tenant_id=EXCLUDED.tenant_id,product_id=EXCLUDED.product_id,
              user_id=EXCLUDED.user_id,template_id=EXCLUDED.template_id,requested_channels=EXCLUDED.requested_channels,
              requested_at=EXCLUDED.requested_at
            """,e.notificationId(),e.requestId(),e.tenantId(),e.productId(),e.recipient().userId(),e.templateId(),json(e.requestedChannels()),
                ts(e.requestedAt()),ts(e.requestedAt()));
    }
    @Override @Transactional
    public void updateNotificationStatus(NotificationStatusChanged e){
        if(!mark("status",e.eventId()))return;
        jdbc.update("""
            INSERT INTO notifications(notification_id,tenant_id,status,reason_code,reason_message,updated_at)
            VALUES (?,?,?,?,?,?) ON CONFLICT(notification_id) DO UPDATE SET status=EXCLUDED.status,
              reason_code=EXCLUDED.reason_code,reason_message=EXCLUDED.reason_message,updated_at=EXCLUDED.updated_at
            WHERE notifications.updated_at <= EXCLUDED.updated_at
            """,e.notificationId(),e.tenantId(),e.status(),e.reasonCode(),e.reasonMessage(),ts(e.occurredAt()));
    }
    @Override @Transactional
    public void upsertDelivery(DeliveryResult e){
        jdbc.update("""
            INSERT INTO deliveries(delivery_id,notification_id,tenant_id,channel,status,attempt,provider_message_id,error_code,error_message,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(delivery_id) DO UPDATE SET status=EXCLUDED.status,attempt=EXCLUDED.attempt,
              provider_message_id=EXCLUDED.provider_message_id,error_code=EXCLUDED.error_code,error_message=EXCLUDED.error_message,
              updated_at=EXCLUDED.updated_at WHERE deliveries.attempt <= EXCLUDED.attempt
            """,e.deliveryId(),e.notificationId(),e.tenantId(),e.channel(),e.status(),e.attempt(),e.providerMessageId(),
                e.errorCode(),e.errorMessage(),ts(e.occurredAt()));
    }
    @Override @Transactional
    public void appendDeliveryAttempt(DeliveryResult e){
        if(!mark("results",e.eventId()))return;
        jdbc.update("""
            INSERT INTO delivery_attempts(event_id,delivery_id,notification_id,attempt,status,provider_message_id,error_code,error_message,occurred_at)
            VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
            """,e.eventId(),e.deliveryId(),e.notificationId(),e.attempt(),e.status(),e.providerMessageId(),e.errorCode(),e.errorMessage(),ts(e.occurredAt()));
        upsertDelivery(e);
        aggregate(e.notificationId(),e.occurredAt());
    }
    @Override public Optional<NotificationView> findById(String id){
        List<NotificationView> values=jdbc.query("SELECT * FROM notifications WHERE notification_id=?",this::mapNotification,id);
        return values.stream().findFirst();
    }
    @Override public PageView<NotificationView> findAll(String tenant,String product,String status,String channel,int page,int size){
        int limit=Math.max(1,Math.min(size,200));int offset=Math.max(0,page)*limit;
        String channelJson=channel==null?null:"[\""+channel.toUpperCase()+"\"]";
        String where=" WHERE (?::text IS NULL OR tenant_id=?) AND (?::text IS NULL OR product_id=?) AND (?::text IS NULL OR status=?) AND (?::jsonb IS NULL OR requested_channels @> ?::jsonb)";
        Long total=jdbc.queryForObject("SELECT count(*) FROM notifications"+where,Long.class,
                tenant,tenant,product,product,status,status,channelJson,channelJson);
        List<NotificationView> items=jdbc.query("SELECT * FROM notifications"+where+" ORDER BY requested_at DESC NULLS LAST LIMIT ? OFFSET ?",
                this::mapNotification,tenant,tenant,product,product,status,status,channelJson,channelJson,limit,offset);
        return new PageView<>(items,total==null?0:total,page,limit);
    }
    @Override public PageView<NotificationView> findByUser(String tenant,String user,int page,int size){
        int limit=Math.max(1,Math.min(size,200));int offset=Math.max(0,page)*limit;
        String where=" WHERE (?::text IS NULL OR tenant_id=?) AND user_id=?";
        Long total=jdbc.queryForObject("SELECT count(*) FROM notifications"+where,Long.class,tenant,tenant,user);
        List<NotificationView> items=jdbc.query("SELECT * FROM notifications"+where+" ORDER BY requested_at DESC NULLS LAST LIMIT ? OFFSET ?",
                this::mapNotification,tenant,tenant,user,limit,offset);
        return new PageView<>(items,total==null?0:total,page,limit);
    }
    @Override public List<DeliveryView> findDeliveries(String id){
        return jdbc.query("SELECT * FROM deliveries WHERE notification_id=? ORDER BY updated_at DESC",this::mapDelivery,id);
    }
    @Override public PageView<DeliveryView> findDeliveries(String notificationId,String status,String channel,int page,int size){
        int limit=Math.max(1,Math.min(size,200));int offset=Math.max(0,page)*limit;
        String where=" WHERE (?::text IS NULL OR notification_id=?) AND (?::text IS NULL OR status=?) AND (?::text IS NULL OR channel=?)";
        Long total=jdbc.queryForObject("SELECT count(*) FROM deliveries"+where,Long.class,
                notificationId,notificationId,status,status,channel,channel);
        List<DeliveryView> items=jdbc.query("SELECT * FROM deliveries"+where+" ORDER BY updated_at DESC LIMIT ? OFFSET ?",
                this::mapDelivery,notificationId,notificationId,status,status,channel,channel,limit,offset);
        return new PageView<>(items,total==null?0:total,page,limit);
    }
    @Override public Map<String,Object> stats(){
        Map<String,Long> statuses=jdbc.query("SELECT status,count(*) FROM notifications GROUP BY status",rs->{
            Map<String,Long> result=new java.util.HashMap<>();while(rs.next())result.put(rs.getString(1),rs.getLong(2));return result;});
        Long today=jdbc.queryForObject("SELECT count(*) FROM notifications WHERE requested_at >= date_trunc('day',now())",Long.class);
        Long retries=jdbc.queryForObject("SELECT count(*) FROM delivery_attempts WHERE attempt > 1",Long.class);
        long delivered=statuses.getOrDefault("DELIVERED",0L)+statuses.getOrDefault("PARTIALLY_DELIVERED",0L);
        long failed=statuses.getOrDefault("FAILED",0L);long completed=delivered+failed;
        return Map.of("totalNotificationsToday",today==null?0:today,"deliveredCount",delivered,"failedCount",failed,
                "retryAttemptCount",retries==null?0:retries,
                "providerErrorRate",completed==0?0.0:(double)failed/completed);
    }
    @Override @Transactional public void clearForRebuild(){
        jdbc.execute("TRUNCATE delivery_attempts,deliveries,notifications,processed_events");
    }
    private boolean mark(String consumer,String event){return jdbc.update(
            "INSERT INTO processed_events(consumer_name,event_id,processed_at) VALUES (?,?,now()) ON CONFLICT DO NOTHING",consumer,event)==1;}
    private void aggregate(String notificationId,Instant at){
        Map<String,Long> counts=jdbc.query("SELECT status,count(*) c FROM deliveries WHERE notification_id=? GROUP BY status",rs->{
            Map<String,Long> map=new java.util.HashMap<>();while(rs.next())map.put(rs.getString(1),rs.getLong(2));return map;},notificationId);
        long delivered=counts.getOrDefault("DELIVERED",0L)+counts.getOrDefault("SENT",0L);
        long failed=counts.getOrDefault("FAILED",0L);long total=counts.values().stream().mapToLong(Long::longValue).sum();
        String status=delivered==total&&total>0?"DELIVERED":failed==total&&total>0?"FAILED":delivered>0&&failed>0?"PARTIALLY_DELIVERED":"PROCESSING";
        jdbc.update("UPDATE notifications SET status=?,updated_at=? WHERE notification_id=?",status,ts(at),notificationId);
    }
    private NotificationView mapNotification(ResultSet rs,int row)throws SQLException{String id=rs.getString("notification_id");
        String template=rs.getString("template_id");Instant requested=instant(rs,"requested_at");return new NotificationView(
            id,id,rs.getString("request_id"),rs.getString("tenant_id"),rs.getString("product_id"),rs.getString("user_id"),
            template,template,firstChannel(rs.getString("requested_channels")),rs.getString("status"),rs.getString("reason_code"),rs.getString("reason_message"),
            requested,requested,instant(rs,"updated_at"));}
    private DeliveryView mapDelivery(ResultSet rs,int row)throws SQLException{String id=rs.getString("delivery_id");
        String notification=rs.getString("notification_id");String channel=rs.getString("channel");int attempt=rs.getInt("attempt");
        return new DeliveryView(id,id,notification,notification,channel,channel.toLowerCase(),null,rs.getString("status"),attempt,attempt,
            rs.getString("provider_message_id"),rs.getString("error_code"),rs.getString("error_message"),instant(rs,"updated_at"));}
    private String firstChannel(String json){try{JsonNode node=mapper.readTree(json);return node.isArray()&&node.size()>0?node.get(0).asText():null;}
        catch(Exception exception){return null;}}
    private Instant instant(ResultSet rs,String column)throws SQLException{var value=rs.getTimestamp(column);return value==null?null:value.toInstant();}
    private java.sql.Timestamp ts(Instant i){return i==null?null:java.sql.Timestamp.from(i);}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException(e);}}
}

@RestController
@RequestMapping("/projections/notifications")
class ProjectionController {
    private final NotificationProjectionRepository repository;
    ProjectionController(NotificationProjectionRepository repository){this.repository=repository;}
    @GetMapping("/{id}") NotificationView get(@PathVariable String id){return repository.findById(id)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification not found: "+id));}
    @GetMapping("/{id}/status") NotificationStatusView status(@PathVariable String id){NotificationView n=get(id);
        return new NotificationStatusView(n.notificationId(),n.status(),n.updatedAt());}
    @GetMapping NotificationPage list(@RequestParam(required=false) String tenantId,@RequestParam(required=false) String userId,
            @RequestParam(required=false) String productId,
            @RequestParam(required=false) String status,@RequestParam(required=false) String channel,
            @RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size){
        PageView<NotificationView> result=userId==null?repository.findAll(tenantId,productId,status,channel,page,size)
                :repository.findByUser(tenantId,userId,page,size);
        return new NotificationPage(result.items(),result.total(),result.page(),result.size());}
    @GetMapping("/{id}/deliveries") List<DeliveryView> deliveries(@PathVariable String id){return repository.findDeliveries(id);}
    @PostMapping("/rebuild/clear") Map<String,Object> clear(){repository.clearForRebuild();return Map.of("status","CLEARED","at",Instant.now());}
    @GetMapping("/stats") Map<String,Object> stats(){return repository.stats();}
}

@RestController
@RequestMapping("/projections/deliveries")
class DeliveryProjectionController {
    private final NotificationProjectionRepository repository;
    DeliveryProjectionController(NotificationProjectionRepository repository){this.repository=repository;}
    @GetMapping DeliveryPage list(@RequestParam(required=false)String notificationId,
            @RequestParam(required=false)String status,@RequestParam(required=false)String channel,
            @RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size){
        PageView<DeliveryView> result=repository.findDeliveries(notificationId,status,channel,page,size);
        return new DeliveryPage(result.items(),result.total(),result.page(),result.size());
    }
}

record NotificationView(String notificationId,String id,String requestId,String tenantId,String productId,String userId,
        String templateId,String templateKey,String channel,String status,String reasonCode,String reasonMessage,
        Instant requestedAt,Instant createdAt,Instant updatedAt){}
record NotificationStatusView(String notificationId,String status,Instant updatedAt){}
record DeliveryView(String deliveryId,String id,String notificationId,String notificationRequestId,String channel,String provider,
        String destination,String status,int attempt,int attemptCount,String providerMessageId,String errorCode,String errorMessage,Instant updatedAt){}
record PageView<T>(List<T> items,long total,int page,int size){}
record NotificationPage(List<NotificationView> items,long total,int page,int size){}
record DeliveryPage(List<DeliveryView> items,long total,int page,int size){}
