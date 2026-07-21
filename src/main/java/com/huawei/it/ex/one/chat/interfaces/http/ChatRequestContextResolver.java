package com.huawei.it.ex.one.chat.interfaces.http;

import com.huawei.it.ex.one.security.application.context.AuthContextProvider;
import com.huawei.it.ex.one.common.trace.TraceContextProvider;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import org.springframework.stereotype.Component;

@Component
public final class ChatRequestContextResolver {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatController.class);

    private final AuthContextProvider auth;
    private final TraceContextProvider traceContextProvider;
    private final PermissionChecker permissionChecker;
    private final RuntimeForwardHeaderExtractor forwardHeaderExtractor;

    public ChatRequestContextResolver(AuthContextProvider auth,
                                      TraceContextProvider traceContextProvider,
                                      PermissionChecker permissionChecker,
                                      RuntimeForwardHeaderExtractor forwardHeaderExtractor) {
        this.auth = auth;
        this.traceContextProvider = traceContextProvider;
        this.permissionChecker = permissionChecker;
        this.forwardHeaderExtractor = forwardHeaderExtractor;
    }

    public UserContext resolveChatUser() {
        UserContext user = auth.resolve();
        permissionChecker.checkChatPermission(user);
        return user;
    }

    public TraceContext resolveTraceContext() {
        try {
            TraceContext context = traceContextProvider == null ? null : traceContextProvider.resolve();
            return context == null ? TraceContext.empty() : context;
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                            "TraceContext provider failed at request entry; continuing without traceId")
                    .operation("trace.resolve")
                    .retryable(false)
                    .build(), ex);
            return TraceContext.empty();
        }
    }

    public RuntimeForwardHeaders forwardHeaders(String cookieHeader) {
        return forwardHeaderExtractor.fromCookieHeader(cookieHeader);
    }
}
