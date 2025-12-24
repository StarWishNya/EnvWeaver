package com.sentinel.testutil;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 测试工具类
 * 提供通用的测试辅助方法
 */
public class TestUtils {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /**
     * 将对象转换为 JSON 字符串
     */
    public static String toJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert object to JSON", e);
        }
    }

    /**
     * 将 JSON 字符串转换为对象
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert JSON to object", e);
        }
    }

    /**
     * 生成唯一的测试ID
     */
    public static String generateTestId() {
        return "TEST-" + System.currentTimeMillis();
    }

    /**
     * 生成唯一的服务ID
     */
    public static String generateServiceId() {
        return "service-" + System.currentTimeMillis();
    }
}
