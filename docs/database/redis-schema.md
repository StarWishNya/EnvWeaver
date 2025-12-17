# Redis 数据结构设计 (精简版 v2.0)

**版本：** v2.0  
**更新日期：** 2025-12-16  
**说明：** MVP 版本的 Redis 仅用于缓存和会话管理

---

## 一、设计原则

1. **简单优先** - 只存储必要的缓存数据
2. **TTL 必设** - 所有 Key 必须设置过期时间
3. **前缀规范** - 使用统一的 Key 前缀便于管理

---

## 二、Key 命名规范

```
{业务域}:{子域}:{标识}

示例：
- auth:token:1001
- task:status:12345
- diagnosis:cache:md5hash
```

---

## 三、数据结构设计

### 3.1 会话管理

#### JWT Token 存储
```redis
# 存储用户 Token（用于验证和登出）
SET auth:token:{userId} "{token}" EX 7200

# 示例
SET auth:token:1001 "eyJhbGciOiJIUzI1NiIs..." EX 7200
```

#### Token 黑名单（登出后失效）
```redis
# 使用 String + TTL（自动过期，无需清理）
SET auth:blacklist:{tokenHash} "1" EX 7200

# 示例：用户登出后将 Token 加入黑名单
SET auth:blacklist:a1b2c3d4 "1" EX 7200
```

---

### 3.2 任务状态缓存

#### 任务状态（热数据缓存）
```redis
# Hash 存储任务状态
HSET task:status:{taskId} status "RUNNING" startedAt "1702713600000"

# 设置过期时间
EXPIRE task:status:{taskId} 3600

# 示例
HSET task:status:12345 status "RUNNING" startedAt "1702713600000"
EXPIRE task:status:12345 3600
```

#### 任务队列
```redis
# 使用 List 作为任务队列（FIFO）
LPUSH task:queue "{taskId}:{priority}"
RPOP task:queue

# 示例
LPUSH task:queue "12345:5"
LPUSH task:queue "12346:8"  # 高优先级
```

---

### 3.3 环境状态缓存

#### 环境状态
```redis
# Hash 存储环境信息
HSET env:status:{envId} status "READY" taskId "12345" accessUrl "http://..."

# 设置过期时间
EXPIRE env:status:{envId} 1800

# 示例
HSET env:status:env-abc123 status "READY" taskId "12345" accessUrl "http://localhost:33061"
EXPIRE env:status:env-abc123 1800
```

#### 空闲环境池

> **设计说明：** 使用 Sorted Set 存储空闲环境，score 为加入时间戳，支持 TTL 过期清理。
> 避免因环境异常退出导致脏数据累积。

```redis
# 使用 Sorted Set 存储空闲环境（score = 加入时间戳）
ZADD env:pool:idle {timestamp} "env-001"

# 获取最早加入的空闲环境（FIFO）
ZRANGE env:pool:idle 0 0
ZREM env:pool:idle "env-001"

# 释放环境回池
ZADD env:pool:idle {current_timestamp} "env-001"

# 清理过期环境（超过 30 分钟未被使用的环境视为无效）
# 定时任务执行：删除 score < (当前时间戳 - 1800000) 的成员
ZREMRANGEBYSCORE env:pool:idle 0 {current_timestamp - 1800000}
```

**Java 实现示例：**
```java
// 获取空闲环境
public String popIdleEnvironment() {
    Set<String> envs = redisTemplate.opsForZSet()
        .range("env:pool:idle", 0, 0);
    if (envs != null && !envs.isEmpty()) {
        String envId = envs.iterator().next();
        redisTemplate.opsForZSet().remove("env:pool:idle", envId);
        return envId;
    }
    return null;
}

// 释放环境回池
public void releaseEnvironment(String envId) {
    redisTemplate.opsForZSet()
        .add("env:pool:idle", envId, System.currentTimeMillis());
}

// 清理过期环境（定时任务，每 5 分钟执行）
@Scheduled(fixedRate = 300000)
public void cleanupStaleEnvironments() {
    long threshold = System.currentTimeMillis() - 1800000; // 30分钟
    redisTemplate.opsForZSet()
        .removeRangeByScore("env:pool:idle", 0, threshold);
}
```

---

### 3.4 诊断缓存

> **缓存策略说明：** 诊断结果采用 **Redis 热缓存 + MySQL 持久化** 双层存储策略：
> - **Redis**：作为热缓存，存储最近 90 天的诊断结果，提供毫秒级查询响应
> - **MySQL (`t_diagnosis_cache` 表)**：作为持久化存储，保留完整的诊断历史记录
> - **查询流程**：先查 Redis，未命中则查 MySQL，查到后回写 Redis
> - **写入流程**：同时写入 Redis 和 MySQL，保证数据一致性

#### AI 诊断结果缓存
```redis
# Hash 存储诊断结果
HSET diagnosis:cache:{logHash} rootCause "端口被占用" solutions "[\"kill进程\",\"换端口\"]" hitCount "5"

# 设置 90 天过期
EXPIRE diagnosis:cache:{logHash} 7776000

# 示例
HSET diagnosis:cache:a1b2c3d4e5f6 rootCause "MySQL启动失败：端口3306被占用" solutions "[\"执行lsof -i:3306\",\"修改端口映射\"]" hitCount "1"
EXPIRE diagnosis:cache:a1b2c3d4e5f6 7776000
```

#### 缓存命中计数
```redis
# 每次命中时增加计数
HINCRBY diagnosis:cache:{logHash} hitCount 1
```

---

### 3.5 系统指标

#### 实时资源指标
```redis
# Hash 存储最新指标
HSET metrics:system cpu "45.5" memory "62.3" disk "38.0" containers "8"

# 设置短过期时间（指标需要频繁更新）
EXPIRE metrics:system 30

# 示例
HSET metrics:system cpu "45.5" memory "62.3" disk "38.0" containers "8"
EXPIRE metrics:system 30
```

#### 容器数量计数
```redis
# 使用 String 计数器
INCR metrics:containers:total
DECR metrics:containers:total

GET metrics:containers:total
```

---

### 3.6 分布式锁

#### 环境创建锁（防止并发创建）
```redis
# 使用 SET NX EX 实现分布式锁
SET lock:env:create:{taskId} "{instanceId}" NX EX 60

# 释放锁（需要验证持有者）
# 使用 Lua 脚本保证原子性
```

#### 调度锁（单实例调度）
```redis
SET lock:scheduler "instance-1" NX EX 30
```

---

## 四、Key 清单汇总

| Key Pattern | 类型 | TTL | 说明 |
|-------------|------|-----|------|
| `auth:token:{userId}` | String | 2h | 用户 Token |
| `auth:blacklist:{tokenHash}` | String | 2h | Token 黑名单 |
| `task:status:{taskId}` | Hash | 1h | 任务状态缓存 |
| `task:queue` | List | - | 任务队列 |
| `env:status:{envId}` | Hash | 30min | 环境状态缓存 |
| `env:pool:idle` | Sorted Set | 30min (定时清理) | 空闲环境池 |
| `diagnosis:cache:{logHash}` | Hash | 90d | 诊断结果缓存 |
| `metrics:system` | Hash | 30s | 系统指标 |
| `metrics:containers:total` | String | - | 容器计数 |
| `lock:env:create:{taskId}` | String | 60s | 环境创建锁 |
| `lock:scheduler` | String | 30s | 调度锁 |

---

## 五、运维命令

### 5.1 查看 Key 统计
```bash
# 统计各类 Key 数量
redis-cli --scan --pattern "task:*" | wc -l
redis-cli --scan --pattern "env:*" | wc -l
redis-cli --scan --pattern "diagnosis:*" | wc -l
```

### 5.2 清理过期数据
```bash
# Redis 会自动清理过期 Key，无需手动操作
# 如需强制清理特定前缀的 Key：
redis-cli --scan --pattern "task:status:*" | xargs redis-cli DEL
```

### 5.3 监控内存使用
```bash
redis-cli INFO memory
redis-cli MEMORY DOCTOR
```

---

## 六、Spring 配置示例

```yaml
# application.yml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 3000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 2
```

```java
// RedisConfig.java
@Configuration
public class RedisConfig {
    
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}
```
