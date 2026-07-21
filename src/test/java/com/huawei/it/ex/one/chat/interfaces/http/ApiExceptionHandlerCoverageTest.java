package com.huawei.it.ex.one.chat.interfaces.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.huawei.it.ex.one.bootstrap.web.ApiExceptionHandler;
import com.huawei.it.ex.one.bootstrap.web.ReactiveApiExceptionHandler;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class ApiExceptionHandlerCoverageTest {
    private static final String APPLICATION_ROOT_PACKAGE = "com.huawei.it.ex.one";

    @Test
    void movedChatControllerStillUsesServletErrorEnvelope() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new InvalidRunController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(post("/v1/chat/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.path").value("/v1/chat/runs"));
    }

    @Test
    void servletAndReactiveAdviceCoverEveryApplicationRestController() {
        Set<String> controllerPackages = applicationRestControllerPackages();

        assertThat(controllerPackages).isNotEmpty();
        assertAdviceCovers(ApiExceptionHandler.class, controllerPackages);
        assertAdviceCovers(ReactiveApiExceptionHandler.class, controllerPackages);
    }

    private Set<String> applicationRestControllerPackages() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> packages = new TreeSet<>();
        scanner.findCandidateComponents(APPLICATION_ROOT_PACKAGE).forEach(definition -> {
            String className = definition.getBeanClassName();
            if (className != null && className.contains(".")) {
                packages.add(className.substring(0, className.lastIndexOf('.')));
            }
        });
        return packages;
    }

    private void assertAdviceCovers(Class<?> adviceType, Set<String> controllerPackages) {
        RestControllerAdvice advice = adviceType.getAnnotation(RestControllerAdvice.class);
        assertThat(advice).isNotNull();
        Set<String> basePackages = new TreeSet<>(Arrays.asList(advice.basePackages()));
        assertThat(basePackages).isNotEmpty();
        for (String controllerPackage : controllerPackages) {
            boolean covered = basePackages.stream().anyMatch(basePackage ->
                    controllerPackage.equals(basePackage) || controllerPackage.startsWith(basePackage + "."));
            assertThat(covered)
                    .as("%s must cover REST controller package %s", adviceType.getSimpleName(), controllerPackage)
                    .isTrue();
        }
    }

    @RestController
    private static final class InvalidRunController {
        @PostMapping("/v1/chat/runs")
        void startRun() {
            throw new IllegalArgumentException("sessionId must not be empty");
        }
    }
}
