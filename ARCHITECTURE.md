# Sentinel 系统架构设计文档

**版本：** v2.0 (重构版)  
**更新日期：** 2025-12-16  
**架构模式：** 单体应用 + 嵌入式智能体

---

## 一、架构概述

### 1.1 架构定位

Sentinel 采用**单体 Spring Boot 应用**架构，所有组件运行在同一个 JVM 进程中。这是一个适合课程项目的务实选择：

- ✅ 开发简单，无需管理多个服务
- ✅ 部署方便，单个 JAR 文件即可运行
- ✅ 调试容易，所有代码在同一进程
- ✅ 资源节省，无需 Kafka、服务注册等中间件

### 1.2 技术栈

| 层次 | 技术选型 | 说明 |
|------|----------|------|
| 框架 | Spring Boot 3.2 | 主应用框架 |
| 持久层 | MyBatis-Plus + MySQL 8.0 | 核心业务数据 |
| 缓存 | Redis 7.0 | 诊断缓存、会话管理 |
| 容器 | Docker Engine API v1.41 | 容器生命周期管理 |
| AI | Ollama + Qwen2.5-7B | 本地大模型推理 |
| 前端 | Thymeleaf + HTMX | 轻量级 Dashboard |

### 1.3 系统架构图

```
┌─────────────────────────────────────────────────────────────┐
│                    CI System (Jenkins/GitLab)                │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTP POST /api/tasks
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                  Sentinel Application (单体)                 │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                   Controller Layer                   │   │
│  │   TaskController  │  MonitorController  │  DiagnosisController   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                    Service Layer                     │   │
│  │   TaskService  │  EnvironmentService  │  DiagnosisService   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │               Agent Core (智能体核心)                │   │
│  │  ┌──────────┐  ┌──────────────┐  ┌──────────────┐  │   │
│  │  │Perceiver │  │DecisionEngine│  │   Actuator   │  │   │
│  │  │ (感知器) │  │  (决策引擎)  │  │   (执行器)   │  │   │
│  │  └────┬─────┘  └──────┬───────┘  └──────┬───────┘  │   │
│  │       │               │                 │           │   │
│  │  DockerSensor    RuleEngine(L1)    DockerActuator  │   │
│  │  MetricsCollector LLMAdvisor(L2)   ComposeGenerator│   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                 Infrastructure Layer                 │   │
│  │   Docker Client  │  MySQL  │  Redis  │  Ollama API  │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                           │
            ┌──────────────┼──────────────┐
            ▼              ▼              ▼
      ┌──────────┐  ┌──────────┐  ┌──────────┐
      │  MySQL   │  │  Redis   │  │  Ollama  │
      │ 8.0      │  │  7.0     │  │ Container│
      └──────────┘  └──────────┘  └──────────┘
```

---

## 二、模块设计

### 2.1 包结构

```
com.sentinel
├── SentinelApplication.java          # 启动类
├── controller/                        # 控制器层
│   ├── TaskController.java           # 任务管理 API
│   ├── MonitorController.java        # 监控 API
│   └── DiagnosisController.java      # AI 诊断 API
├── service/                           # 业务服务层
│   ├── TaskService.java              # 任务业务逻辑
│   ├── EnvironmentService.java       # 环境管理
│   └── DiagnosisService.java         # 诊断服务
├── agent/                             # 智能体核心
│   ├── AgentBrain.java               # 智能体大脑（主循环）
│   ├── perceiver/                    # 感知器模块
│   │   ├── Perceiver.java            # 感知器接口
│   │   ├── DockerSensor.java         # Docker 资源感知
│   │   └── MetricsCollector.java     # 指标采集器
│   ├── decision/                     # 决策引擎模块
│   │   ├── DecisionEngine.java       # 决策引擎接口
│   │   ├── RuleEngine.java           # L1: 规则引擎
│   │   └── LLMAdvisor.java           # L2: LLM 顾问
│   └── actuator/                     # 执行器模块
│       ├── Actuator.java             # 执行器接口
│       ├── DockerActuator.java       # Docker 操作执行
│       └── ComposeGenerator.java     # Compose 文件生成
├── domain/                            # 领域模型
│   ├── Task.java                     # 任务实体
│   ├── Environment.java              # 环境实体
│   ├── Container.java                # 容器实体
│   ├── Metrics.java                  # 监控指标
│   └── DiagnosisResult.java          # 诊断结果
├── mapper/                            # MyBatis Mapper
│   ├── TaskMapper.java
│   ├── EnvironmentMapper.java
│   └── DiagnosisCacheMapper.java
├── config/                            # 配置类
│   ├── DockerConfig.java             # Docker 客户端配置
│   ├── RedisConfig.java              # Redis 配置
│   └── OllamaConfig.java             # Ollama 配置
├── event/                             # Spring 事件（替代 Kafka）
│   ├── TaskCreatedEvent.java
│   ├── EnvironmentReadyEvent.java
│   └── DiagnosisCompletedEvent.java
└── util/                              # 工具类
    ├── DockerUtils.java
    └── LogHashUtils.java
```

### 2.2 核心组件职责

| 组件 | 职责 | 关键方法 |
|------|------|----------|
| **AgentBrain** | 智能体主循环，协调感知-决策-执行 | `mainLoop()`, `perceive()`, `decide()`, `execute()` |
| **DockerSensor** | 采集 Docker 宿主机资源指标 | `getMetrics()`, `getContainerStats()` |
| **RuleEngine** | 基于规则的快速决策（处理 80% 场景） | `decide(task, metrics)` |
| **LLMAdvisor** | 调用 Ollama 进行复杂决策和诊断 | `analyze(log)`, `advise(context)` |
| **DockerActuator** | 执行 Docker 容器操作 | `createEnvironment()`, `stopContainer()` |
| **ComposeGenerator** | 动态生成 docker-compose.yml | `generate(dependencies)` |

---

## 三、数据模型

### 3.1 核心实体（6 张表）

根据需求分析，MVP 版本需要以下核心表：

```
┌─────────────┐     ┌─────────────────┐     ┌─────────────┐
│   t_user    │     │     t_task      │────<│  t_environment  │────<│ t_container │
│  (用户表)   │     │    (任务表)     │     │    (环境表)     │     │  (容器表)   │
└─────────────┘     └─────────────────┘     └─────────────────┘     └─────────────┘
                            │
                            │
                    ┌───────┴───────┐
                    │               │
            ┌───────────────┐  ┌────────────────┐
            │t_diagnosis_cache│  │ t_system_config│
            │  (诊断缓存表)  │  │  (系统配置表)  │
            └───────────────┘  └────────────────┘
```

### 3.2 表结构概览

| 表名 | 说明 | 核心字段 |
|------|------|----------|
| `t_user` | 用户信息 | id, username, password, email, role, enabled |
| `t_task` | 任务记录 | id, task_code, service_id, test_type, status, created_at |
| `t_environment` | 测试环境 | id, env_code, task_id, status, compose_content, access_url |
| `t_container` | 容器实例 | id, environment_id, container_id, image, status |
| `t_diagnosis_cache` | AI 诊断缓存 | log_hash, root_cause, solutions, hit_count |
| `t_system_config` | 系统配置 | config_key, config_value, description |

---

## 四、接口设计

### 4.1 核心 API（15 个）

MVP 版本聚焦于核心功能，只实现必要的 API：

#### 认证接口（2 个）
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 用户登录 |
| GET | `/api/auth/me` | 获取当前用户信息 |

#### 任务管理（6 个）
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/tasks` | 任务列表 |
| POST | `/api/tasks` | 创建任务 |
| GET | `/api/tasks/{id}` | 查询任务详情 |
| GET | `/api/tasks/{id}/status` | 查询任务状态 |
| GET | `/api/tasks/{id}/logs` | 获取任务日志 |
| DELETE | `/api/tasks/{id}` | 取消任务 |

#### 环境管理（3 个）
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/environments` | 环境列表 |
| GET | `/api/environments/{id}` | 环境详情 |
| POST | `/api/environments/{id}/release` | 释放环境 |

#### 监控指标（2 个）
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/metrics` | 系统资源指标 |
| GET | `/api/metrics/health` | 健康检查 |

#### AI 诊断（2 个）
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/diagnosis/{taskId}` | 获取诊断结果 |
| POST | `/api/diagnosis/{taskId}` | 请求 AI 诊断 |

### 4.2 统一响应格式

```json
{
  "code": 200,
  "message": "success",
  "data": { ... },
  "timestamp": 1702713600000
}
```

### 4.3 错误码定义

| 错误码 | 说明 |
|--------|------|
| `TASK_001` | 任务不存在 |
| `TASK_002` | 任务状态不允许该操作 |
| `ENV_001` | 环境创建失败 |
| `ENV_002` | 无可用资源，已加入等待队列 |
| `ENV_003` | 环境启动超时 |
| `AI_001` | Ollama 服务不可用 |
| `AI_002` | 诊断超时 |
| `SYS_001` | 系统内部错误 |

---

## 五、智能体设计

### 5.1 感知-决策-执行循环

```java
public class AgentBrain {
    
    @Scheduled(fixedDelay = 5000)  // 每 5 秒执行一次
    public void mainLoop() {
        // 1. 感知：从队列获取待处理任务
        Task task = taskQueue.poll();
        if (task == null) return;
        
        // 2. 感知：获取系统资源状态
        Metrics metrics = dockerSensor.getMetrics();
        
        // 3. 决策：根据规则或 LLM 做出决策
        Decision decision = decisionEngine.decide(task, metrics);
        
        // 4. 执行：执行决策动作
        ActionResult result = actuator.execute(decision);
        
        // 5. 学习：失败时调用 AI 分析并缓存
        if (result.isFailed()) {
            diagnosisService.analyzeAndCache(result);
        }
    }
}
```

### 5.2 决策规则（L1 规则引擎）

| 条件 | 动作 | 说明 |
|------|------|------|
| 内存 < 60% | CREATE_NOW | 资源充足，立即创建 |
| 内存 60-80% && 队列 < 3 | CREATE_NOW | 可承受波动 |
| 内存 60-80% && 队列 >= 3 | ENQUEUE | 避免雪崩 |
| 内存 > 80% | ENQUEUE + ALERT | 接近 OOM |
| 有匹配的空闲环境 | REUSE | 复用环境，节省时间 |

### 5.3 LLM 调用场景（L2）

仅在以下场景调用 Ollama：

1. **环境启动失败** - 分析容器日志，给出诊断建议
2. **复杂依赖解析** - 解析非标准的服务依赖关系
3. **异常模式识别** - 识别规则引擎无法处理的异常场景

---

## 六、事件驱动（替代 Kafka）

单体应用使用 **Spring Events** 实现模块间解耦：

```java
// 定义事件
public record TaskCreatedEvent(Long taskId, String serviceId, TestType testType) {}

// 发布事件
@Service
public class TaskService {
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    
    public Task createTask(TaskRequest request) {
        Task task = saveTask(request);
        eventPublisher.publishEvent(new TaskCreatedEvent(task.getId(), ...));
        return task;
    }
}

// 监听事件
@Component
public class AgentBrain {
    @EventListener
    public void onTaskCreated(TaskCreatedEvent event) {
        // 处理新任务
        taskQueue.offer(event);
    }
}
```

### 事件类型

| 事件 | 发布者 | 监听者 | 说明 |
|------|--------|--------|------|
| `TaskCreatedEvent` | TaskService | AgentBrain | 新任务入队 |
| `EnvironmentReadyEvent` | DockerActuator | TaskService | 环境就绪通知 |
| `EnvironmentFailedEvent` | DockerActuator | DiagnosisService | 触发 AI 诊断 |
| `DiagnosisCompletedEvent` | DiagnosisService | TaskService | 诊断结果回调 |

---

## 七、部署架构

### 7.1 开发环境

使用 Docker Compose 启动依赖服务：

```yaml
# docker-compose.yml
version: '3.8'
services:
  mysql:
    image: mysql:8.0
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: sentinel
      MYSQL_DATABASE: sentinel
      
  redis:
    image: redis:7.0-alpine
    ports:
      - "6379:6379"
      
  ollama:
    image: ollama/ollama:latest
    ports:
      - "11434:11434"
    volumes:
      - ollama_data:/root/.ollama
      
volumes:
  ollama_data:
```

### 7.2 应用启动

```bash
# 1. 启动依赖服务
docker-compose up -d

# 2. 拉取 AI 模型（首次）
docker exec -it ollama ollama pull qwen2.5:7b

# 3. 启动应用
./mvnw spring-boot:run
```

### 7.3 生产部署

```bash
# 打包
./mvnw clean package -DskipTests

# 运行
java -jar target/sentinel-1.0.0.jar \
  --spring.profiles.active=prod \
  --server.port=8080
```

---

## 八、安全设计

### 8.1 API 安全

- **JWT 认证**：所有 API 需携带有效 Token
- **Token 有效期**：24 小时
- **刷新机制**：支持 Token 刷新

### 8.2 容器安全

```yaml
# 容器安全配置模板
security_opt:
  - no-new-privileges:true
cap_drop:
  - ALL
read_only: true
tmpfs:
  - /tmp
```

### 8.3 敏感信息

- 数据库密码：通过环境变量注入
- AI 分析前：自动脱敏日志中的密码、密钥

---

## 九、监控与可观测性

### 9.1 健康检查

```
GET /api/health

{
  "status": "UP",
  "components": {
    "mysql": "UP",
    "redis": "UP",
    "docker": "UP",
    "ollama": "UP"
  }
}
```

### 9.2 Prometheus 指标

```
# 应用指标
sentinel_tasks_total{status="success|failed"}
sentinel_environments_active
sentinel_diagnosis_cache_hit_rate
sentinel_decision_latency_seconds

# JVM 指标（Spring Boot Actuator 自动暴露）
jvm_memory_used_bytes
jvm_threads_live
```

---

## 十、扩展路线图

当前 MVP 版本完成后，可按以下路径演进：

```
MVP (单体)
    │
    ├─ Phase 1: 功能增强
    │   ├─ 支持更多测试类型
    │   ├─ Dashboard 可视化
    │   └─ 告警通知（钉钉/邮件）
    │
    ├─ Phase 2: 性能优化
    │   ├─ 环境预热池
    │   ├─ 并行任务处理
    │   └─ 缓存优化
    │
    └─ Phase 3: 架构演进（如需）
        ├─ 拆分为微服务
        ├─ 引入 Kafka
        └─ 多集群调度
```

---

## 附录

### A. 与需求分析的对应关系

| 需求编号 | 需求描述 | 实现模块 |
|----------|----------|----------|
| FR-1 | 任务感知与解析 | AgentBrain + Perceiver |
| FR-2 | 环境状态监控 | DockerSensor + MetricsCollector |
| FR-3 | 智能决策引擎 | RuleEngine + LLMAdvisor |
| FR-4 | 容器生命周期执行 | DockerActuator + ComposeGenerator |
| FR-5 | 故障诊断 | DiagnosisService + LLMAdvisor |

### B. 技术决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| 架构模式 | 单体应用 | 适合课程项目规模，开发部署简单 |
| 消息通信 | Spring Events | 单体应用无需 Kafka 的复杂性 |
| 数据库 | 仅 MySQL | 简化运维，Redis 仅用于缓存 |
| 前端 | Thymeleaf + HTMX | 轻量级，无需前后端分离 |
