package com.huawei.finance.front.one.bootstrap;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.huawei.finance.front.one")
@MapperScan(basePackages = "com.huawei.finance.front.one.infrastructure.memory.mybatis")
public class FinanceEXChatServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FinanceEXChatServiceApplication.class, args);
    }
}
