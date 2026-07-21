package com.huawei.it.ex.one.application.integration.security;

import com.huawei.it.ex.one.domain.auth.UserContext;
import reactor.core.publisher.Mono;

/**
 * Resolves an employee's registered country through a trusted enterprise source.
 */
public interface EmployeeLocationProvider {
    Mono<RegionalLocationResult> findCountry(UserContext user, String employeeNumber);
}
