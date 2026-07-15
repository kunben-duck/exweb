package com.huawei.it.ex.one.architecture;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

/**
 * 主配置文件语法护栏，避免无效 YAML 直到部署启动时才被发现。
 */
class ApplicationYamlSyntaxTest {
    @Test
    void applicationYamlShouldBeParseableBySpringBoot() {
        FileSystemResource resource = new FileSystemResource("src/main/resources/application.yml");

        assertThatCode(() -> new YamlPropertySourceLoader().load("application", resource))
                .doesNotThrowAnyException();
    }
}
