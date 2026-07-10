package com.notificationplatform.common.observability;

import java.util.UUID;
import org.slf4j.MDC;

public final class CorrelationIds {
    public static final String MDC_KEY = "correlationId";
    public static final String HEADER = "X-Correlation-Id";

    private CorrelationIds() {
    }

    public static String resolve(String correlationId) {
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }

    public static void put(String correlationId) {
        String resolvedCorrelationId = resolve(correlationId);
        MDC.put(MDC_KEY, resolvedCorrelationId);
    }

    public static void remove() {
        MDC.remove(MDC_KEY);
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
