# 配置管理优化需求文档

## 引言

本需求旨在优化 Sentinel 项目的配置管理，消除代码中的硬编码配置，将所有可配置项提取到配置文件中，并为开发（dev）、测试（test）、生产（prod）三个环境提供独立的配置文件。这将提高系统的可维护性、灵活性和安全性，使得不同环境的部署更加便捷。

## 需求

### 需求 1：识别并提取硬编码配置

**用户故事：** 作为一名开发人员，我希望识别项目中所有的硬编码配置项，以便将它们提取到配置文件中进行统一管理。

#### 验收标准

1. WHEN 审查项目代码 THEN 系统 SHALL 识别出所有硬编码的配置值，包括但不限于：
   - 数据库连接参数（URL、用户名、密码、连接池配置）
   - Redis 连接参数（主机、端口、密码、超时时间）
   - JWT 配置（密钥、过期时间、刷新令牌过期时间）
   - AI 服务配置（提供商、API 地址、API 密钥、模型名称、超时时间）
   - Docker 配置（主机地址、API 版本、TLS 验证）
   - 登录安全配置（最大尝试次数、锁定时间）
   - 缓存配置（TTL 时间、键前缀）
   - 日志配置（日志级别、日志文件路径）
   - 服务器配置（端口、上下文路径）

2. WHEN 识别硬编码配置 THEN 系统 SHALL 记录每个配置项的位置、用途和默认值

3. IF 配置项涉及敏感信息（如密码、密钥） THEN 系统 SHALL 标记为需要环境变量或加密处理

### 需求 2：创建统一的配置属性类

**用户故事：** 作为一名开发人员，我希望创建类型安全的配置属性类，以便通过 Spring Boot 的 @ConfigurationProperties 机制管理配置。

#### 验收标准

1. WHEN 创建配置属性类 THEN 系统 SHALL 为每个功能模块创建独立的配置类：
   - DatabaseProperties（数据库配置）
   - RedisProperties（Redis 配置）
   - JwtProperties（JWT 配置）
   - AIProperties（AI 服务配置，已存在需优化）
   - DockerProperties（Docker 配置）
   - SecurityProperties（安全配置）
   - CacheProperties（缓存配置）

2. WHEN 定义配置属性类 THEN 系统 SHALL 使用 @ConfigurationProperties 注解并指定前缀

3. WHEN 定义配置属性 THEN 系统 SHALL 提供合理的默认值和 JSR-303 验证注解

4. IF 配置项为嵌套结构 THEN 系统 SHALL 使用内部静态类组织配置

### 需求 3：实现多环境配置文件

**用户故事：** 作为一名运维人员，我希望为开发、测试、生产环境提供独立的配置文件，以便在不同环境中使用不同的配置参数。

#### 验收标准

1. WHEN 配置多环境 THEN 系统 SHALL 创建以下配置文件：
   - application.yml（通用配置和默认值）
   - application-dev.yml（开发环境配置）
   - application-test.yml（测试环境配置）
   - application-prod.yml（生产环境配置）

2. WHEN 在开发环境 THEN 系统 SHALL 配置：
   - 本地数据库连接（localhost:3306）
   - 本地 Redis 连接（localhost:6379）
   - 本地 Ollama AI 服务
   - DEBUG 日志级别
   - 开发用的 JWT 密钥
   - 较短的缓存 TTL

3. WHEN 在测试环境 THEN 系统 SHALL 配置：
   - 测试数据库连接
   - 测试 Redis 连接
   - 测试 AI 服务（可使用 Mock）
   - INFO 日志级别
   - 测试用的 JWT 密钥
   - 中等的缓存 TTL

4. WHEN 在生产环境 THEN 系统 SHALL 配置：
   - 生产数据库连接（使用环境变量）
   - 生产 Redis 连接（使用环境变量）
   - 生产 AI 服务（OpenAI 或企业 API）
   - WARN 日志级别
   - 强安全性的 JWT 密钥（必须使用环境变量）
   - 较长的缓存 TTL

5. IF 配置项为敏感信息 THEN 系统 SHALL 使用 ${ENV_VAR:default} 格式引用环境变量

### 需求 4：重构代码以使用配置属性

**用户故事：** 作为一名开发人员，我希望重构现有代码以使用配置属性类，以便消除所有硬编码配置。

#### 验收标准

1. WHEN 重构服务类 THEN 系统 SHALL 通过构造函数注入配置属性类

2. WHEN 访问配置值 THEN 系统 SHALL 使用配置属性类的 getter 方法，而不是直接使用字面量

3. WHEN 重构完成 THEN 系统 SHALL 确保以下类不再包含硬编码配置：
   - JwtUtil
   - LoginAttemptService
   - CacheService
   - OllamaAIClient
   - OpenAIClient
   - DockerConfig
   - SecurityConfig
   - RedisConfig
   - MybatisPlusConfig

4. IF 配置值需要计算或转换 THEN 系统 SHALL 在配置属性类中提供辅助方法

### 需求 5：实现配置验证和文档

**用户故事：** 作为一名运维人员，我希望系统在启动时验证配置的正确性，并提供清晰的配置文档，以便快速定位配置问题。

#### 验收标准

1. WHEN 应用启动 THEN 系统 SHALL 验证所有必需的配置项是否已设置

2. WHEN 配置验证失败 THEN 系统 SHALL 抛出清晰的异常信息，指明缺失或错误的配置项

3. WHEN 使用 JSR-303 验证 THEN 系统 SHALL 为配置属性添加验证注解：
   - @NotNull（必需配置）
   - @NotBlank（非空字符串）
   - @Min/@Max（数值范围）
   - @Pattern（格式验证）
   - @Email（邮箱格式）

4. WHEN 创建配置文档 THEN 系统 SHALL 在每个配置文件中添加详细的注释说明：
   - 配置项的用途
   - 可选值或范围
   - 默认值
   - 示例值

5. WHEN 创建配置文档 THEN 系统 SHALL 更新 DEPLOYMENT.md 文档，添加完整的配置说明章节

### 需求 6：实现敏感配置的安全管理

**用户故事：** 作为一名安全工程师，我希望敏感配置（如密码、密钥）能够安全存储和使用，以便防止配置泄露。

#### 验收标准

1. WHEN 处理敏感配置 THEN 系统 SHALL 支持以下方式：
   - 环境变量
   - 外部配置文件（不纳入版本控制）
   - Spring Cloud Config Server（可选）
   - Kubernetes Secrets（可选）

2. WHEN 在配置文件中引用敏感信息 THEN 系统 SHALL 使用占位符格式：`${ENV_VAR_NAME}`

3. WHEN 提供配置示例 THEN 系统 SHALL 创建 `.env.example` 文件，包含所有环境变量的示例

4. WHEN 版本控制 THEN 系统 SHALL 确保 `.gitignore` 包含：
   - `.env`
   - `application-local.yml`
   - 任何包含真实密钥的配置文件

5. IF 在日志中输出配置信息 THEN 系统 SHALL 自动脱敏敏感字段（密码、密钥等）

### 需求 7：提供配置切换和覆盖机制

**用户故事：** 作为一名开发人员，我希望能够灵活地切换环境配置和覆盖特定配置项，以便在不同场景下快速调整配置。

#### 验收标准

1. WHEN 启动应用 THEN 系统 SHALL 支持通过以下方式指定环境：
   - 命令行参数：`--spring.profiles.active=dev`
   - 环境变量：`SPRING_PROFILES_ACTIVE=dev`
   - IDE 配置

2. WHEN 需要本地覆盖配置 THEN 系统 SHALL 支持 `application-local.yml` 文件（优先级最高，不纳入版本控制）

3. WHEN 配置加载 THEN 系统 SHALL 遵循以下优先级（从高到低）：
   - 命令行参数
   - 环境变量
   - application-local.yml
   - application-{profile}.yml
   - application.yml

4. WHEN 提供启动脚本 THEN 系统 SHALL 更新 start-dev.sh 等脚本，支持环境变量配置

### 需求 8：实现配置热更新支持（可选）

**用户故事：** 作为一名运维人员，我希望某些非关键配置能够在运行时更新，以便无需重启应用即可调整配置。

#### 验收标准

1. WHEN 使用 @RefreshScope THEN 系统 SHALL 标记支持热更新的 Bean

2. WHEN 配置更新 THEN 系统 SHALL 支持通过 Actuator 端点触发配置刷新

3. IF 配置项支持热更新 THEN 系统 SHALL 在配置文档中明确标注

4. WHEN 热更新配置 THEN 系统 SHALL 记录配置变更日志

## 配置项清单

### 数据库配置
- 连接 URL
- 用户名
- 密码
- 驱动类名
- 连接池配置（最大连接数、最小空闲连接、连接超时等）

### Redis 配置
- 主机地址
- 端口
- 密码
- 数据库索引
- 连接超时
- 连接池配置

### JWT 配置
- 密钥（secret）
- 访问令牌过期时间
- 刷新令牌过期时间
- 发行者（issuer）
- 受众（audience）

### AI 服务配置
- 提供商类型（ollama/openai/custom）
- Ollama 配置（base-url, model, timeout）
- OpenAI 配置（base-url, api-key, model, timeout）
- 自定义 AI 配置（base-url, api-key, model, timeout）

### Docker 配置
- Docker 主机地址
- API 版本
- TLS 验证
- 连接超时
- 响应超时

### 安全配置
- 登录最大尝试次数
- 账户锁定时间
- 密码加密强度
- CORS 配置
- 白名单路径

### 缓存配置
- 诊断缓存 TTL
- 指标缓存 TTL
- Token 黑名单 TTL
- 缓存键前缀

### 日志配置
- 日志级别
- 日志文件路径
- 日志文件大小
- 日志保留天数
- 日志格式

### 服务器配置
- 服务器端口
- 上下文路径
- 会话超时
- 最大请求大小

## 非功能性需求

### 性能要求
- 配置加载时间不应超过 2 秒
- 配置验证不应显著增加启动时间

### 安全要求
- 敏感配置必须加密或使用环境变量
- 配置文件权限应限制为只读
- 日志中不得输出敏感配置的明文

### 可维护性要求
- 配置项应有清晰的命名和注释
- 配置文档应与代码同步更新
- 配置变更应有版本记录

### 兼容性要求
- 支持 Spring Boot 3.2+ 的配置机制
- 兼容 Docker、Kubernetes 等容器化部署
- 支持传统虚拟机部署方式
