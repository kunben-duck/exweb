package com.huawei.finance.front.one.bootstrap;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * FinanceEXChatService Spring Boot 启动类。
 *
 * <p>MapperScan 显式列出承载 Mapper 的基础设施包。Mapper 类与对应 Repository 实现放在同一业务
 * 基础设施包内，避免为了 MyBatis 技术细节再创建额外深层目录。</p>
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.huawei.finance.front.one")
@MapperScan(basePackages = {
        "com.huawei.finance.front.one.infrastructure.memory",
        "com.huawei.finance.front.one.infrastructure.persistence",
        "com.huawei.finance.front.one.infrastructure.runtime",
        "com.huawei.finance.front.one.infrastructure.session",
        "com.huawei.finance.front.one.infrastructure.storage"
})
public class FinanceEXChatServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FinanceEXChatServiceApplication.class, args);
    }
}
