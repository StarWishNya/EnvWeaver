# 实施计划 - Sentinel 后端服务 Spring Boot 部署

## 任务清单

- [ ] 1. 搭建 Spring Boot 项目基础架构
   - 使用 Maven 创建 Spring Boot 3.2+ 项目，配置 pom.xml 依赖（Web、MyBatis-Plus、Redis、Security、Actuator、Docker Client、JWT、Lombok、Validation、OkHttp）
   - 创建标准包结构：controller、service、agent、domain、mapper、config、event、util、dto、enums
   - 配置多环境 application.yml（dev、test、prod），包含数据库、Redis、AI 服务配置
   - 创建主启动类 SentinelApplication 并验证应用能成功启动
   - _需求：1.1, 1.2, 1.3, 1.4, 1.5_

- [ ] 2. 实现数据库集成与 MyBatis-Plus 配置
   - [ ] 2.1 配置数据源和 MyBatis-Plus
      - 在 application.yml 中配置 MySQL 8.0 数据源连接信息
      - 配置 MyBatis-Plus（mapper 扫描路径、驼峰命名转换、逻辑删除、分页插件）
      - 创建数据库初始化脚本（schema.sql）并配置自动执行
      - 创建 MybatisPlusConfig 配置类，注册分页插件和乐观锁插件
      - _需求：2.1, 2.2_
   
   - [ ] 2.2 创建实体类和 Mapper 接口
      - 创建 6 个实体类（User、Task、Environment、Container、DiagnosisCache、SystemConfig），使用 Lombok 和 MyBatis-Plus 注解（@TableName、@TableId、@TableField）
      - 为每个实体创建对应的 Mapper 接口，继承 BaseMapper<T>
      - 实现自定义查询方法（如按状态查询任务、按哈希查询诊断缓存）
      - 实现数据库连接失败的异常处理和日志记录
      - _需求：2.3, 2.4, 2.5, 2.6_

- [ ] 3. 集成 Redis 缓存服务
   - 配置 Spring Data Redis 连接（application.yml）
   - 创建 RedisConfig 配置类，定义 RedisTemplate 和序列化策略（使用 Jackson2JsonRedisSerializer）
   - 创建 CacheService 工具类，封装三种缓存场景：AI 诊断结果缓存（TTL 90天）、系统指标缓存（TTL 10秒）、JWT Token 黑名单（TTL 24小时）
   - 配置 Spring Cache 注解支持（@EnableCaching、@Cacheable、@CacheEvict）
   - 实现 Redis 不可用时的降级策略（捕获 RedisConnectionFailureException）
   - _需求：3.1, 3.2, 3.3, 3.4, 3.5_

- [ ] 4. 实现安全认证与授权模块
   - [ ] 4.1 实现 JWT 工具类和过滤器
      - 创建 JwtUtil 工具类（生成 Token、验证 Token、解析 Token、刷新 Token）
      - 实现 JwtAuthenticationFilter 拦截请求并验证 Token
      - 实现 Token 黑名单机制（基于 Redis，登出时加入黑名单）
      - 创建 LoginAttemptService 实现登录失败锁定机制（3次失败锁定15分钟）
      - _需求：4.2, 4.3, 4.4, 4.7, 4.8_
   
   - [ ] 4.2 配置 Spring Security
      - 创建 SecurityConfig 配置类，配置认证和授权规则
      - 实现 UserDetailsServiceImpl 加载用户信息（从数据库查询）
      - 配置密码加密器（BCryptPasswordEncoder）
      - 配置 URL 访问权限（ADMIN 可访问所有接口，USER 仅访问普通接口）
      - 配置异常处理（AuthenticationEntryPoint、AccessDeniedHandler）
      - _需求：4.1, 4.5, 4.6_

- [ ] 5. 实现核心 API 接口
   - [ ] 5.1 实现统一响应格式和异常处理
      - 创建 ApiResponse<T> 统一响应类（code、message、data、timestamp）
      - 创建 ErrorCode 枚举类，定义标准错误码（1xxx-认证、2xxx-任务、3xxx-环境、4xxx-诊断、5xxx-系统）
      - 创建 GlobalExceptionHandler（@ControllerAdvice），捕获所有异常并返回统一格式
      - _需求：5.6, 5.7, 12.1, 12.2, 12.3_
   
   - [ ] 5.2 实现认证接口
      - 创建 LoginRequest 和 LoginResponse DTO，使用 Bean Validation 注解
      - 创建 AuthController，实现登录接口（POST /api/auth/login）
      - 创建 AuthService，实现用户认证逻辑（验证密码、生成 Token）
      - 实现获取当前用户信息接口（GET /api/auth/me）
      - _需求：5.1, 13.5_
   
   - [ ] 5.3 实现任务管理接口
      - 创建 CreateTaskRequest、TaskResponse DTO，使用 Bean Validation 注解
      - 创建 TaskController 和 TaskService
      - 实现任务列表接口（GET /api/tasks，使用 MyBatis-Plus 分页插件）
      - 实现创建任务接口（POST /api/tasks），发布 TaskCreatedEvent 事件
      - 实现查询任务详情、状态、日志接口（GET /api/tasks/{id}/*）
      - 实现取消任务接口（DELETE /api/tasks/{id}）
      - _需求：5.2, 13.4_
   
   - [ ] 5.4 实现环境管理和监控接口
      - 创建 EnvironmentController 和 EnvironmentService，实现环境列表、详情、释放接口
      - 创建 MetricsController，实现系统资源指标和健康检查接口
      - 创建 DiagnosisController，实现 AI 诊断接口（GET/POST /api/diagnosis/{taskId}）
      - _需求：5.3, 5.4, 5.5_

- [ ] 6. 实现多 AI 提供商支持
   - [ ] 6.1 定义 AI 客户端统一接口
      - 创建 AIClient 接口，定义 diagnose() 和 isAvailable() 方法
      - 创建 DiagnosisResult 类，封装诊断结果（rootCause、possibleReasons、solutions、confidence、provider、model）
      - 创建 AIProvider 枚举类（OLLAMA、OPENAI、CUSTOM）
      - _需求：8.5_
   
   - [ ] 6.2 实现 Ollama 客户端
      - 创建 OllamaConfig 配置类，读取 application.yml 中的 Ollama 配置
      - 创建 OllamaClient 实现 AIClient 接口，使用 OkHttp 调用 POST /api/generate
      - 实现请求构建（model、prompt、stream、options）
      - 实现响应解析（提取 response 字段）
      - 实现超时处理和异常捕获
      - _需求：8.1, 8.3_
   
   - [ ] 6.3 实现 OpenAI 兼容客户端
      - 创建 OpenAIConfig 配置类，读取 application.yml 中的 OpenAI/Custom 配置
      - 创建 OpenAICompatibleClient 实现 AIClient 接口，使用 OkHttp 调用 POST /v1/chat/completions
      - 实现请求构建（model、messages、temperature、max_tokens）
      - 实现 Authorization Header（Bearer Token）
      - 实现响应解析（提取 choices[0].message.content）
      - 实现错误处理（401 API Key 无效、429 速率限制）
      - _需求：8.1, 8.4_
   
   - [ ] 6.4 实现 AI 客户端工厂
      - 创建 AIClientFactory，根据 ai.provider 配置返回对应的客户端实例
      - 实现客户端单例管理（避免重复创建）
      - 实现配置验证（缺少必要配置时抛出异常）
      - _需求：8.1, 8.5_

- [ ] 7. 实现 AI 诊断服务核心逻辑
   - 创建 DiagnosisService，注入 AIClientFactory 和 CacheService
   - 实现诊断流程：计算日志哈希（SHA-256）→ 查询 Redis 缓存 → 调用 AI API → 存储结果
   - 实现 Prompt 构建（角色定义、任务描述、日志内容、输出格式）
   - 实现 JSON 结果解析（提取 rootCause、possibleReasons、solutions、confidence）
   - 实现结果存储（Redis TTL 90天 + MySQL 持久化，记录 ai_provider 和 model_name）
   - 实现 DiagnosisCompletedEvent 事件发布
   - 实现降级策略（规则匹配常见错误：端口占用、权限不足、镜像不存在、内存不足、配置错误）
   - _需求：8.2, 8.6, 8.7, 8.8_

- [ ] 8. 实现 Docker 容器管理模块
   - 创建 DockerClientConfig 配置类，连接 Docker Engine（使用 docker-java 库）
   - 实现 DockerActuator 执行器，封装容器操作（创建、启动、停止、删除、获取日志）
   - 实现 ComposeGenerator，根据任务依赖动态生成 docker-compose.yml（使用模板引擎或字符串拼接）
   - 实现容器启动流程：拉取镜像 → 创建网络 → 启动容器 → 健康检查（最多120秒，轮询检查）
   - 实现容器启动失败时的日志获取（最后100行）和 TaskFailedEvent 事件发布
   - 实现环境释放和自动回收机制（定时任务扫描空闲30分钟的环境）
   - 配置容器安全策略（no-new-privileges、cap_drop、read_only）
   - _需求：7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8_

- [ ] 9. 实现智能体核心模块
   - [ ] 9.1 实现感知器（Perceiver）
      - 创建 DockerSensor，使用 Docker API 采集宿主机资源指标（CPU、内存、磁盘使用率）
      - 创建 TaskQueueSensor，查询数据库获取待处理任务队列状态
      - 创建 PerceptionResult 类，封装感知结果
      - _需求：6.2_
   
   - [ ] 9.2 实现决策引擎（DecisionEngine）
      - 创建 Decision 枚举类（CREATE_NOW、REUSE、ENQUEUE）
      - 创建 RuleEngine，实现基于规则的快速决策（内存<60%立即创建、60-80%根据队列长度决策、>80%加入队列并告警）
      - 创建 LLMAdvisor，实现基于 AI 的复杂决策（调用 AI API 分析异常场景）
      - 创建 HybridDecisionEngine，根据场景选择合适的决策器（常规场景用 RuleEngine，异常场景用 LLMAdvisor）
      - 实现环境复用决策逻辑（查询空闲环境，匹配依赖和配置）
      - _需求：6.3, 6.5, 6.6, 6.7, 6.8, 6.9_
   
   - [ ] 9.3 实现 AgentBrain 主循环
      - 创建 AgentBrain 类，使用 @Scheduled 注解实现定时任务（每5秒执行一次）
      - 实现感知-决策-执行循环：调用感知器 → 调用决策引擎 → 调用执行器
      - 实现执行失败时发布 TaskFailedEvent 事件
      - 实现循环异常处理（捕获异常但不中断循环）
      - _需求：6.1, 6.4, 6.10_

- [ ] 10. 实现 Spring 事件驱动架构
   - 创建 7 个事件类（TaskCreatedEvent、TaskCompletedEvent、TaskFailedEvent、EnvironmentReadyEvent、EnvironmentReleasedEvent、DiagnosisCompletedEvent、ResourceThresholdExceededEvent），继承 ApplicationEvent
   - 创建事件监听器类（TaskEventListener、EnvironmentEventListener、DiagnosisEventListener），使用 @EventListener 注解定义监听方法
   - 配置异步任务执行器（@EnableAsync、ThreadPoolTaskExecutor，核心5、最大10、队列100）
   - 在相关业务逻辑中使用 ApplicationEventPublisher 发布事件
   - 实现事件处理失败的异常处理和日志记录（@Async 方法中捕获异常）
   - _需求：9.1, 9.2, 9.3, 9.4, 9.5, 9.6_

- [ ] 11. 实现监控与可观测性
   - [ ] 11.1 配置 Spring Boot Actuator
      - 在 application.yml 中配置 Actuator 端点（暴露 health、metrics、prometheus）
      - 配置端点安全（health 和 prometheus 允许匿名访问，其他需要认证）
      - _需求：10.1_
   
   - [ ] 11.2 实现自定义健康检查
      - 创建 MySQLHealthIndicator，检查 MySQL 数据库连接
      - 创建 RedisHealthIndicator，检查 Redis 连接
      - 创建 DockerHealthIndicator，检查 Docker Engine 连接
      - 创建 AIHealthIndicator，检查 AI API 可用性（根据配置的提供商）
      - _需求：10.2_
   
   - [ ] 11.3 实现自定义业务指标
      - 使用 MeterRegistry 注册自定义指标
      - 实现任务总数指标（按状态分组，使用 Counter）
      - 实现活跃环境数指标（使用 Gauge）
      - 实现诊断缓存命中率指标（使用 Counter 计算命中和未命中次数）
      - 实现按 AI 提供商统计的诊断次数指标（使用 Counter，tag 为 provider）
      - 实现决策延迟指标（使用 Timer）
      - _需求：10.3, 10.4_

- [ ] 12. 实现配置管理、日志和数据验证
   - [ ] 12.1 配置多环境支持
      - 创建 application-dev.yml（使用本地 Ollama，DEBUG 日志级别）
      - 创建 application-test.yml（使用测试环境配置）
      - 创建 application-prod.yml（使用云端 AI API，INFO 日志级别，生产数据库）
      - 实现从 t_system_config 表动态加载配置（使用 @ConfigurationProperties）
      - 实现配置热更新（使用 @RefreshScope）
      - _需求：11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7_
   
   - [ ] 12.2 配置日志管理
      - 创建 logback-spring.xml，配置日志格式、级别、滚动策略
      - 实现敏感信息脱敏（自定义 Logback Converter，脱敏密码、Token、API Key）
      - 实现 API 访问日志拦截器（HandlerInterceptor，记录路径、方法、耗时、状态码）
      - 实现 traceId 链路追踪（使用 MDC，在 Filter 中生成 traceId）
      - 实现 AI API 调用日志（记录提供商、模型、耗时、Token 使用量）
      - _需求：12.4, 12.5, 12.6, 12.7, 12.8, 12.9_
   
   - [ ] 12.3 实现数据验证
      - 创建 DTO 类（LoginRequest、CreateTaskRequest、ReleaseEnvironmentRequest 等），使用 Bean Validation 注解（@NotNull、@Size、@Pattern、@Min、@Max）
      - 在 Controller 方法参数上添加 @Valid 注解，启用自动校验
      - 在 GlobalExceptionHandler 中捕获 MethodArgumentNotValidException，返回 400 错误和详细校验信息
      - 实现自定义 Validator（如需要，例如验证任务依赖配置格式）
      - _需求：13.1, 13.2, 13.3, 13.4, 13.5, 13.6_

- [ ] 13. 编写单元测试和集成测试
   - [ ] 13.1 编写 Service 层单元测试
      - 为 AuthService、TaskService、EnvironmentService、DiagnosisService 编写单元测试
      - 使用 Mockito Mock 依赖的 Mapper、Redis、Docker、AI 客户端
      - 测试正常流程和异常流程（如数据库异常、Redis 不可用、AI API 超时）
      - 验证 Service 层测试覆盖率 > 80%
      - _需求：14.1, 14.2_
   
   - [ ] 13.2 编写 Controller 层单元测试
      - 为 AuthController、TaskController、EnvironmentController 编写单元测试
      - 使用 MockMvc 模拟 HTTP 请求，测试参数校验、认证授权、响应格式
      - 验证 Controller 层测试覆盖率 > 70%
      - _需求：14.3_
   
   - [ ] 13.3 编写集成测试
      - 使用 Testcontainers 启动 MySQL、Redis、Docker-in-Docker 容器
      - 编写端到端集成测试（创建任务 → 启动容器 → 获取日志 → AI 诊断 → 释放环境）
      - Mock AI API 响应，测试不同提供商的客户端实现（Ollama、OpenAI）
      - 验证整体测试覆盖率 > 60%
      - _需求：14.4, 14.5, 14.6, 14.7_

- [ ] 14. 完成部署配置和文档
   - 配置 Maven 打包插件（spring-boot-maven-plugin），生成可执行 JAR 文件
   - 创建 docker-compose.yml（包含 MySQL、Redis、Ollama、Sentinel 应用）
   - 创建 .env.example 文件模板（数据库密码、AI API Key、Docker 连接等）
   - 创建启动脚本（start.sh、stop.sh），支持一键启动和停止
   - 编写 README.md（项目介绍、环境要求、部署步骤、配置说明、API 文档链接）
   - 编写 AI 提供商配置文档（如何配置本地 Ollama、OpenAI API、其他 OpenAI 兼容 API）
   - 集成 Swagger/OpenAPI 生成 API 文档（使用 springdoc-openapi-starter-webmvc-ui）
   - 创建数据库初始化脚本自动执行配置（spring.sql.init.mode=always）
   - _需求：15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 15.8, 15.9_

---

## 任务依赖关系

```
1 (基础架构)
├── 2 (数据库集成)
│   └── 5.3 (任务管理接口)
│   └── 5.4 (环境管理接口)
├── 3 (Redis 缓存)
│   └── 4.1 (JWT 过滤器)
│   └── 7 (AI 诊断服务)
├── 4 (安全认证)
│   └── 5.2 (认证接口)
├── 5.1 (统一响应格式)
│   └── 5.2, 5.3, 5.4 (所有 API 接口)
├── 6 (多 AI 提供商)
│   └── 7 (AI 诊断服务)
├── 7 (AI 诊断服务)
│   └── 9.2 (决策引擎 - LLMAdvisor)
├── 8 (Docker 容器管理)
│   └── 9.3 (AgentBrain 主循环)
├── 9 (智能体核心)
│   └── 10 (事件驱动)
├── 10 (事件驱动) - 贯穿整个开发过程
├── 11 (监控) - 贯穿整个开发过程
├── 12 (配置、日志、验证) - 贯穿整个开发过程
└── 13 (测试) - 依赖所有功能任务完成
    └── 14 (部署文档) - 最后执行
```

---

## 技术要点提醒

### MyBatis-Plus 使用要点
1. **实体类注解**：使用 `@TableName`、`@TableId(type = IdType.AUTO)`、`@TableField`
2. **逻辑删除**：配置 `@TableLogic` 注解和全局配置
3. **分页查询**：使用 `Page<T>` 和 `IPage<T>`，注册 `PaginationInnerInterceptor`
4. **条件构造器**：使用 `QueryWrapper` 和 `LambdaQueryWrapper` 构建复杂查询
5. **自动填充**：使用 `@TableField(fill = FieldFill.INSERT)` 自动填充创建时间

### AI 多提供商集成要点
1. **统一接口**：定义 `AIClient` 接口，不同提供商实现该接口
2. **工厂模式**：使用 `AIClientFactory` 根据配置返回对应客户端
3. **配置管理**：使用 `@ConfigurationProperties` 读取不同提供商的配置
4. **错误处理**：统一处理 HTTP 错误（401、429、500）和超时异常
5. **降级策略**：AI 服务不可用时使用规则匹配提供基础诊断

### JWT 认证要点
1. **Token 生成**：使用 JJWT 库，设置过期时间 24 小时
2. **Token 验证**：在 Filter 中验证 Token 并设置 SecurityContext
3. **黑名单机制**：登出时将 Token 加入 Redis 黑名单
4. **刷新机制**：提供 Token 刷新接口，延长有效期

### Docker 集成要点
1. **异步操作**：容器启动、停止等操作使用异步处理，避免阻塞
2. **超时处理**：设置合理的超时时间（拉取镜像 5 分钟，健康检查 120 秒）
3. **日志获取**：使用 `docker logs --tail 100` 获取容器日志
4. **资源清理**：确保容器停止后删除网络和卷

### 事件驱动要点
1. **异步处理**：使用 `@Async` 注解处理耗时事件（如 AI 诊断）
2. **事务边界**：事件发布应在事务提交后（使用 `@TransactionalEventListener`）
3. **异常处理**：事件监听器中捕获异常，避免影响事件发布者
4. **顺序保证**：如需保证事件顺序，使用同步事件或消息队列

### 监控指标要点
1. **业务指标**：使用 Micrometer 注册自定义指标（Counter、Gauge、Timer）
2. **标签使用**：为指标添加标签（如 status、provider），便于分组统计
3. **性能影响**：避免在高频路径上记录过多指标，影响性能

### 测试要点
1. **Testcontainers**：使用真实的 MySQL、Redis、Docker 容器进行集成测试
2. **Mock AI API**：使用 WireMock 或 MockWebServer Mock AI API 响应
3. **测试数据**：使用 @Sql 注解加载测试数据，测试后清理
4. **覆盖率**：优先测试核心业务逻辑，不追求 100% 覆盖率
