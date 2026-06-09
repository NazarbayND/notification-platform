package com.notificationplatform.application.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class NotificationTracing {

    private final ObservationRegistry observationRegistry;

    public NotificationTracing(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    public <T> T observe(String name, Supplier<T> operation) {
        return observe(name, Map.of(), operation);
    }

    public <T> T observe(String name, Map<String, String> lowCardinalityTags, Supplier<T> operation) {
        Observation observation = Observation.createNotStarted(name, observationRegistry);
        lowCardinalityTags.forEach((key, value) -> observation.lowCardinalityKeyValue(KeyValue.of(key, value)));
        return observation.observe(operation);
    }

    public void observe(String name, Runnable operation) {
        observe(name, Map.of(), operation);
    }

    public void observe(String name, Map<String, String> lowCardinalityTags, Runnable operation) {
        Observation observation = Observation.createNotStarted(name, observationRegistry);
        lowCardinalityTags.forEach((key, value) -> observation.lowCardinalityKeyValue(KeyValue.of(key, value)));
        observation.observe(operation);
    }
}
