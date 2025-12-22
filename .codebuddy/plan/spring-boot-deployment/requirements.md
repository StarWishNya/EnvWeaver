# 需求文档 - Sentinel 后端服务 Spring Boot 部署

## 引言

本需求文档旨在将 Sentinel 智能测试环境管理系统从设计阶段推进到实现阶段,使用最新的 Spring Boot 3.x 框架构建完整的后端服务。

### 项目背景

Sentinel 是一个基于智能体架构的测试环境自动化管理系统,采用"感知-决策-执行"的设计模式,能够:
- 自动感知 CI 系统的测试任务请求
- 智能决策环境创建、复用或排队策略
- 自动执行 Docker 容器的生命周期管理
- 利用 AI 进行故障诊断和根因分析

### 技术栈概览

- **框架**: Spring Boot 3.2+ (最新稳定版)
- **持久层**: MyBatis-Plus 3.5+ + MySQL 8.0
- **缓存**: Spring Data Redis + Redis 7.0
- **容器管理**: Docker Java Client
- **AI 集成**: 支持本地 Ollama API 或 OpenAI 兼容 API (可配置)
- **安全**: Spring Security + JWT
- **监控**: Spring Boot Actuator + Prometheus
- **前端**: Thymeleaf + HTMX (轻量级 Dashboard)

### 架构模式

采用**单体应用**架构,所有组件运行在同一个 JVM 进程中,适合快速开发和部署。

---

## 需求

### 需求 1: 项目基础架构搭建

**用户故事**: 作为一名开发人员,我希望建立完整的 Spring Boot 项目结构和基础配置,以便后续功能模块能够快速集成开发。

#### 验收标准

1. WHEN 使用 Maven 或 Gradle 构建工具 THEN 系统 SHALL 创建标准的 Spring Boot 3.2+ 项目结构
2. WHEN 配置项目依赖 THEN 系统 SHALL 包含以下核心依赖:
   - Spring Boot Starter Web
   - Spring Boot Starter Data JPA
   - MyBatis Spring Boot Starter
   - Spring Boot Starter Data Redis
   - Spring Boot Starter Security
   - Spring Boot Starter Actuator
   - Docker Java Client
   - JWT 相关库 (jjwt)
   - Lombok
   - Validation API
   - HTTP Client (OkHttp 或 Spring WebClient)
3. WHEN 定义包结构 THEN 系统 SHALL 按照以下层次组织代码:
   - `controller/` - 控制器层
   - `service/` - 业务服务层
   - `agent/` - 智能体核心模块
   - `domain/` - 领域模型
   - `mapper/` - MyBatis Mapper
   - `config/` - 配置类
   - `event/` - Spring 事件
   - `util/` - 工具类
4. WHEN 配置 application.yml THEN 系统 SHALL 支持多环境配置 (dev, test, prod)
5. WHEN 启动应用 THEN 系统 SHALL 成功启动并监听指定端口 (默认 8080)

---

### 需求 2: 数据库集成与实体映射

**用户故事**: 作为一名开发人员,我希望完成 MySQL 数据库的集成和实体类映射,以便系统能够持久化业务数据。

#### 验收标准

1. WHEN 配置数据源 THEN 系统 SHALL 连接到 MySQL 8.0 数据库
2. WHEN 执行数据库初始化脚本 THEN 系统 SHALL 创建以下 6 张核心表:
   - `t_user` - 用户表
   - `t_task` - 任务表
   - `t_environment` - 环境表
   - `t_container` - 容器表
   - `t_diagnosis_cache` - 诊断缓存表
   - `t_system_config` - 系统配置表
3. WHEN 定义实体类 THEN 系统 SHALL 为每张表创建对应的实体类,并使用 Lombok 简化代码
4. WHEN 配置 MyBatis THEN 系统 SHALL 支持 CRUD 操作和分页查询
5. WHEN 定义 Mapper 接口 THEN 系统 SHALL 为每个实体提供数据访问接口,并编写对应的 XML 映射文件
6. IF 数据库连接失败 THEN 系统 SHALL 记录详细错误日志并优雅降级

---

### 需求 3: Redis 缓存集成

**用户故事**: 作为一名开发人员,我希望集成 Redis 缓存服务,以便系统能够缓存 AI 诊断结果和会话信息,提升性能。

#### 验收标准

1. WHEN 配置 Redis 连接 THEN 系统 SHALL 连接到 Redis 7.0 服务器
2. WHEN 定义缓存策略 THEN 系统 SHALL 支持以下缓存场景:
   - AI 诊断结果缓存 (key: `diagnosis:{logHash}`, TTL: 90 天)
   - 系统指标缓存 (key: `metrics:system`, TTL: 10 秒)
   - JWT Token 黑名单 (key: `token:blacklist:{token}`, TTL: 24 小时)
3. WHEN 使用 Spring Cache 注解 THEN 系统 SHALL 自动管理缓存的读写和失效
4. WHEN Redis 不可用 THEN 系统 SHALL 降级到数据库查询,不影响核心功能
5. WHEN 缓存命中 THEN 系统 SHALL 在响应中标记 `fromCache: true`

---

### 需求 4: 安全认证与授权

**用户故事**: 作为一名系统管理员,我希望系统提供安全的用户认证和授权机制,以便保护 API 接口不被未授权访问。

#### 验收标准

1. WHEN 用户提交登录请求 THEN 系统 SHALL 验证用户名和密码 (BCrypt 加密)
2. WHEN 认证成功 THEN 系统 SHALL 生成 JWT Token (有效期 24 小时) 并返回给客户端
3. WHEN 访问受保护的 API THEN 系统 SHALL 验证 JWT Token 的有效性
4. WHEN Token 过期或无效 THEN 系统 SHALL 返回 401 Unauthorized 错误
5. WHEN 用户角色为 ADMIN THEN 系统 SHALL 允许访问所有管理接口
6. WHEN 用户角色为 USER THEN 系统 SHALL 仅允许访问普通用户接口
7. WHEN 用户登出 THEN 系统 SHALL 将 Token 加入黑名单
8. IF 连续登录失败 3 次 THEN 系统 SHALL 锁定账户 15 分钟

---

### 需求 5: 核心 API 接口实现

**用户故事**: 作为一名 CI 系统集成者,我希望系统提供完整的 RESTful API 接口,以便我能够创建任务、查询状态、获取日志等操作。

#### 验收标准

1. WHEN 实现认证接口 THEN 系统 SHALL 提供:
   - `POST /api/auth/login` - 用户登录
   - `GET /api/auth/me` - 获取当前用户信息
2. WHEN 实现任务管理接口 THEN 系统 SHALL 提供:
   - `GET /api/tasks` - 任务列表 (支持分页和过滤)
   - `POST /api/tasks` - 创建任务
   - `GET /api/tasks/{id}` - 查询任务详情
   - `GET /api/tasks/{id}/status` - 查询任务状态
   - `GET /api/tasks/{id}/logs` - 获取任务日志
   - `DELETE /api/tasks/{id}` - 取消任务
3. WHEN 实现环境管理接口 THEN 系统 SHALL 提供:
   - `GET /api/environments` - 环境列表
   - `GET /api/environments/{id}` - 环境详情
   - `POST /api/environments/{id}/release` - 释放环境
4. WHEN 实现监控指标接口 THEN 系统 SHALL 提供:
   - `GET /api/metrics` - 系统资源指标
   - `GET /api/metrics/health` - 健康检查
5. WHEN 实现 AI 诊断接口 THEN 系统 SHALL 提供:
   - `GET /api/diagnosis/{taskId}` - 获取诊断结果
   - `POST /api/diagnosis/{taskId}` - 请求 AI 诊断
6. WHEN API 返回响应 THEN 系统 SHALL 使用统一的响应格式:
   ```json
   {
     "code": 200,
     "message": "success",
     "data": {...},
     "timestamp": 1702713600000
   }
   ```
7. WHEN 发生错误 THEN 系统 SHALL 返回标准错误码 (1xxx-认证, 2xxx-任务, 3xxx-环境, 4xxx-诊断, 5xxx-系统)

---

### 需求 6: 智能体核心模块实现

**用户故事**: 作为一名系统架构师,我希望实现智能体的"感知-决策-执行"核心逻辑,以便系统能够自主管理测试环境的生命周期。

#### 验收标准

1. WHEN 实现 AgentBrain 主循环 THEN 系统 SHALL 每 5 秒执行一次感知-决策-执行循环
2. WHEN 实现感知器 (Perceiver) THEN 系统 SHALL 包含:
   - `DockerSensor` - 采集 Docker 宿主机资源指标 (CPU, 内存, 磁盘)
   - `TaskQueueSensor` - 监听任务队列
3. WHEN 实现决策引擎 (DecisionEngine) THEN 系统 SHALL 包含:
   - `RuleEngine` - 基于规则的快速决策 (处理 80% 常规场景)
   - `LLMAdvisor` - 基于 AI 的复杂决策 (处理 20% 异常场景)
   - `HybridDecisionEngine` - 混合决策引擎,根据场景选择合适的决策器
4. WHEN 实现执行器 (Actuator) THEN 系统 SHALL 包含:
   - `DockerActuator` - 执行 Docker 容器操作 (创建、启动、停止、删除)
   - `ComposeGenerator` - 动态生成 docker-compose.yml 文件
5. WHEN 内存使用率 < 60% THEN 决策引擎 SHALL 决策为 CREATE_NOW (立即创建环境)
6. WHEN 内存使用率 60-80% AND 队列长度 < 3 THEN 决策引擎 SHALL 决策为 CREATE_NOW
7. WHEN 内存使用率 60-80% AND 队列长度 >= 3 THEN 决策引擎 SHALL 决策为 ENQUEUE (加入等待队列)
8. WHEN 内存使用率 > 80% THEN 决策引擎 SHALL 决策为 ENQUEUE 并发送告警
9. WHEN 存在匹配的空闲环境 THEN 决策引擎 SHALL 决策为 REUSE (复用环境)
10. WHEN 执行失败 THEN 系统 SHALL 发布 `TaskFailedEvent` 事件触发 AI 诊断

---

### 需求 7: Docker 容器管理集成

**用户故事**: 作为一名运维工程师,我希望系统能够通过 Docker API 管理容器的生命周期,以便自动化测试环境的创建和销毁。

#### 验收标准

1. WHEN 配置 Docker 客户端 THEN 系统 SHALL 连接到本地或远程 Docker Engine (API v1.41+)
2. WHEN 创建测试环境 THEN 系统 SHALL 根据任务依赖动态生成 docker-compose.yml
3. WHEN 启动容器 THEN 系统 SHALL 执行以下步骤:
   - 拉取所需镜像 (如果本地不存在)
   - 创建容器网络
   - 启动所有依赖容器
   - 等待健康检查通过 (最多 120 秒)
4. WHEN 容器启动成功 THEN 系统 SHALL 记录容器 ID、端口映射、访问地址
5. WHEN 容器启动失败 THEN 系统 SHALL 获取容器日志 (最后 100 行) 并触发 AI 诊断
6. WHEN 释放环境 THEN 系统 SHALL 停止并删除所有容器和网络
7. WHEN 环境空闲超过 30 分钟 THEN 系统 SHALL 自动回收环境资源
8. WHEN 容器配置安全选项 THEN 系统 SHALL 应用以下安全策略:
   - `no-new-privileges: true`
   - `cap_drop: ALL`
   - `read_only: true` (根文件系统只读)

---

### 需求 8: AI 诊断服务集成

**用户故事**: 作为一名开发人员,我希望系统能够利用 AI 自动分析容器启动失败的日志,并支持灵活配置不同的 AI 服务提供商,以便快速定位问题根因并获得修复建议。

#### 验收标准

##### 8.1 AI 服务提供商配置

1. WHEN 配置 AI 服务 THEN 系统 SHALL 支持通过 application.yml 配置以下参数:
   ```yaml
   ai:
     provider: ollama  # 可选值: ollama, openai, custom
     ollama:
       base-url: http://localhost:11434
       model: qwen2.5:7b
       timeout: 30s
     openai:
       base-url: https://api.openai.com/v1  # 或其他 OpenAI 兼容 API
       api-key: ${OPENAI_API_KEY}
       model: gpt-4
       timeout: 30s
     custom:
       base-url: ${CUSTOM_AI_BASE_URL}
       api-key: ${CUSTOM_AI_API_KEY}
       model: ${CUSTOM_AI_MODEL}
       timeout: 30s
   ```
2. WHEN 系统启动 THEN 系统 SHALL 根据 `ai.provider` 配置初始化对应的 AI 客户端
3. WHEN 配置为 `ollama` THEN 系统 SHALL 使用 Ollama API 格式 (POST /api/generate)
4. WHEN 配置为 `openai` 或 `custom` THEN 系统 SHALL 使用 OpenAI 兼容 API 格式 (POST /v1/chat/completions)
5. WHEN 缺少必要配置 THEN 系统 SHALL 在启动时抛出配置错误异常

##### 8.2 AI 诊断核心流程

1. WHEN 任务失败 THEN 系统 SHALL 异步触发 AI 诊断流程
2. WHEN 执行诊断 THEN 系统 SHALL 计算日志内容的 SHA-256 哈希值
3. WHEN 查询缓存 THEN 系统 SHALL 首先从 Redis 查找是否存在相同日志的诊断结果
4. IF 缓存命中 THEN 系统 SHALL 直接返回缓存结果并增加命中计数
5. IF 缓存未命中 THEN 系统 SHALL 调用配置的 AI API 进行分析

##### 8.3 Ollama API 集成

1. WHEN 使用 Ollama 提供商 THEN 系统 SHALL 调用 POST /api/generate 接口
2. WHEN 构建 Ollama 请求 THEN 系统 SHALL 使用以下格式:
   ```json
   {
     "model": "qwen2.5:7b",
     "prompt": "你是资深 DevOps 工程师...",
     "stream": false,
     "options": {
       "temperature": 0.7
     }
   }
   ```
3. WHEN 接收 Ollama 响应 THEN 系统 SHALL 解析 `response` 字段获取诊断结果
4. WHEN Ollama 服务不可用 THEN 系统 SHALL 返回降级诊断结果 (基于规则匹配)

##### 8.4 OpenAI 兼容 API 集成

1. WHEN 使用 OpenAI 或 Custom 提供商 THEN 系统 SHALL 调用 POST /v1/chat/completions 接口
2. WHEN 构建 OpenAI 请求 THEN 系统 SHALL 使用以下格式:
   ```json
   {
     "model": "gpt-4",
     "messages": [
       {
         "role": "system",
         "content": "你是资深 DevOps 工程师..."
       },
       {
         "role": "user",
         "content": "分析以下 Docker 启动失败日志:\n[日志内容]"
       }
     ],
     "temperature": 0.7,
     "max_tokens": 2000
   }
   ```
3. WHEN 发送请求 THEN 系统 SHALL 在 HTTP Header 中包含 `Authorization: Bearer {api-key}`
4. WHEN 接收 OpenAI 响应 THEN 系统 SHALL 解析 `choices[0].message.content` 字段获取诊断结果
5. WHEN API 返回 401 错误 THEN 系统 SHALL 记录 API Key 无效错误
6. WHEN API 返回 429 错误 THEN 系统 SHALL 记录速率限制错误并延迟重试

##### 8.5 统一诊断接口

1. WHEN 实现 AI 客户端 THEN 系统 SHALL 定义统一的接口 `AIClient`:
   ```java
   public interface AIClient {
       DiagnosisResult diagnose(String prompt, int maxTokens);
       boolean isAvailable();
   }
   ```
2. WHEN 实现具体客户端 THEN 系统 SHALL 创建 `OllamaClient` 和 `OpenAICompatibleClient` 实现类
3. WHEN 调用诊断服务 THEN 系统 SHALL 通过工厂模式根据配置返回对应的客户端实例

##### 8.6 Prompt 构建与结果解析

1. WHEN 构建 Prompt THEN 系统 SHALL 包含以下信息:
   - 角色定义: "你是资深 DevOps 工程师,擅长分析容器启动失败问题"
   - 任务描述: "分析以下 Docker 启动失败日志,找出根本原因并提供解决方案"
   - 日志内容: 容器日志 (最后 100 行)
   - 输出格式: JSON 格式 (rootCause, possibleReasons, solutions, confidence)
2. WHEN 解析 AI 响应 THEN 系统 SHALL 提取 JSON 内容并反序列化为 `DiagnosisResult` 对象
3. IF JSON 解析失败 THEN 系统 SHALL 将原始文本作为 `rootCause` 返回

##### 8.7 结果存储与事件发布

1. WHEN 获得 AI 分析结果 THEN 系统 SHALL 将结果存入 Redis (TTL 90天) 和 MySQL
2. WHEN 存储到 MySQL THEN 系统 SHALL 记录以下字段:
   - `log_hash` - 日志哈希值
   - `diagnosis_result` - 诊断结果 JSON
   - `ai_provider` - AI 提供商 (ollama/openai/custom)
   - `model_name` - 使用的模型名称
   - `hit_count` - 缓存命中次数
   - `created_at` - 创建时间
3. WHEN 诊断完成 THEN 系统 SHALL 发布 `DiagnosisCompletedEvent` 事件

##### 8.8 降级策略

1. WHEN AI 服务不可用 THEN 系统 SHALL 返回基于规则的降级诊断结果
2. WHEN 实现规则匹配 THEN 系统 SHALL 支持以下常见错误模式:
   - 端口占用: "Address already in use"
   - 权限不足: "Permission denied"
   - 镜像不存在: "manifest unknown"
   - 内存不足: "OOMKilled"
   - 配置错误: "invalid reference format"
3. WHEN 使用降级诊断 THEN 系统 SHALL 在结果中标记 `fallback: true`

---

### 需求 9: Spring 事件驱动架构

**用户故事**: 作为一名系统架构师,我希望使用 Spring Events 实现模块间的解耦通信,以便提高系统的可维护性和扩展性。

#### 验收标准

1. WHEN 定义事件类 THEN 系统 SHALL 创建以下事件:
   - `TaskCreatedEvent` - 任务创建事件
   - `TaskCompletedEvent` - 任务完成事件
   - `TaskFailedEvent` - 任务失败事件
   - `EnvironmentReadyEvent` - 环境就绪事件
   - `EnvironmentReleasedEvent` - 环境释放事件
   - `DiagnosisCompletedEvent` - 诊断完成事件
   - `ResourceThresholdExceededEvent` - 资源阈值超限事件
2. WHEN 发布事件 THEN 系统 SHALL 使用 `ApplicationEventPublisher` 发布事件
3. WHEN 监听事件 THEN 系统 SHALL 使用 `@EventListener` 注解定义监听器
4. WHEN 处理异步事件 THEN 系统 SHALL 使用 `@Async` 注解避免阻塞主线程
5. WHEN 事件处理失败 THEN 系统 SHALL 记录错误日志但不影响事件发布者
6. WHEN 系统启动 THEN 系统 SHALL 配置异步任务执行器 (线程池大小: 核心 5, 最大 10)

---

### 需求 10: 监控与可观测性

**用户故事**: 作为一名运维工程师,我希望系统提供完善的监控指标和健康检查接口,以便实时了解系统运行状态。

#### 验收标准

1. WHEN 配置 Spring Boot Actuator THEN 系统 SHALL 暴露以下端点:
   - `/actuator/health` - 健康检查
   - `/actuator/metrics` - 指标查询
   - `/actuator/prometheus` - Prometheus 格式指标
2. WHEN 执行健康检查 THEN 系统 SHALL 检查以下组件状态:
   - MySQL 数据库连接
   - Redis 连接
   - Docker Engine 连接
   - AI API 可用性 (根据配置的提供商)
3. WHEN 采集业务指标 THEN 系统 SHALL 记录以下指标:
   - `sentinel_tasks_total{status}` - 任务总数 (按状态分组)
   - `sentinel_environments_active` - 活跃环境数
   - `sentinel_diagnosis_cache_hit_rate` - 诊断缓存命中率
   - `sentinel_diagnosis_by_provider{provider}` - 按 AI 提供商统计的诊断次数
   - `sentinel_decision_latency_seconds` - 决策延迟
4. WHEN 暴露 Prometheus 指标 THEN 系统 SHALL 支持 Prometheus 抓取
5. WHEN 系统资源超限 THEN 系统 SHALL 记录 WARN 级别日志
6. WHEN 关键组件不可用 THEN 系统 SHALL 返回 `DOWN` 健康状态

---

### 需求 11: 配置管理与多环境支持

**用户故事**: 作为一名开发人员,我希望系统支持灵活的配置管理和多环境部署,以便在不同环境中使用不同的配置参数。

#### 验收标准

1. WHEN 定义配置文件 THEN 系统 SHALL 支持以下环境:
   - `application.yml` - 通用配置
   - `application-dev.yml` - 开发环境 (默认使用本地 Ollama)
   - `application-test.yml` - 测试环境
   - `application-prod.yml` - 生产环境 (可配置使用云端 AI API)
2. WHEN 配置数据源 THEN 系统 SHALL 支持通过环境变量覆盖敏感信息 (如数据库密码、AI API Key)
3. WHEN 配置系统参数 THEN 系统 SHALL 从 `t_system_config` 表动态加载配置
4. WHEN 修改系统配置 THEN 系统 SHALL 支持热更新 (无需重启)
5. WHEN 配置日志级别 THEN 系统 SHALL 在开发环境使用 DEBUG,生产环境使用 INFO
6. WHEN 配置线程池 THEN 系统 SHALL 根据环境调整线程池大小
7. WHEN 启动应用 THEN 系统 SHALL 通过 `--spring.profiles.active` 参数指定环境

---

### 需求 12: 异常处理与日志管理

**用户故事**: 作为一名开发人员,我希望系统提供统一的异常处理和完善的日志记录,以便快速定位和解决问题。

#### 验收标准

1. WHEN 定义全局异常处理器 THEN 系统 SHALL 使用 `@ControllerAdvice` 捕获所有异常
2. WHEN 发生业务异常 THEN 系统 SHALL 返回标准错误响应 (包含错误码、消息、时间戳)
3. WHEN 发生系统异常 THEN 系统 SHALL 返回 500 错误并记录完整堆栈信息
4. WHEN 记录日志 THEN 系统 SHALL 使用 SLF4J + Logback
5. WHEN 记录敏感信息 THEN 系统 SHALL 自动脱敏 (密码、Token、API Key)
6. WHEN 日志滚动 THEN 系统 SHALL 按天切割日志文件,保留最近 30 天
7. WHEN 记录 API 访问日志 THEN 系统 SHALL 记录请求路径、方法、耗时、状态码
8. WHEN 记录业务日志 THEN 系统 SHALL 包含 traceId 用于链路追踪
9. WHEN 记录 AI API 调用 THEN 系统 SHALL 记录提供商、模型、耗时、Token 使用量

---

### 需求 13: 数据验证与参数校验

**用户故事**: 作为一名开发人员,我希望系统对所有输入参数进行严格校验,以便防止非法数据进入系统。

#### 验收标准

1. WHEN 定义 DTO 类 THEN 系统 SHALL 使用 Bean Validation 注解 (@NotNull, @Size, @Pattern 等)
2. WHEN 接收 API 请求 THEN 系统 SHALL 自动验证请求参数
3. WHEN 参数校验失败 THEN 系统 SHALL 返回 400 错误和详细的校验错误信息
4. WHEN 创建任务 THEN 系统 SHALL 验证:
   - `taskCode` 长度 1-64 字符,不能为空
   - `serviceId` 长度 1-64 字符,不能为空
   - `testType` 必须是枚举值之一
   - `priority` 范围 1-10
5. WHEN 用户登录 THEN 系统 SHALL 验证:
   - `username` 长度 3-50 字符
   - `password` 长度 6-100 字符
6. WHEN 自定义校验规则 THEN 系统 SHALL 支持自定义 Validator

---

### 需求 14: 单元测试与集成测试

**用户故事**: 作为一名开发人员,我希望编写完善的单元测试和集成测试,以便保证代码质量和系统稳定性。

#### 验收标准

1. WHEN 编写单元测试 THEN 系统 SHALL 使用 JUnit 5 + Mockito
2. WHEN 测试 Service 层 THEN 系统 SHALL Mock 依赖的 Repository 和外部服务
3. WHEN 测试 Controller 层 THEN 系统 SHALL 使用 MockMvc 模拟 HTTP 请求
4. WHEN 编写集成测试 THEN 系统 SHALL 使用 Testcontainers 启动真实的 MySQL 和 Redis
5. WHEN 测试 Docker 集成 THEN 系统 SHALL 使用 Testcontainers 启动 Docker-in-Docker
6. WHEN 测试 AI 集成 THEN 系统 SHALL Mock AI API 响应,测试不同提供商的客户端实现
7. WHEN 运行测试 THEN 系统 SHALL 达到以下覆盖率目标:
   - Service 层: > 80%
   - Controller 层: > 70%
   - 整体覆盖率: > 60%
8. WHEN 执行 CI 构建 THEN 系统 SHALL 自动运行所有测试

---

### 需求 15: 部署与运维支持

**用户故事**: 作为一名运维工程师,我希望系统提供便捷的部署方式和完善的运维工具,以便快速部署和维护系统。

#### 验收标准

1. WHEN 构建应用 THEN 系统 SHALL 生成可执行的 JAR 文件
2. WHEN 提供 Docker Compose 配置 THEN 系统 SHALL 包含以下服务:
   - MySQL 8.0
   - Redis 7.0
   - Ollama (可选,用于本地开发)
   - Sentinel 应用
3. WHEN 一键启动 THEN 系统 SHALL 通过 `docker-compose up -d` 启动所有服务
4. WHEN 初始化数据库 THEN 系统 SHALL 自动执行 schema.sql 脚本
5. WHEN 配置环境变量 THEN 系统 SHALL 支持通过 `.env` 文件配置敏感信息 (数据库密码、AI API Key)
6. WHEN 提供启动脚本 THEN 系统 SHALL 包含 `start.sh` 和 `stop.sh` 脚本
7. WHEN 提供 README THEN 系统 SHALL 包含详细的部署和使用文档,包括:
   - 如何配置不同的 AI 提供商
   - 环境变量说明
   - API 使用示例
8. WHEN 生产部署 THEN 系统 SHALL 支持外部化配置 (通过环境变量或配置中心)
9. WHEN 提供配置示例 THEN 系统 SHALL 包含以下场景的配置模板:
   - 使用本地 Ollama
   - 使用 OpenAI API
   - 使用其他 OpenAI 兼容 API (如 Azure OpenAI、通义千问等)

---

## 非功能性需求

### 性能要求

1. API 响应时间 (P95) < 500ms (不含 AI 诊断)
2. AI 诊断响应时间:
   - 本地 Ollama: < 10 秒 (首次) / < 50ms (缓存命中)
   - 云端 API: < 5 秒 (首次) / < 50ms (缓存命中)
3. 支持并发创建 10 个测试环境
4. 系统内存占用 < 2GB (不含容器)

### 可靠性要求

1. 系统可用性 > 99% (单月)
2. 数据持久化保证不丢失
3. 关键操作支持事务回滚
4. 外部服务故障时优雅降级 (AI 服务降级到规则匹配)

### 安全性要求

1. 所有 API 接口需要 JWT 认证 (除健康检查)
2. 密码使用 BCrypt 加密存储
3. AI API Key 加密存储,日志中自动脱敏
4. 敏感日志自动脱敏
5. 容器运行时应用安全策略

### 可维护性要求

1. 代码遵循阿里巴巴 Java 开发规范
2. 关键业务逻辑添加详细注释
3. 提供完整的 API 文档 (Swagger/OpenAPI)
4. 日志记录完整的操作链路
5. AI 提供商可插拔,易于扩展新的提供商

---

## 技术约束

1. JDK 版本: 17 或 21 (LTS)
2. Spring Boot 版本: 3.2.x (最新稳定版)
3. MySQL 版本: 8.0+
4. Redis 版本: 7.0+
5. Docker API 版本: v1.41+
6. 构建工具: Maven 3.8+ 或 Gradle 8.0+
7. AI API 兼容性: 支持 OpenAI API v1 格式

---

## 依赖关系

1. 需求 1 (项目基础架构) 是所有其他需求的前置条件
2. 需求 2 (数据库集成) 是需求 5 (API 实现) 的前置条件
3. 需求 4 (安全认证) 是需求 5 (API 实现) 的前置条件
4. 需求 6 (智能体核心) 依赖需求 7 (Docker 集成) 和需求 8 (AI 诊断)
5. 需求 9 (事件驱动) 贯穿需求 6-8 的实现
6. 需求 14 (测试) 依赖所有功能需求完成

---

## 验收标准总览

系统完成开发后,应满足以下总体验收标准:

1. ✅ 所有 15 个核心 API 接口正常工作
2. ✅ 能够成功创建、启动、停止、删除 Docker 容器
3. ✅ AI 诊断功能正常,支持至少 2 种 AI 提供商 (Ollama + OpenAI 兼容)
4. ✅ AI 诊断缓存命中率 > 50%
5. ✅ 智能体决策引擎能够根据资源状态做出正确决策
6. ✅ 所有单元测试和集成测试通过
7. ✅ 系统能够通过 Docker Compose 一键启动
8. ✅ 健康检查接口返回所有组件状态正常
9. ✅ 提供完整的部署文档和 API 文档
10. ✅ 支持通过配置文件切换不同的 AI 提供商
