package com.sentinel;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 测试基类
 * 提供通用的测试配置和环境设置
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional  // 每个测试方法执行后自动回滚
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "logging.level.com.sentinel=DEBUG"
})
public abstract class BaseTest {

    @BeforeEach
    public void setUp() {
        // 子类可以覆盖此方法进行额外的初始化
    }

    /**
     * 等待一段时间（用于异步操作测试）
     */
    protected void waitFor(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
