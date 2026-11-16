package com.smarthealthfinance.copilot.presentation.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.smarthealthfinance.shared.domain.InvalidValueException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CopilotBodyLimitFilterTest {

    private final List<Exception> rejected = new ArrayList<>();
    private final CopilotBodyLimitFilter filter = new CopilotBodyLimitFilter((request, response, handler, error) -> {
        rejected.add(error);
        return null;
    });

    /** Corpo chunked: sem Content-Length, o tamanho só se sabe lendo. */
    private static MockHttpServletRequest chunked(int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/v1/workspaces/w/copilot/questions") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent("x".repeat(bytes).getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void aChunkedBodyAboveTheLimitIsRejectedWithoutContentLength() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(chunked(CopilotBodyLimitFilter.MAX_BODY_BYTES + 1), new MockHttpServletResponse(), chain);

        assertThat(rejected).singleElement().isInstanceOfSatisfying(InvalidValueException.class, error -> {
            assertThat(error.field()).isEqualTo("body");
            assertThat(error.reason()).isEqualTo("TOO_LARGE");
        });
        assertThat(chain.getRequest()).as("a cadeia não segue").isNull();
    }

    @Test
    void aChunkedBodyAtTheLimitPassesAndIsReplayable() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(chunked(CopilotBodyLimitFilter.MAX_BODY_BYTES), new MockHttpServletResponse(), chain);

        assertThat(rejected).isEmpty();
        assertThat(chain.getRequest().getInputStream().readAllBytes()).hasSize(CopilotBodyLimitFilter.MAX_BODY_BYTES);
    }
}
