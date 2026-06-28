package com.huawei.finance.front.one.architecture;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

class SpringBeanConstructorSelectionTest {

    @Test
    void springBeansWithMultipleConstructorsMustDeclareInjectionConstructor() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class, true, true));

        List<String> violations = new ArrayList<>();
        scanner.findCandidateComponents("com.huawei.finance.front.one")
                .forEach(definition -> inspectBeanClass(definition.getBeanClassName(), violations));

        assertThat(violations)
                .as("Spring beans with multiple declared constructors must mark exactly one constructor with @Autowired")
                .isEmpty();
    }

    private void inspectBeanClass(String className, List<String> violations) {
        try {
            Class<?> beanClass = Class.forName(className);
            Constructor<?>[] constructors = beanClass.getDeclaredConstructors();
            if (constructors.length <= 1) {
                return;
            }
            long autowiredCount = Arrays.stream(constructors)
                    .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                    .count();
            if (autowiredCount != 1) {
                violations.add(className + " constructors=" + constructors.length
                        + ", autowiredConstructors=" + autowiredCount);
            }
        } catch (ClassNotFoundException ex) {
            violations.add(className + " could not be loaded: " + ex.getMessage());
        }
    }
}
