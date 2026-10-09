# Sentinel - 智能测试环境管理系统

基于智能体架构的测试环境自动化管理系统，采用"感知-决策-执行"的设计模式。

## 技术栈

- **框架**: Spring Boot 3.2+
- **持久层**: MyBatis-Plus 3.5+ + MySQL 8.0
- **缓存**: Spring Data Redis + Redis 7.0
- **容器管理**: Docker Java Client
- **AI 集成**: 支持本地 Ollama API 或 OpenAI 兼容 API
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

编辑 `.env` 文件。`JWT_SECRET` 为必填（至少 32 个字符），主配置不再提供默认值；其余项按示例填写：

```bash
# 数据库配置
DB_PASSWORD=root

# JWT 密钥（至少 32 个字符，必填）
JWT_SECRET=your-super-secret-jwt-key-change-in-production

# AI 服务配置（可选）
AI_PROVIDER=ollama  # 可选: ollama, openai, custom
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

服务信息：
- **MySQL**: `localhost:3306`
  - 数据库: `sentinel`
  - 用户名: `root`
  - 密码: `root`（可在 .env 中配置）
- **Redis**: `localhost:6379`
- **Ollama**: `localhost:11434`（可选）

数据持久化：
- MySQL 数据卷: `sentinel-mysql-data`
- Redis 数据卷: `sentinel-redis-data`
- Ollama 数据卷: `sentinel-ollama-data`

### 3. 构建项目

```bash
mvn clean package
```

### 4. 运行应用

应用启动必须提供 `JWT_SECRET`（至少 32 个字符）。主配置不再内置默认密钥，可通过环境变量提供，或使用已被 `.gitignore` 忽略的 `.env` 文件（`cp .env.example .env` 后设置 `JWT_SECRET`；`application.yml` 会导入 `optional:file:.env[.properties]`）。

```bash
# 开发环境（默认）
mvn spring-boot:run

# 测试环境
mvn spring-boot:run -Dspring-boot.run.profiles=test

# 生产环境
java -jar target/sentinel-1.0.0-SNAPSHOT.jar --spring.profiles.active=prod

# 使用环境变量覆盖配置
export JWT_SECRET=your-production-secret
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

- 应用地址: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- Actuator: http://localhost:8080/actuator
- Prometheus Metrics: http://localhost:8080/actuator/prometheus（需要管理员 Bearer Token）

## 运行测试

先启动本机 MySQL 和 Redis，再执行测试，不必额外导出环境变量：

```bash
docker compose up -d mysql redis
mvn -B -o clean test
```

不使用离线模式时可以去掉 `-o`：`mvn -B clean test`。

`src/test/resources/config/application-test.yml` 只覆盖测试 profile 的连接信息，默认指向：

- MySQL：`127.0.0.1:3306`，数据库 `sentinel`，用户名 `root`，密码 `root`（`useSSL=false`）
- Redis：`127.0.0.1:6379`，无密码
- 日志文件：`target/logs/sentinel-test.log`

可用环境变量覆盖：`DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`JWT_SECRET`。未设置 `JWT_SECRET` 时，测试使用配置中内置的、长度不少于 32 的测试密钥。

数据库集成测试在事务结束后回滚，重复运行不会在 `t_task` 留下新行。

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
├── agent/          # 智能体核心模块
├── config/         # 配置类
├── controller/     # 控制器层
├── domain/         # 领域模型
├── dto/            # 数据传输对象
├── enums/          # 枚举类
├── event/          # Spring 事件
├── mapper/         # MyBatis Mapper
├── service/        # 业务服务层
└── util/           # 工具类
```

## 配置说明

详细配置说明请参考 [docs/](docs/) 目录下的文档。

## 开发状态

🚧 项目正在开发中...

## License

Copyright © 2024 Sentinel Team
