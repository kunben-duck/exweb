package com.huawei.it.ex.one.architecture;

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
    private static final int MAX_CONSTRUCTOR_DEPENDENCIES = 8;

    @Test
    void springBeansWithMultipleConstructorsMustDeclareInjectionConstructor() {
        List<String> violations = new ArrayList<>();
        componentScanner().findCandidateComponents("com.huawei.it.ex.one")
                .forEach(definition -> inspectBeanClass(definition.getBeanClassName(), violations));

        assertThat(violations)
                .as("Spring beans with multiple declared constructors must mark exactly one constructor with @Autowired")
                .isEmpty();
    }

    @Test
    void springBeanInjectionConstructorsShouldHaveAtMostEightDependencies() {
        List<String> violations = new ArrayList<>();
        componentScanner().findCandidateComponents("com.huawei.it.ex.one")
                .forEach(definition -> inspectDependencyCount(definition.getBeanClassName(), violations));

        assertThat(violations)
                .as("Spring bean injection constructors should have at most %s dependencies",
                        MAX_CONSTRUCTOR_DEPENDENCIES)
                .isEmpty();
    }

    private ClassPathScanningCandidateComponentProvider componentScanner() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class, true, true));
        return scanner;
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

    private void inspectDependencyCount(String className, List<String> violations) {
        try {
            Class<?> beanClass = Class.forName(className);
            Constructor<?> constructor = injectionConstructor(beanClass.getDeclaredConstructors());
            if (constructor != null && constructor.getParameterCount() > MAX_CONSTRUCTOR_DEPENDENCIES) {
                violations.add(className + " dependencies=" + constructor.getParameterCount());
            }
        } catch (ClassNotFoundException ex) {
            violations.add(className + " could not be loaded: " + ex.getMessage());
        }
    }

    private Constructor<?> injectionConstructor(Constructor<?>[] constructors) {
        return Arrays.stream(constructors)
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .findFirst()
                .orElseGet(() -> constructors.length == 1 ? constructors[0] : null);
    }
}
