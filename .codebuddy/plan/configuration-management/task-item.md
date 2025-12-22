# 实施计划 - 配置管理优化

## 任务清单

- [ ] 1. 创建配置属性类
   - [ ] 1.1 创建 JwtProperties 配置类
      - 使用 @ConfigurationProperties(prefix = "jwt") 注解
      - 定义 secret、expiration、refreshExpiration 属性
      - 添加 JSR-303 验证注解（@NotBlank、@Min）
      - _需求：2.1, 2.2, 2.3_
   
   - [ ] 1.2 创建 SecurityProperties 配置类
      - 使用 @ConfigurationProperties(prefix = "security") 注解
      - 定义 LoginAttempt 内部类（maxAttempts、lockTimeMinutes）
      - 定义 CORS 配置（allowedOrigins、allowedMethods）
      - _需求：2.1, 2.2, 2.4_
   
   - [ ] 1.3 创建 CacheProperties 配置类
      - 使用 @ConfigurationProperties(prefix = "cache") 注解
      - 定义 diagnosisTtl、metricsTtl、tokenBlacklistTtl 属性
      - 定义 keyPrefix 属性
      - _需求：2.1, 2.2, 2.3_
   
   - [ ] 1.4 创建 AIProperties 配置类（优化现有）
      - 使用 @ConfigurationProperties(prefix = "ai") 注解
      - 定义 provider 枚举属性
      - 定义 Ollama、OpenAI、Custom 内部配置类
      - 添加验证注解和默认值
      - _需求：2.1, 2.2, 2.3, 2.4_
   
   - [ ] 1.5 创建 DockerProperties 配置类
      - 使用 @ConfigurationProperties(prefix = "docker") 注解
      - 定义 host、apiVersion、tlsVerify、timeout 属性
      - 添加验证注解
      - _需求：2.1, 2.2, 2.3_

- [ ] 2. 重构现有代码以使用配置属性
   - [ ] 2.1 重构 JwtUtil 类
      - 移除 @Value 注解，改用 JwtProperties 构造函数注入
      - 移除硬编码的配置值
      - 使用 jwtProperties.getSecret() 等方法获取配置
      - _需求：4.1, 4.2, 4.3_
   
   - [ ] 2.2 重构 LoginAttemptService 类
      - 移除硬编码的 MAX_ATTEMPTS 和 LOCK_TIME_MINUTES 常量
      - 注入 SecurityProperties 并使用配置值
      - _需求：4.1, 4.2, 4.3_
   
   - [ ] 2.3 重构 CacheService 类
      - 移除硬编码的 TTL 时间和键前缀
      - 注入 CacheProperties 并使用配置值
      - 更新所有缓存方法使用配置的 TTL
      - _需求：4.1, 4.2, 4.3_
   
   - [ ] 2.4 重构 AI 客户端类
      - 重构 OllamaAIClient 使用 AIProperties
      - 重构 OpenAIClient 使用 AIProperties
      - 移除所有硬编码的 URL、模型名称、超时时间
      - _需求：4.1, 4.2, 4.3_
   
   - [ ] 2.5 重构 DockerConfig 类
      - 注入 DockerProperties
      - 使用配置属性创建 DockerClient
      - 移除硬编码的 Docker 主机地址和 API 版本
      - _需求：4.1, 4.2, 4.3_

- [ ] 3. 更新多环境配置文件
   - [ ] 3.1 更新 application.yml（通用配置）
      - 添加所有配置项的默认值和详细注释
      - 定义配置项的用途、可选值、示例
      - 设置 spring.profiles.active 默认为 dev
      - _需求：3.1, 5.4_
   
   - [ ] 3.2 更新 application-dev.yml（开发环境）
      - 配置本地数据库连接（localhost:3306）
      - 配置本地 Redis（localhost:6379）
      - 配置本地 Ollama AI 服务
      - 设置 DEBUG 日志级别
      - 配置开发用 JWT 密钥
      - 设置较短的缓存 TTL
      - _需求：3.2, 5.4_
   
   - [ ] 3.3 更新 application-test.yml（测试环境）
      - 配置测试数据库连接
      - 配置测试 Redis 连接
      - 配置测试 AI 服务
      - 设置 INFO 日志级别
      - 配置测试用 JWT 密钥
      - _需求：3.3, 5.4_
   
   - [ ] 3.4 更新 application-prod.yml（生产环境）
      - 使用环境变量配置数据库连接
      - 使用环境变量配置 Redis 连接
      - 配置生产 AI 服务（OpenAI）
      - 设置 WARN 日志级别
      - 使用环境变量配置 JWT 密钥
      - 设置较长的缓存 TTL
      - _需求：3.4, 3.5, 5.4_

- [ ] 4. 实现配置验证和安全管理
   - [ ] 4.1 启用配置属性验证
      - 在主启动类添加 @EnableConfigurationProperties 注解
      - 在 pom.xml 添加 spring-boot-configuration-processor 依赖
      - 确保所有配置属性类都有 @Validated 注解
      - _需求：5.1, 5.2, 5.3_
   
   - [ ] 4.2 创建 .env.example 文件
      - 列出所有需要的环境变量
      - 提供示例值和说明
      - 标注必需和可选的环境变量
      - _需求：6.3_
   
   - [ ] 4.3 更新 .gitignore 文件
      - 添加 .env 文件
      - 添加 application-local.yml 文件
      - 添加任何包含真实密钥的配置文件
      - _需求：6.4_
   
   - [ ] 4.4 实现配置日志脱敏
      - 创建 LoggingFilter 或使用 Logback 配置
      - 自动脱敏密码、密钥、Token 等敏感字段
      - _需求：6.5_

- [ ] 5. 创建 Docker Compose 配置文件
   - [ ] 5.1 创建 docker-compose.yml
      - 配置 MySQL 8.0 服务（端口 3306）
      - 配置 Redis 7.0 服务（端口 6379）
      - 配置数据卷持久化（mysql-data、redis-data）
      - 配置网络（sentinel-network）
      - _需求：3.1_
   
   - [ ] 5.2 创建 MySQL 配置文件
      - 创建 docker/mysql/my.cnf 配置文件
      - 配置字符集、时区、连接数等参数
      - _需求：3.1_
   
   - [ ] 5.3 创建 Redis 配置文件
      - 创建 docker/redis/redis.conf 配置文件
      - 配置持久化、内存限制等参数
      - _需求：3.1_
   
   - [ ] 5.4 创建 .env 模板文件
      - 定义 MySQL 环境变量（MYSQL_ROOT_PASSWORD 等）
      - 定义 Redis 环境变量（REDIS_PASSWORD 等）
      - 提供默认值和说明
      - _需求：6.3_

- [ ] 6. 更新启动脚本和文档
   - [ ] 6.1 创建 start-dev.sh 启动脚本
      - 启动 Docker Compose 服务
      - 等待服务就绪
      - 启动 Spring Boot 应用（dev 环境）
      - _需求：7.4_
   
   - [ ] 6.2 创建 stop-dev.sh 停止脚本
      - 停止 Spring Boot 应用
      - 停止 Docker Compose 服务
      - _需求：7.4_
   
   - [ ] 6.3 更新 DEPLOYMENT.md 文档
      - 添加"配置说明"章节
      - 列出所有配置项及其说明
      - 提供环境变量配置示例
      - 添加多环境部署指南
      - _需求：5.5_
   
   - [ ] 6.4 更新 README.md 文档
      - 更新快速开始章节，说明如何使用 Docker Compose
      - 添加配置说明链接
      - 更新环境要求
      - _需求：5.5_

- [ ] 7. 实现配置热更新支持（可选）
   - [ ] 7.1 添加 Spring Cloud Config 依赖
      - 在 pom.xml 添加 spring-cloud-starter-config 依赖
      - 配置 bootstrap.yml 文件
      - _需求：8.1_
   
   - [ ] 7.2 标记支持热更新的 Bean
      - 为 CacheService 等类添加 @RefreshScope 注解
      - 在配置文档中标注支持热更新的配置项
      - _需求：8.1, 8.3_
   
   - [ ] 7.3 配置 Actuator 刷新端点
      - 启用 /actuator/refresh 端点
      - 配置端点安全策略
      - 实现配置变更日志记录
      - _需求：8.2, 8.4_

- [ ] 8. 编写配置相关测试
   - [ ] 8.1 编写配置属性类单元测试
      - 测试配置属性的加载和验证
      - 测试默认值是否正确
      - 测试验证注解是否生效
      - _需求：5.1, 5.2_
   
   - [ ] 8.2 编写多环境配置集成测试
      - 测试 dev 环境配置加载
      - 测试 test 环境配置加载
      - 测试 prod 环境配置加载（使用 Mock 环境变量）
      - _需求：3.1, 3.2, 3.3, 3.4_
   
   - [ ] 8.3 编写配置验证失败测试
      - 测试缺失必需配置时的异常
      - 测试配置格式错误时的异常
      - 验证异常信息是否清晰
      - _需求：5.2_

---

## 任务依赖关系

- 任务 1（创建配置属性类）是所有其他任务的前置条件
- 任务 2（重构代码）依赖任务 1
- 任务 3（更新配置文件）依赖任务 1
- 任务 4（配置验证和安全）依赖任务 1、2、3
- 任务 5（Docker Compose）可以与任务 1-4 并行
- 任务 6（文档更新）依赖任务 1-5
- 任务 7（热更新）是可选任务，依赖任务 1-4
- 任务 8（测试）依赖所有功能任务完成

---

## 技术要点提醒

1. **@ConfigurationProperties 使用**：需要在 pom.xml 添加 spring-boot-configuration-processor 依赖以支持 IDE 自动补全
2. **环境变量优先级**：环境变量 > application-{profile}.yml > application.yml
3. **敏感信息处理**：生产环境的密码、密钥必须使用环境变量，不能硬编码在配置文件中
4. **配置验证**：使用 JSR-303 验证注解确保配置的正确性
5. **Docker Compose**：使用 .env 文件管理环境变量，不要将真实密码提交到版本控制
6. **日志脱敏**：确保日志中不输出敏感信息的明文
7. **配置文档**：配置文件中的注释要清晰，说明每个配置项的用途和可选值
