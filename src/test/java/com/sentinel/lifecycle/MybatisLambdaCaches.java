package com.sentinel.lifecycle;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 单元测试里没有 Mapper 扫描，Lambda 包装器需要先注册实体元数据。
 */
final class MybatisLambdaCaches {

    private MybatisLambdaCaches() {
    }

    static void install(Class<?>... types) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        for (Class<?> type : types) {
            TableInfoHelper.initTableInfo(assistant, type);
        }
    }

    /**
     * WHERE 参数要等 SQL 片段生成后才写入 paramNameValuePairs。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static List<Object> values(Wrapper<?> wrapper) {
        if (!(wrapper instanceof AbstractWrapper raw)) {
            return List.of();
        }
        raw.getSqlSegment();
        raw.getSqlSet();
        List<Object> values = new ArrayList<>();
        Collection<?> stored = raw.getParamNameValuePairs().values();
        for (Object value : stored) {
            if (value instanceof Collection<?> nested) {
                values.addAll(nested);
            } else {
                values.add(value);
            }
        }
        return values;
    }
}
