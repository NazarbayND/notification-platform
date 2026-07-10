package com.notificationplatform.common.observability;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "correlationIdFilter")
    Filter correlationIdFilter() {
        return (request, response, chain) -> {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            String correlationId = CorrelationIds.resolve(httpRequest.getHeader(CorrelationIds.HEADER));
            CorrelationIds.put(correlationId);
            httpResponse.setHeader(CorrelationIds.HEADER, correlationId);
            try {
                chain.doFilter(request, response);
            } finally {
                CorrelationIds.remove();
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(name = "correlationIdRestClientCustomizer")
    RestClientCustomizer correlationIdRestClientCustomizer() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            String correlationId = CorrelationIds.current();
            if (correlationId != null && !correlationId.isBlank()) {
                request.getHeaders().set(CorrelationIds.HEADER, correlationId);
            }
            return execution.execute(request, body);
        });
    }
}
