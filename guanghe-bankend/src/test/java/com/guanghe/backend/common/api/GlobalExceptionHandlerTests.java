package com.guanghe.backend.common.api;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTests {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void committedStreamFailureNeverAttemptsToReplaceEventsWithJson() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType("text/event-stream");
        response.getWriter().write("id: 1\ndata: saved\n\n");
        response.flushBuffer();

        assertThat(handler.handleUnexpectedException(new IllegalStateException("stream failed"), response)).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("id: 1\ndata: saved\n\n");
    }

    @Test
    void disconnectedClientReceivesNoSecondErrorResponse() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(handler.handleUnexpectedException(new IOException("Broken pipe"), response)).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void ordinaryServerFailureRetainsTheExistingJsonErrorContract() {
        var result = handler.handleUnexpectedException(new IllegalStateException("failed"),
                new MockHttpServletResponse());
        assertThat(result).isNotNull();
        assertThat(result.getStatusCode().value()).isEqualTo(500);
        assertThat(result.getBody().code()).isEqualTo("INTERNAL_ERROR");
    }
}
