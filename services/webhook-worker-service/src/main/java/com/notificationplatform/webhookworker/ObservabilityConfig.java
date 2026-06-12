package com.notificationplatform.webhookworker;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ObservabilityConfig {
    static final String CORRELATION_ID = "correlationId";
    static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Bean
    Filter correlationIdFilter() {
        return (request, response, chain) -> {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            String correlationId = httpRequest.getHeader(CORRELATION_HEADER);
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = UUID.randomUUID().toString();
            }
            MDC.put(CORRELATION_ID, correlationId);
            httpResponse.setHeader(CORRELATION_HEADER, correlationId);
            try {
                chain.doFilter(request, response);
            } finally {
                MDC.remove(CORRELATION_ID);
            }
        };
    }

    @Bean
    RestClientCustomizer correlationIdRestClientCustomizer() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            String correlationId = MDC.get(CORRELATION_ID);
            if (correlationId != null && !correlationId.isBlank()) {
                request.getHeaders().set(CORRELATION_HEADER, correlationId);
            }
            return execution.execute(request, body);
        });
    }
}
