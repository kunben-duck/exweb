package com.huawei.it.ex.one.bootstrap;

import org.mybatis.spring.annotation.MapperScan;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * FinanceEXChatService Spring Boot 启动类。
 *
 * <p>MapperScan 显式列出承载 Mapper 接口的基础设施包；具体 SQL 统一维护在
 * {@code src/main/resources/mapper} 下的 XML 文件中，Java 接口只保留方法签名。</p>
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.huawei.it.ex.one")
@MapperScan(annotationClass = Mapper.class, basePackages = {
        "com.huawei.it.ex.one.infrastructure.memory",
        "com.huawei.it.ex.one.infrastructure.persistence",
        "com.huawei.it.ex.one.infrastructure.runtime",
        "com.huawei.it.ex.one.infrastructure.session",
        "com.huawei.it.ex.one.infrastructure.storage"
})
public class FinanceEXChatServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FinanceEXChatServiceApplication.class, args);
    }
}
