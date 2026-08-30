/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class SpringBeanConstructorSelectionTest {

    @Test
    void springBeansWithMultipleConstructorsMustDeclareInjectionConstructor() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class, true, true));

        List<String> violations = new ArrayList<>();
        scanner.findCandidateComponents("com.huawei.it.ex.one")
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
