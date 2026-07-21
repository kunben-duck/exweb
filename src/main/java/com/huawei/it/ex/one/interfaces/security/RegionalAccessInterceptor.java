package com.huawei.it.ex.one.interfaces.security;

import com.huawei.it.ex.one.application.config.RegionalAccessProperties;
import com.huawei.it.ex.one.application.integration.identity.AuthContextProvider;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessDecision;
import com.huawei.it.ex.one.application.service.security.RegionalAccessAuthorizer;
import com.huawei.it.ex.one.application.service.security.RegionalAccessDeniedException;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * Servlet entry gate that runs after enterprise identity initialization and before controllers.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RegionalAccessInterceptor implements AsyncHandlerInterceptor {
    static final String CHECKED_ATTRIBUTE = RegionalAccessInterceptor.class.getName() + ".checked";
    private static final AppLogger log = AppLoggerFactory.getLogger(RegionalAccessInterceptor.class);

    private final AuthContextProvider auth;
    private final RegionalAccessAuthorizer authorizer;
    private final TrustedClientIpResolver clientIpResolver;
    private final RegionalAccessProperties properties;

    public RegionalAccessInterceptor(AuthContextProvider auth, RegionalAccessAuthorizer authorizer,
                                     TrustedClientIpResolver clientIpResolver,
                                     RegionalAccessProperties properties) {
        this.auth = auth;
        this.authorizer = authorizer;
        this.clientIpResolver = clientIpResolver;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.isEnabled() || HttpMethod.OPTIONS.matches(request.getMethod())
                || Boolean.TRUE.equals(request.getAttribute(CHECKED_ATTRIBUTE))) {
            return true;
        }
        UserContext user = auth.resolve();
        if (user == null) {
            throw new SecurityException("当前用户身份缺失");
        }
        String clientIp = clientIpResolver.resolve(request.getHeader(properties.normalizedIpHeaderName()));
        RegionalAccessDecision decision = authorize(user, clientIp);
        if (decision == RegionalAccessDecision.BLOCK) {
            throw new RegionalAccessDeniedException();
        }
        request.setAttribute(CHECKED_ATTRIBUTE, Boolean.TRUE);
        return true;
    }

    private RegionalAccessDecision authorize(UserContext user, String clientIp) {
        Duration wait = properties.normalizedLookupTimeout().plusMillis(250);
        try {
            RegionalAccessDecision decision = authorizer.authorize(user, clientIp).block(wait);
            return decision == null ? RegionalAccessDecision.ALLOW : decision;
        } catch (SecurityException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Regional access authorization failed at the Servlet boundary; allowing request, exceptionClass={}",
                    ex.getClass().getName());
            return RegionalAccessDecision.ALLOW;
        }
    }
}
