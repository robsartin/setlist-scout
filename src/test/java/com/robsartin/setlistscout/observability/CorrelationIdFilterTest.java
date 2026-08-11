package com.robsartin.setlistscout.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void mintsAValidCidWhenNoInboundHeaderAndEchoesItAndClearsAfter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/artists");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> cidDuring = new AtomicReference<>();
        FilterChain chain = (req, res) -> cidDuring.set(MDC.get(Correlation.CID));

        filter.doFilter(request, response, chain);

        assertThat(CorrelationIds.isValid(cidDuring.get())).isTrue();
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(cidDuring.get());
        assertThat(MDC.get(Correlation.CID)).isNull(); // cleared after
    }

    @Test
    void honorsAValidInboundRequestId() throws Exception {
        String inbound = CorrelationIds.newId();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader("X-Request-Id", inbound);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> cidDuring = new AtomicReference<>();
        FilterChain chain = (req, res) -> cidDuring.set(MDC.get(Correlation.CID));

        filter.doFilter(request, response, chain);

        assertThat(cidDuring.get()).isEqualTo(inbound);
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(inbound);
    }

    @Test
    void ignoresAJunkInboundRequestIdAndMintsInstead() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader("X-Request-Id", "not-a-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> cidDuring = new AtomicReference<>();
        FilterChain chain = (req, res) -> cidDuring.set(MDC.get(Correlation.CID));

        filter.doFilter(request, response, chain);

        assertThat(cidDuring.get()).isNotEqualTo("not-a-uuid");
        assertThat(CorrelationIds.isValid(cidDuring.get())).isTrue();
    }
}
