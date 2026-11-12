package com.smarthealthfinance.copilot.presentation.filter;

import com.smarthealthfinance.shared.domain.InvalidValueException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Limite de bytes do corpo nas rotas do Copilot (ADR-0040 §3, defesa em profundidade além do BFF): corpo acima de
 * {@value #MAX_BODY_BYTES} bytes é {@code 400 VALIDATION_FAILED} (campo {@code body}, motivo {@code TOO_LARGE}) antes de
 * qualquer parsing, hash ou consulta. Vale com ou sem {@code Content-Length}: sem ele, o filtro lê no máximo o limite
 * mais um byte. Roda depois da cadeia de segurança, então só pessoas autenticadas chegam aqui. O erro sai pelo mesmo
 * {@code GlobalExceptionHandler} das demais validações. Nada do corpo é registrado.
 */
@Component
public class CopilotBodyLimitFilter extends OncePerRequestFilter {

    /** 16 KB: a pergunta (500) e até 3 anteriores (500) cabem com folga, e o contexto é pequeno. */
    public static final int MAX_BODY_BYTES = 16 * 1024;

    private static final String COPILOT_SEGMENT = "/copilot/";

    private final HandlerExceptionResolver resolver;

    public CopilotBodyLimitFilter(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        boolean hasBody = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
        return !hasBody || !request.getRequestURI().contains(COPILOT_SEGMENT);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            reject(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(request, response);
            return;
        }
        chain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) {
        resolver.resolveException(request, response, null, new InvalidValueException("body", "TOO_LARGE"));
    }

    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Async reads are not supported");
                }

                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    return source.read(target, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
