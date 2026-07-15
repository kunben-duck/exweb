package com.huawei.it.ex.one.interfaces.document.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.PostMapping;
class DocumentUploadControllerCompatibilityTest {
    @Test
    void servletUploadControllerIsOnlyRegisteredForMvcRuntime() {
        ConditionalOnWebApplication condition = MvcDocumentUploadController.class
                .getAnnotation(ConditionalOnWebApplication.class);

        assertThat(condition).isNotNull();
        assertThat(condition.type()).isEqualTo(ConditionalOnWebApplication.Type.SERVLET);
        assertThat(uploadMethod(MvcDocumentUploadController.class)).isNotNull();
    }

    @Test
    void reactiveUploadControllerIsOnlyRegisteredForWebFluxRuntime() {
        ConditionalOnWebApplication condition = ReactiveDocumentUploadController.class
                .getAnnotation(ConditionalOnWebApplication.class);

        assertThat(condition).isNotNull();
        assertThat(condition.type()).isEqualTo(ConditionalOnWebApplication.Type.REACTIVE);
        assertThat(uploadMethod(ReactiveDocumentUploadController.class)).isNotNull();
    }

    private Method uploadMethod(Class<?> controllerType) {
        for (Method method : controllerType.getDeclaredMethods()) {
            if (method.getAnnotation(PostMapping.class) != null) {
                return method;
            }
        }
        return null;
    }
}
