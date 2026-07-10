package com.notificationplatform.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import jakarta.servlet.Filter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ObservabilityAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ObservabilityAutoConfiguration.class);

    @Test
    void contributesCorrelationBeans() {
        contextRunner.run(context -> assertThat(context)
                .hasBean("correlationIdFilter")
                .hasBean("correlationIdRestClientCustomizer"));
    }

    @Test
    void preservesIncomingCorrelationIdAndClearsMdc() {
        contextRunner.run(context -> {
            Filter filter = context.getBean("correlationIdFilter", Filter.class);
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            request.addHeader(CorrelationIds.HEADER, "request-123");

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getHeader(CorrelationIds.HEADER)).isEqualTo("request-123");
            assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
        });
    }

    @Test
    void createsCorrelationIdWhenRequestDoesNotProvideOne() {
        contextRunner.run(context -> {
            Filter filter = context.getBean("correlationIdFilter", Filter.class);
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThatCode(() -> UUID.fromString(response.getHeader(CorrelationIds.HEADER)))
                    .doesNotThrowAnyException();
            assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
        });
    }
}
