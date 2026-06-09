package com.notificationplatform.application.observability;

import java.util.Map;
import org.slf4j.MDC;

public final class MdcScope implements AutoCloseable {

    private final Map<String, String> previousContext;

    private MdcScope(Map<String, String> values) {
        this.previousContext = MDC.getCopyOfContextMap();
        values.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                MDC.put(key, value);
            }
        });
    }

    public static MdcScope with(Map<String, String> values) {
        return new MdcScope(values);
    }

    @Override
    public void close() {
        if (previousContext == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousContext);
        }
    }
}
