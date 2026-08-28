package com.huawei.it.ex.one.interfaces.chat;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.service.chat.DomainAgentAsyncCallbackAdmission;
import com.huawei.it.ex.one.domain.chat.DomainAgentAsyncCallbackPayloadTooLargeException;
import com.huawei.it.ex.one.interfaces.ApiExceptionHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Applies callback concurrency and body-size limits before Spring deserializes the JSON body. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class DomainAgentAsyncTaskCallbackAdmissionFilter extends OncePerRequestFilter {
    static final String CALLBACK_PATH = "/v1/internal/domain-agent/async-tasks/callback";
    private static final PathPattern CALLBACK_PATTERN =
            PathPatternParser.defaultInstance.parse(CALLBACK_PATH);

    private final DomainAgentAsyncCallbackAdmission admission;
    private final ObjectMapper objectMapper;
    private final int maxRequestBytes;

    public DomainAgentAsyncTaskCallbackAdmissionFilter(
            DomainAgentAsyncCallbackAdmission admission,
            DomainAgentProperties properties,
            ObjectMapper objectMapper) {
        this.admission = admission;
        this.objectMapper = objectMapper;
        this.maxRequestBytes = properties.requiredAsyncTaskCallbackRequestMaxBytes();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !request.getRequestURI().contains(CALLBACK_PATH)
                || !callbackPath(request);
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        DomainAgentAsyncCallbackAdmission.Permit permit = admission.tryAcquire();
        if (permit == null) {
            writeError(response, request, HttpStatus.TOO_MANY_REQUESTS,
                    "DOMAIN_AGENT_ASYNC_CALLBACK_BUSY",
                    "DomainAgent async callback capacity is exhausted");
            return;
        }

        boolean releaseByAsyncListener = false;
        try {
            if (request.getContentLengthLong() > maxRequestBytes) {
                writeTooLarge(response, request);
                return;
            }
            HttpServletRequest bounded = request.getContentLengthLong() < 0L
                    ? new CachedBodyRequest(request, readBoundedBody(request), maxRequestBytes)
                    : new BoundedBodyRequest(request, maxRequestBytes);
            try {
                filterChain.doFilter(bounded, response);
            } catch (RuntimeException ex) {
                if (causedByPayloadLimit(ex) && !response.isCommitted()) {
                    writeTooLarge(response, request);
                    return;
                }
                throw ex;
            }
            if (bounded.isAsyncStarted()) {
                try {
                    bounded.getAsyncContext().addListener(new PermitReleaseListener(permit));
                    releaseByAsyncListener = true;
                } catch (IllegalStateException ignored) {
                    // The async response completed between the state check and listener registration.
                }
            }
        } catch (DomainAgentAsyncCallbackPayloadTooLargeException ex) {
            if (!response.isCommitted()) {
                writeTooLarge(response, request);
                return;
            }
            throw ex;
        } finally {
            if (!releaseByAsyncListener) {
                permit.close();
            }
        }
    }

    private byte[] readBoundedBody(HttpServletRequest request) throws IOException {
        try (ServletInputStream input = request.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxRequestBytes, 8192))) {
            byte[] buffer = new byte[8192];
            long consumed = 0L;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                consumed += read;
                if (consumed > maxRequestBytes) {
                    throw new DomainAgentAsyncCallbackPayloadTooLargeException(
                            "DomainAgent async callback request exceeds the configured size limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void writeTooLarge(HttpServletResponse response, HttpServletRequest request) throws IOException {
        writeError(response, request, HttpStatus.PAYLOAD_TOO_LARGE,
                "DOMAIN_AGENT_ASYNC_CALLBACK_TOO_LARGE",
                "DomainAgent async callback request exceeds the configured size limit");
    }

    private void writeError(
            HttpServletResponse response,
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String message) throws IOException {
        response.reset();
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiExceptionHandler.ApiErrorResponse(
                Instant.now(), request.getRequestURI(), status.value(), status.getReasonPhrase(), code, message));
    }

    private boolean causedByPayloadLimit(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DomainAgentAsyncCallbackPayloadTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean callbackPath(HttpServletRequest request) {
        RequestPath requestPath = ServletRequestPathUtils.parseAndCache(request);
        return CALLBACK_PATTERN.matches(requestPath.pathWithinApplication());
    }

    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {
        private final long maxBytes;
        private ServletInputStream inputStream;

        private BoundedBodyRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (inputStream == null) {
                inputStream = new CountingServletInputStream(super.getInputStream(), maxBytes);
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
        }
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private final long maxBytes;

        private CachedBodyRequest(HttpServletRequest request, byte[] body, long maxBytes) {
            super(request);
            this.body = body == null ? new byte[0] : body;
            this.maxBytes = maxBytes;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CountingServletInputStream(
                    new ByteArrayServletInputStream(body), maxBytes);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
        }
    }

    private static final class ByteArrayServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;

        private ByteArrayServletInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return delegate.read(bytes, offset, length);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            if (readListener == null) {
                return;
            }
            try {
                if (!isFinished()) {
                    readListener.onDataAvailable();
                }
                if (isFinished()) {
                    readListener.onAllDataRead();
                }
            } catch (IOException ex) {
                readListener.onError(ex);
            }
        }
    }

    private static final class CountingServletInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maxBytes;
        private long consumed;

        private CountingServletInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                account(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            long remaining = Math.max(0L, maxBytes - consumed);
            int allowed = (int) Math.min(length, Math.min(Integer.MAX_VALUE, remaining + 1L));
            int read = delegate.read(bytes, offset, allowed);
            if (read > 0) {
                account(read);
            }
            return read;
        }

        private void account(int bytes) {
            consumed += bytes;
            if (consumed > maxBytes) {
                throw new DomainAgentAsyncCallbackPayloadTooLargeException(
                        "DomainAgent async callback request exceeds the configured size limit");
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }

    private static final class PermitReleaseListener implements AsyncListener {
        private final DomainAgentAsyncCallbackAdmission.Permit permit;

        private PermitReleaseListener(DomainAgentAsyncCallbackAdmission.Permit permit) {
            this.permit = permit;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            permit.close();
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            permit.close();
        }

        @Override
        public void onError(AsyncEvent event) {
            permit.close();
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }
}
