# Sentinel 配置说明文档

本文档详细说明 Sentinel 系统的所有配置项及其用途。

## 目录

- [配置文件说明](#配置文件说明)
- [环境变量配置](#环境变量配置)
- [配置项详解](#配置项详解)
- [多环境配置](#多环境配置)
- [安全最佳实践](#安全最佳实践)

## 配置文件说明

Sentinel 使用 Spring Boot 的多环境配置机制，支持以下配置文件：

| 文件名 | 用途 | 优先级 |
|--------|------|--------|
| `application.yml` | 通用配置和默认值 | 低 |
| `application-dev.yml` | 开发环境配置 | 中 |
| `application-test.yml` | 测试环境配置 | 中 |
| `application-prod.yml` | 生产环境配置 | 中 |
| `application-local.yml` | 本地覆盖配置（不纳入版本控制） | 高 |
| 环境变量 | 运行时配置 | 最高 |

## 环境变量配置

### 必需的环境变量（生产环境）

```bash
# 数据库配置
DB_HOST=your-db-host
DB_PASSWORD=your-db-password

# Redis 配置
REDIS_HOST=your-redis-host
REDIS_PASSWORD=your-redis-password

# JWT 密钥（至少32位）
JWT_SECRET=your-super-secret-jwt-key-min-32-chars

# AI 服务配置（根据选择的提供商）
AI_PROVIDER=openai
OPENAI_API_KEY=your-openai-api-key
```

### 可选的环境变量

查看 `.env.example` 文件获取完整的环境变量列表。

## 配置项详解

### 1. 数据库配置

```yaml
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST}:${DB_PORT}/sentinel
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 50  # 最大连接数
      minimum-idle: 20       # 最小空闲连接数
```

**说明**：
- `maximum-pool-size`: 根据应用负载调整，建议值：10-100
- `minimum-idle`: 保持的最小空闲连接数，建议为 max 的 20%-40%

### 2. Redis 配置

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD}
      database: 0
      timeout: 5000ms
```

**说明**：
- `database`: Redis 数据库索引（0-15）
- `timeout`: 连接超时时间，建议 3000-10000ms

### 3. JWT 配置

```yaml
jwt:
  secret: ${JWT_SECRET}
  expiration: 3600000      # 1小时（毫秒）
  refresh-expiration: 86400000  # 1天（毫秒）
```

**说明**：
- `secret`: **必须使用强密钥**，至少32位，生产环境必须使用环境变量
- `expiration`: 访问令牌过期时间，建议 15分钟-2小时
- `refresh-expiration`: 刷新令牌过期时间，建议 1-7天

**安全建议**：
- 开发环境：1-24小时
- 测试环境：30分钟-12小时
- 生产环境：15分钟-1小时

### 4. 安全配置

```yaml
security:
  login-attempt:
    max-attempts: 5        # 最大登录尝试次数
    lock-time-minutes: 15  # 账户锁定时间（分钟）
  cors:
    allowed-origins:
      - https://your-domain.com
```

**说明**：
- `max-attempts`: 防止暴力破解，建议 3-10次
- `lock-time-minutes`: 锁定时间，建议 5-30分钟
- `allowed-origins`: CORS 允许的源，生产环境必须明确指定

### 5. 缓存配置

```yaml
cache:
  diagnosis-ttl: 86400     # AI 诊断缓存（秒）
  metrics-ttl: 300         # 系统指标缓存（秒）
  token-blacklist-ttl: 86400  # Token 黑名单缓存（秒）
  key-prefix: "sentinel:"  # 缓存键前缀
```

**说明**：
- `diagnosis-ttl`: AI 诊断结果缓存时间，建议 1小时-7天
- `metrics-ttl`: 系统指标缓存时间，建议 10秒-10分钟
- `token-blacklist-ttl`: 应与 JWT expiration 一致

### 6. AI 服务配置

#### 6.1 使用 Ollama（本地 AI）

```yaml
ai:
  provider: ollama
  ollama:
    base-url: http://localhost:11434
    model: qwen2.5:7b
    timeout: 120
```

**适用场景**：
- 开发环境
- 数据隐私要求高的场景
- 无需联网的环境

#### 6.2 使用 OpenAI（云端 AI）

```yaml
ai:
  provider: openai
  openai:
    base-url: https://api.openai.com/v1
    api-key: ${OPENAI_API_KEY}
    model: gpt-4
    timeout: 60
    max-tokens: 2000
```

**适用场景**：
- 生产环境
- 需要高质量 AI 响应
- 有稳定网络连接

#### 6.3 使用自定义 AI（OpenAI 兼容）

```yaml
ai:
  provider: custom
  custom:
    base-url: ${CUSTOM_AI_BASE_URL}
    api-key: ${CUSTOM_AI_API_KEY}
    model: ${CUSTOM_AI_MODEL}
```

**适用场景**：
- 使用企业内部 AI 服务
- 使用第三方 OpenAI 兼容 API

### 7. Docker 配置

```yaml
docker:
  host: unix:///var/run/docker.sock
  api-version: "1.41"
  tls-verify: false
  connection-timeout: 30
  response-timeout: 60
```

**说明**：
- `host`: Docker 主机地址
  - Unix Socket: `unix:///var/run/docker.sock`
  - TCP: `tcp://localhost:2375`
  - TLS: `tcp://localhost:2376`
- `tls-verify`: 生产环境建议启用
- `timeout`: 根据容器启动时间调整

### 8. 日志配置

```yaml
logging:
  level:
    root: INFO
    com.sentinel: DEBUG
  file:
    name: /var/log/sentinel/sentinel.log
    max-size: 500MB
    max-history: 90
```

**日志级别建议**：
- 开发环境: DEBUG
- 测试环境: INFO
- 生产环境: WARN

## 多环境配置

### 开发环境（dev）

```bash
# 启动命令
mvn spring-boot:run

# 或
java -jar sentinel.jar --spring.profiles.active=dev
```

**特点**：
- 使用本地数据库和 Redis
- 使用本地 Ollama AI 服务
- DEBUG 日志级别
- 较宽松的安全配置

### 测试环境（test）

```bash
export SPRING_PROFILES_ACTIVE=test
java -jar sentinel.jar
```

**特点**：
- 使用测试数据库和 Redis
- 可使用 OpenAI 或本地 AI
- INFO 日志级别
- 中等安全配置

### 生产环境（prod）

```bash
# 设置环境变量
export SPRING_PROFILES_ACTIVE=prod
export JWT_SECRET=your-production-secret
export DB_PASSWORD=your-db-password
export REDIS_PASSWORD=your-redis-password
export OPENAI_API_KEY=your-openai-key

# 启动应用
java -jar sentinel.jar
```

**特点**：
- 所有敏感配置使用环境变量
- 使用 OpenAI 或企业 AI 服务
- WARN 日志级别
- 严格的安全配置

## 安全最佳实践

### 1. 密钥管理

❌ **错误做法**：
```yaml
jwt:
  secret: "123456"  # 硬编码在配置文件中
```

✅ **正确做法**：
```yaml
jwt:
  secret: ${JWT_SECRET}  # 使用环境变量
```

```bash
# 生成强密钥
openssl rand -base64 32
```

### 2. 数据库密码

❌ **错误做法**：
```yaml
spring:
  datasource:
    password: "root"  # 明文密码
```

✅ **正确做法**：
```yaml
spring:
  datasource:
    password: ${DB_PASSWORD}  # 环境变量
```

### 3. API 密钥

❌ **错误做法**：
```yaml
openai:
  api-key: "sk-xxxxx"  # 硬编码
```

✅ **正确做法**：
```yaml
openai:
  api-key: ${OPENAI_API_KEY}  # 环境变量
```

### 4. 配置文件权限

```bash
# 限制配置文件权限
chmod 600 application-prod.yml
chmod 600 .env

# 确保敏感文件不被提交
echo ".env" >> .gitignore
echo "application-local.yml" >> .gitignore
```

### 5. 使用 Kubernetes Secrets

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: sentinel-secrets
type: Opaque
data:
  jwt-secret: <base64-encoded-secret>
  db-password: <base64-encoded-password>
```

## 配置验证

应用启动时会自动验证配置的正确性：

```
✅ 配置验证通过
❌ 配置验证失败: JWT 密钥不能为空
```

如果配置验证失败，应用将无法启动，并显示详细的错误信息。

## 故障排查

### 问题 1: 应用无法连接数据库

**检查清单**：
1. 数据库服务是否运行：`docker-compose ps`
2. 数据库连接信息是否正确
3. 网络是否可达：`telnet localhost 3306`
4. 用户名密码是否正确

### 问题 2: Redis 连接失败

**检查清单**：
1. Redis 服务是否运行
2. Redis 密码是否配置正确
3. 防火墙是否阻止连接

### 问题 3: JWT Token 验证失败

**检查清单**：
1. JWT 密钥是否一致
2. Token 是否过期
3. Token 格式是否正确（Bearer + Token）

## 参考资料

- [Spring Boot 配置文档](https://docs.spring.io/spring-boot/docs/current/reference/html/application-properties.html)
- [Spring Security 配置](https://docs.spring.io/spring-security/reference/index.html)
- [MyBatis-Plus 配置](https://baomidou.com/pages/56bac0/)
- [Docker Java Client](https://github.com/docker-java/docker-java)
