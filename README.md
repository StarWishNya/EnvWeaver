# Sentinel - 智能测试环境管理系统

基于智能体架构的测试环境自动化管理系统，采用"感知-决策-执行"的设计模式。

## 技术栈

- **框架**: Spring Boot 3.2+
- **持久层**: MyBatis-Plus 3.5+ + MySQL 8.0
- **缓存**: Spring Data Redis + Redis 7.0
- **容器管理**: Docker Java Client
- **AI 集成**: OpenAI 兼容、Anthropic 兼容与 Ollama，按 `AI_PROVIDER_ORDER` 依次回退
- **安全**: Spring Security + JWT
- **监控**: Spring Boot Actuator + Prometheus

## 快速开始

### 环境要求

- JDK 17 或 21
- Maven 3.8+
- Docker & Docker Compose
- Ollama (可选，用于本地 AI 服务)

### 1. 配置环境变量

复制环境变量示例文件并配置：

```bash
cp .env.example .env
```

编辑 `.env` 文件。`JWT_SECRET` 为必填（至少 32 个字符），主配置不再提供默认值。`.env.example` 里的 `JWT_SECRET` 带 `change_me`，启动检查会拒绝它，必须换成随机值（`openssl rand -base64 48`）。Docker Compose 不会给 MySQL 或 Redis 填默认密码：`.env` 里必须有非空的 `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`REDIS_PASSWORD`，否则容器拒绝启动。复制后把示例里的 `change_me_*` 都换成随机值：

```bash
# Compose：MySQL / Redis / Ollama 只绑定 127.0.0.1。下面三项必填，没有默认密码
MYSQL_ROOT_PASSWORD=your_mysql_root_password
MYSQL_PASSWORD=your_mysql_user_password
REDIS_PASSWORD=your_redis_password

# 应用连接。开发配置默认用户名 root，DB_PASSWORD 需与 MYSQL_ROOT_PASSWORD 相同
DB_USERNAME=root
DB_PASSWORD=your_mysql_root_password

# JWT 密钥（至少 32 个字符，必填）。不要保留 change_me 示例。
# openssl rand -base64 48
JWT_SECRET=change_me_replace_this_jwt_secret_min_32_characters

# 可选。非空时启动会把 admin 密码改成这个值（至少 12 个字符）。不要用示例。
# SENTINEL_ADMIN_PASSWORD=change_me_admin_min_12

# AI 服务配置（可选）。DeepSeek 优先、本地 Ollama 兜底：
AI_PROVIDER_ORDER=deepseek,ollama
DEEPSEEK_API_KEY=your_deepseek_api_key
```

> 📖 **配置说明**: 查看 [配置文档](docs/CONFIGURATION.md) 了解所有配置项

### 2. 启动本地开发环境

使用 Docker Compose 启动 MySQL、Redis 和 Ollama：

```bash
# 方式一：使用启动脚本（推荐）
./scripts/start-dev.sh

# 方式二：直接使用 docker-compose
docker-compose up -d
```

MySQL、Redis、Ollama 都只绑定 `127.0.0.1`（`BIND_ADDRESS` 可覆盖）。端口对应 `MYSQL_PORT`（默认 3306）、`REDIS_PORT`（默认 6379）、`OLLAMA_PORT`（默认 11434）。

- **MySQL**: `127.0.0.1:3306`
  - 数据库: `sentinel`（`MYSQL_DATABASE`，默认 `sentinel`）
  - root 密码: `.env` 中的 `MYSQL_ROOT_PASSWORD`（必填；Compose 没有默认密码）
  - 额外账号: `MYSQL_USER`（默认 `sentinel`），密码为 `MYSQL_PASSWORD`（必填）
- **Redis**: `127.0.0.1:6379`，密码为 `REDIS_PASSWORD`（必填）
- **Ollama**: `127.0.0.1:11434`（可选）

应用使用 `DB_USERNAME`（开发配置默认 `root`）、`DB_PASSWORD` 和 `REDIS_PASSWORD`。`DB_PASSWORD` 必须与 `MYSQL_ROOT_PASSWORD` 相同。未设置时，`src/main/resources/application-dev.yml` 会把数据库密码回退成字面量 `root`、把 Redis 密码回退成空字符串；这两项都连不上当前 Compose。

数据持久化：
- MySQL 数据卷: `sentinel-mysql-data`
- Redis 数据卷: `sentinel-redis-data`
- Ollama 数据卷: `sentinel-ollama-data`

### 3. 构建项目

```bash
mvn clean package
```

### 4. 运行应用

应用启动必须提供 `JWT_SECRET`（至少 32 个字符，至少 8 个不同字符，且不能是示例或占位符）。主配置不再内置默认密钥，可通过环境变量提供，或使用已被 `.gitignore` 忽略的 `.env` 文件（`cp .env.example .env` 后设置 `JWT_SECRET`；`src/main/resources/application.yml` 会导入 `optional:file:.env[.properties]`）。生产环境还要换掉种子口令，见 [首次登录与生产检查清单](#首次登录与生产检查清单)。

```bash
# 开发环境（默认）
mvn spring-boot:run

# 测试环境
mvn spring-boot:run -Dspring-boot.run.profiles=test

# 生产环境
java -jar target/sentinel-1.0.0-SNAPSHOT.jar --spring.profiles.active=prod

# 使用环境变量覆盖配置
export JWT_SECRET="$(openssl rand -base64 48)"
export SENTINEL_ADMIN_PASSWORD="$(openssl rand -base64 18)"
export DB_PASSWORD=your-db-password
java -jar target/sentinel-1.0.0-SNAPSHOT.jar --spring.profiles.active=prod
```

### 5. 停止服务

```bash
# 使用停止脚本（推荐）
./scripts/stop-dev.sh

# 或直接使用 docker-compose
docker-compose down

# 停止并删除数据卷（谨慎使用）
docker-compose down -v
```

### 访问应用

- Web 界面: http://localhost:8080/ （开发环境在改口令前可用种子账号 `admin` / `admin123` 登录，可管理任务、查看环境与日志、请求 AI 诊断、看监控。这是公开默认口令，启动时会告警；生产环境会拒绝启动）
- 应用地址: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- Actuator: http://localhost:8080/actuator
- Prometheus Metrics: http://localhost:8080/actuator/prometheus（需要管理员 Bearer Token）

## 首次登录与生产检查清单

`schema.sql` 会插入管理员 `admin`，口令是公开的 `admin123`。`data.sql` 还会插入普通用户 `user` / `user123`。这两条口令只适合本机第一次打开界面，不能留在生产库里。

启动类 `CredentialStartupGuard` 读取 `sentinel.security.credential-check`：

| Profile | 值 | 行为 |
| --- | --- | --- |
| 默认（`application.yml`）和 `dev` | `warn` | 每个问题打一条 WARN，启动继续 |
| `prod` | `fail` | 拒绝启动，日志写明是哪一项、怎么改 |
| `test` | `off` | 不检查，除非测试自己把该属性改成 `warn` 或 `fail` |

YAML 里 `off` 必须写成 `"off"`。不加引号时 YAML 1.1 会把它读成布尔值 `false`，启动会认为配置无效。

命令行 `--sentinel.security.credential-check=fail` 可以覆盖配置文件。检查两类问题，日志里不会出现密钥、新口令或 BCrypt 哈希：

1. `JWT_SECRET` 命中 `.env.example` 的示例值，或包含 `change-in-production`、`your-super-secret`、`changeme`、`change_me`（忽略大小写），或不同字符少于 8 个。换成 `openssl rand -base64 48` 的结果。
2. 任一启用用户的密码哈希能对上 `admin123`、`user123`、`admin`、`password`、`123456`。

管理员口令用环境变量 `SENTINEL_ADMIN_PASSWORD`（配置项 `sentinel.security.admin-password`）处理：值非空时，启动把用户 `admin` 的密码写成该值的 BCrypt；库里没有 `admin` 就创建，角色 `ADMIN`。长度必须不少于 12，更短则直接拒绝启动。不要把真实口令写进日志或仓库。

生产上线前核对：

- [ ] `SPRING_PROFILES_ACTIVE=prod`（`credential-check` 为 `fail`）
- [ ] `JWT_SECRET` 已换成随机值，不是 `.env.example` 里的 `change_me` 示例
- [ ] 已设置至少 12 位的 `SENTINEL_ADMIN_PASSWORD`，或确认 `admin` 已不再使用 `admin123`
- [ ] `user` / `user123` 已改掉或该用户已禁用
- [ ] MySQL、Redis 密码已换成随机值，不使用示例里的 `change_me_*`
- [ ] 启动日志里没有 `【凭据安全检查】`

开发环境不设置 `SENTINEL_ADMIN_PASSWORD` 时，仍可用 `admin` / `admin123` 登录，同时会看到一条警告。冒烟脚本 `scripts/smoke-test.sh` 和 `scripts/ai-smoke-test.sh` 默认也用这组口令；重置之后要改脚本或自行登录。

## 运行测试

Compose 里的 MySQL 和 Redis 只监听 `127.0.0.1`，并且依赖 `.env` 中的 `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`REDIS_PASSWORD`（均无默认值）。写好这三项后再启动依赖并执行测试：

```bash
docker compose up -d mysql redis
mvn -B -o clean test
```

不使用离线模式时可以去掉 `-o`：`mvn -B clean test`。

`src/test/resources/config/application-test.yml` 只覆盖测试 profile 的连接信息。未设置环境变量时，该文件回退到 MySQL `127.0.0.1:3306`、数据库 `sentinel`、用户名 `root`、密码字面量 `root`（`useSSL=false`），以及 Redis `127.0.0.1:6379`、空密码。这是测试配置的回退值，不是 Compose 的密码。对着本仓库的 Compose 跑测试时，把 `DB_PASSWORD` 设成与 `MYSQL_ROOT_PASSWORD` 相同，并设置同一个 `REDIS_PASSWORD`。这两个键如果已经写在仓库根 `.env`，会由 `src/main/resources/application.yml` 导入，不必再 export。

日志文件：`target/logs/sentinel-test.log`。

可用环境变量覆盖：`DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`JWT_SECRET`。未设置 `JWT_SECRET` 时，测试使用配置中内置的、长度不少于 32 的测试密钥。测试 profile 的 `sentinel.security.credential-check` 为 `off`，种子口令不会让测试上下文启动失败；单个测试可以把该属性设为 `warn` 或 `fail`。

数据库集成测试在事务结束后回滚，重复运行不会在 `t_task` 留下新行。

默认测试排除 `@Tag("live")` 的在线用例。只跑 DeepSeek 在线协议测试（需要 `DEEPSEEK_API_KEY`，也可写在仓库根 `.env`）见下方 [AI 服务](#ai-服务)。

## 冒烟测试

应用起来之后，在仓库根目录执行：

```bash
BASE_URL=http://localhost:8080 bash scripts/smoke-test.sh
```

`BASE_URL` 默认是 `http://localhost:8080`。本机 8080 已被占用时，用 `--server.port=18080` 启动，再执行：

```bash
BASE_URL=http://localhost:18080 bash scripts/smoke-test.sh
```

脚本使用 `curl`。本机有 `jq` 时用它读取令牌和任务字段，没有则退回 `grep`。健康检查、未登录拒绝、管理员登录、创建/查询/取消任务、非法创建返回 400、登出后旧令牌失效、刷新令牌不能访问受保护接口，任一步失败会打印 `FAIL` 并以非 0 退出。任务编码每次运行唯一，可以重复执行。

## 项目结构

```
src/main/java/com/sentinel/
├── ai/             # 多提供商 AI 客户端与路由
├── config/         # 配置类
├── controller/     # 控制器层
├── domain/         # 领域模型
├── dto/            # 数据传输对象
├── enums/          # 枚举类
├── event/          # Spring 事件
├── lifecycle/      # 任务生命周期与环境回收
├── mapper/         # MyBatis Mapper
├── monitoring/     # 容器资源采集
├── service/        # 业务服务层
└── util/           # 工具类
```

## AI 服务

诊断按 `AI_PROVIDER_ORDER` 里的名字依次调用（逗号分隔，前者优先）。没配好 base-url / api-key 的项会跳过，调用失败会试下一个。没设置 `AI_PROVIDER_ORDER` 时，仍可读旧的单个 `AI_PROVIDER`。

三种协议：

- `openai`：OpenAI 兼容，`POST {base-url}/chat/completions`，`Authorization: Bearer`
- `anthropic`：Anthropic 兼容，`POST {base-url}/v1/messages`，请求头 `x-api-key` 与 `anthropic-version`
- `ollama`：`POST {base-url}/api/chat`（只需 base-url）

DeepSeek 同时提供这两种云端协议，本地 Ollama 可以兜底：

```bash
AI_PROVIDER_ORDER=deepseek,ollama
DEEPSEEK_API_KEY=your_deepseek_api_key
DEEPSEEK_MODEL=deepseek-flash
```

`deepseek` 走 OpenAI 协议（`https://api.deepseek.com`），`deepseek-anthropic` 走 Anthropic 协议（`https://api.deepseek.com/anthropic`）。要改用后者，把 `deepseek-anthropic` 写进 `AI_PROVIDER_ORDER`。

新增一个 OpenAI 或 Anthropic 兼容提供商：在 `src/main/resources/application.yml` 的 `ai.providers` 下加一项，写上 `protocol`、`base-url`、`api-key`、`model`，再把这个名字放进 `AI_PROVIDER_ORDER`。每个提供商还可以设 `timeout`（秒，默认 60）、`max-tokens`（默认 2000）、`temperature`（默认 0.3）和 `enabled`（默认 true）。

`thinking` 可选，取值 `disabled`、`enabled`，或不写（`unset`）。不写时请求里不带该字段，避免官方 OpenAI 拒绝未知参数。`disabled` 时 OpenAI / Anthropic 发送 `"thinking":{"type":"disabled"}`，Ollama 发送 `"think": false`。`enabled` 时前两者发送 `{"type":"enabled"}`；Anthropic 还要求 `budget_tokens` 小于 `max_tokens`，只在 max-tokens 大于 1024 时附带 `max(1024, maxTokens/2)`，否则不发送 thinking。Ollama 的 `enabled` 是 `"think": true`。`deepseek` 和 `deepseek-anthropic` 默认 `thinking: ${DEEPSEEK_THINKING:disabled}`，因为 `deepseek-flash` 会先推理，思考占满 max-tokens 时正文会是空的。空正文（OpenAI 的空白 `content`、Anthropic 只有 thinking 块、Ollama 空白 `content`）算调用失败，路由会试下一个提供商。

部分「coding plan / 编程套餐」只允许指定的编程工具调用，不能当作通用 API。接入前请核对提供商条款。

健康检查 `/actuator/health` 的 `ai` 组件只报告每个提供商是否已配置、模型名和协议，不带密钥，也不在每次检查时访问模型服务。一个都没配好时该项是 `UNKNOWN`，不会把整体健康打成 `DOWN`。

默认 `mvn test` 排除 `@Tag("live")`。只跑 DeepSeek 在线测试（读环境变量 `DEEPSEEK_API_KEY`，没有则解析仓库根 `.env`）：

```bash
mvn -B -o test -Dgroups=live -Dsurefire.excludedGroups=
```

## AI 诊断

诊断接口需要登录。`POST /api/diagnosis/{taskId}` 在任务不存在时返回 HTTP 404，业务码 `2001`。还没有请求过诊断时，`GET /api/diagnosis/{taskId}` 返回 HTTP 404，业务码 `4002`。

请求诊断（`log` 可省略；`forceRefresh` 默认 false）：

```bash
curl -X POST "$BASE/api/diagnosis/$TASK_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"forceRefresh":false,"log":"可选，直接诊断这段日志"}'
```

省略 `log` 时，输入是任务的 `errorMessage`，再加上该任务环境里每个容器的尾部 200 行日志。Docker 读取失败会跳过该容器。两处都没有文本时返回 HTTP 400。响应是 HTTP 202，`data` 只有 `diagnosisId` 和 `status`（`PENDING`、`PROCESSING` 或 `COMPLETED`）。相同日志（MD5）且没有强制刷新时，`status` 直接是 `COMPLETED`，外层 `fromCache` 为 true。模型调用在诊断专用有界线程池里执行。

`GET /api/diagnosis/{taskId}` 的 `data` 包含 `id`、`taskId`、`errorType`、`severity`、`summary`、`rootCause`、`suggestions`、`confidence`、`fromCache`，以及 `status`、`provider`、`model`。失败时 `status` 为 `FAILED`，并带 `error`。`data.fromCache` 与响应体外层的 `fromCache` 是同一个值。

`errorType` 为 `RESOURCE_EXHAUSTED`、`NETWORK_ERROR`、`DEPENDENCY_FAILURE`、`TIMEOUT`、`CONFIG_ERROR`、`UNKNOWN`。`severity` 为 `LOW`、`MEDIUM`、`HIGH`、`CRITICAL`。非法值分别落到 `UNKNOWN` 和 `MEDIUM`，`confidence` 钳制到 0 到 1。模型被要求只返回这个 JSON（中文文本）。解析失败时 `summary` 取原文前 200 字，`rootCause` 取截断后的原文，`errorType` 为 `UNKNOWN`。

相同日志的结果写在 `t_diagnosis_cache`。`root_cause` 截断到 500 字；`solutions` 是建议字符串的 JSON 数组；`possible_reasons` 是对象 JSON：`errorType`、`severity`、`summary`、`confidence`、`provider`、`model`。每个任务的进行状态在 Redis，TTL 为 `cache.diagnosis-ttl`。

对已启动的应用（例如 `AI_PROVIDER_ORDER=deepseek,ollama`）：

```bash
BASE_URL=http://localhost:8080 bash scripts/ai-smoke-test.sh
```

脚本用 `admin` / `admin123` 登录，创建唯一任务，提交一段 MySQL `3306` 连接被拒绝、随后 `ApplicationContext` 启动失败的 Spring 日志，并在 90 秒内轮询到 `COMPLETED`。若已经用 `SENTINEL_ADMIN_PASSWORD` 重置过 admin，需要改脚本里的口令，否则登录失败。

## 任务、环境与监控接口

除 `GET /api/metrics/health` 外都需要登录。资源不存在时返回 HTTP 404。

| 接口 | 说明 |
| --- | --- |
| `GET /api/auth/me` | 当前用户 `id`、`username`、`email`、`role` |
| `GET /api/tasks/{taskId}/status` | `taskId`、`status`、`progress`（按状态推算：PENDING 0、QUEUED 10、RUNNING 50、结束态 100） |
| `POST /api/tasks` | 创建任务，返回 HTTP 201。`dependencies` 为字符串数组，如 `["mysql","redis"]` |
| `GET /api/tasks?page=0&size=20&status=&testType=` | 分页列表，`page` 从 0 开始，`size` 取 1 到 500，数据在 `data.content` |
| `DELETE /api/tasks/{taskId}` | 取消任务（旧的 `POST /api/tasks/{taskId}/cancel` 仍可用，已弃用） |
| `GET /api/tasks/{taskId}/logs?tail=100&since=` | 任务环境内各容器的尾部日志，`tail` 取 1 到 5000；`since` 为 ISO-8601 时间，交给 Docker 过滤 |
| `GET /api/environments?status=` | 环境列表，按 id 倒序，最多 500 条。状态为 `CREATING`、`READY`、`BUSY`、`RELEASING`、`IDLE`、`ERROR`、`RETAINED`（任务失败后保留排查） |
| `GET /api/environments/{envId}` | 环境详情，`portMappings` 与 `resourceUsage` 以对象返回。`resourceUsage` 为 `{cpu, memory, sampledAt}`：CPU 单位为核，内存单位为 MB |
| `POST /api/environments/{envId}/release` | 停止并删除环境内容器，环境变为 `IDLE`。`RETAINED` 也可以释放。已是 `IDLE` 或 `RELEASING` 时返回 400；有容器删除失败时环境变为 `ERROR` 并返回 503，可重试 |
| `GET /api/metrics` | 主机 CPU、内存、磁盘与 Docker 容器数 |
| `GET /api/metrics/health` | 匿名可访问，`{status, components:{db, redis, docker}}`；任一项 `DOWN` 时返回 503 |

## 任务生命周期

创建任务后，应用自动为它准备测试环境：

1. 按 `dependencies` 启动容器。内置 `mysql`、`redis`、`postgres`、`mongodb`、`rabbitmq`、`nginx`，可在 `sentinel.provisioning.dependencies` 中覆盖或新增。主机端口从 `sentinel.provisioning.port-range`（默认 30000-39999）分配，结果在环境的 `portMappings` 中。
2. 容器端口可连接后，环境变为 `BUSY`，任务变为 `RUNNING`。依赖名未知或容器启动失败时，任务变为 `FAILED`，`errorMessage` 写明原因。
3. 外部 CI 用 `PUT /api/tasks/{taskId}/status?status=SUCCESS|FAILED` 回报结果，或用 `DELETE` 取消。
4. `SUCCESS` 和取消会立即释放环境。`FAILED` 会先自动请求 AI 诊断（此时容器日志还在），再把环境标为 `RETAINED`，保留 `sentinel.lifecycle.failed-retention`（默认 30 分钟）供排查。环境创建本身失败时，环境保持 `ERROR`，同样在保留期后回收。
5. 后台每分钟回收已过期的环境（默认存活 `sentinel.provisioning.ttl` 2 小时），并补扫因重启遗漏的 `PENDING` 任务。

`sentinel.lifecycle.auto-provision` 和 `auto-diagnose` 可分别关闭自动供给和失败自动诊断。测试 profile 中两者都关闭。

`READY`、`BUSY`、`RETAINED` 的环境每 `sentinel.monitoring.resource-usage.interval`（默认 30 秒）通过 `docker stats` 采集一次，各容器汇总后写入 `resourceUsage`。环境变为 `IDLE` 后清空。设置 `sentinel.monitoring.resource-usage.enabled=false` 可关闭采集。

## 配置说明

详细配置说明请参考 [docs/](docs/) 目录下的文档。

## 开发状态

🚧 项目正在开发中...

## License

Copyright © 2024 Sentinel Team
