# Sentinel 部署指南

## 📋 目录

- [环境准备](#环境准备)
- [本地开发环境](#本地开发环境)
- [生产环境部署](#生产环境部署)
- [配置说明](#配置说明)
- [常见问题](#常见问题)

## 环境准备

### 必需软件

- **JDK**: 17 或 21
- **Maven**: 3.8+
- **Docker**: 20.10+
- **Docker Compose**: 2.0+

### 可选软件

- **Ollama**: 用于本地 AI 服务（推荐用于开发环境）
- **MySQL Client**: 用于数据库管理
- **Redis CLI**: 用于缓存管理

## 本地开发环境

### 1. 克隆项目

```bash
git clone <repository-url>
cd EnvWeaver
```

### 2. 启动基础服务

使用 Docker Compose 启动 MySQL 和 Redis：

```bash
# 方式一：使用启动脚本
./start-dev.sh

# 方式二：直接使用 docker-compose
docker-compose up -d
```

### 3. 配置数据库

数据库会自动初始化，如需手动初始化：

```bash
docker-compose exec mysql mysql -uroot -proot sentinel < src/main/resources/schema.sql
```

### 4. 配置 AI 服务（可选）

#### 使用本地 Ollama

```bash
# 安装 Ollama
curl -fsSL https://ollama.com/install.sh | sh

# 拉取模型
ollama pull qwen2.5:7b

# 启动服务（默认端口 11434）
ollama serve
```

#### 使用 OpenAI API

编辑 `src/main/resources/application-dev.yml`：

```yaml
ai:
  provider: openai
  openai:
    api-key: your-openai-api-key
    model: gpt-4
```

### 5. 构建项目

```bash
mvn clean package -DskipTests
```

### 6. 运行应用

```bash
# 开发模式
mvn spring-boot:run

# 或使用 JAR 文件
java -jar target/sentinel-1.0.0-SNAPSHOT.jar
```

### 7. 访问应用

- **应用首页**: http://localhost:8080
- **API 文档**: http://localhost:8080/swagger-ui.html
- **健康检查**: http://localhost:8080/actuator/health
- **Prometheus 指标**: http://localhost:8080/actuator/prometheus

## 生产环境部署

### 1. 准备环境变量

创建 `.env` 文件：

```bash
# 数据库配置
DB_HOST=your-mysql-host
DB_PORT=3306
DB_NAME=sentinel
DB_USERNAME=sentinel
DB_PASSWORD=your-secure-password

# Redis 配置
REDIS_HOST=your-redis-host
REDIS_PORT=6379
REDIS_PASSWORD=your-redis-password

# JWT 配置
JWT_SECRET=your-very-long-and-secure-secret-key-at-least-256-bits

# AI 配置
AI_PROVIDER=openai
OPENAI_API_KEY=your-openai-api-key
OPENAI_MODEL=gpt-4

# Docker 配置
DOCKER_HOST=unix:///var/run/docker.sock
```

### 2. 构建生产镜像

```bash
# 构建 JAR 文件
mvn clean package -DskipTests

# 构建 Docker 镜像（如果有 Dockerfile）
docker build -t sentinel:latest .
```

### 3. 使用 Docker Compose 部署

创建 `docker-compose.prod.yml`：

```yaml
version: '3.8'

services:
  sentinel:
    image: sentinel:latest
    container_name: sentinel-app
    restart: unless-stopped
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DB_HOST: ${DB_HOST}
      DB_PASSWORD: ${DB_PASSWORD}
      REDIS_HOST: ${REDIS_HOST}
      REDIS_PASSWORD: ${REDIS_PASSWORD}
      JWT_SECRET: ${JWT_SECRET}
      OPENAI_API_KEY: ${OPENAI_API_KEY}
    ports:
      - "8080:8080"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - ./logs:/var/log/sentinel
    depends_on:
      - mysql
      - redis
    networks:
      - sentinel-network

  mysql:
    image: mysql:8.0
    # ... (参考 docker-compose.yml)

  redis:
    image: redis:7.0-alpine
    # ... (参考 docker-compose.yml)
```

启动服务：

```bash
docker-compose -f docker-compose.prod.yml up -d
```

### 4. 健康检查

```bash
# 检查应用状态
curl http://localhost:8080/actuator/health

# 查看日志
docker-compose -f docker-compose.prod.yml logs -f sentinel
```

## 配置说明

### 数据库配置

```yaml
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
```

### Redis 配置

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}
      password: ${REDIS_PASSWORD}
```

### AI 提供商配置

支持三种 AI 提供商：

1. **Ollama（本地）**
```yaml
ai:
  provider: ollama
  ollama:
    base-url: http://localhost:11434
    model: qwen2.5:7b
```

2. **OpenAI**
```yaml
ai:
  provider: openai
  openai:
    base-url: https://api.openai.com/v1
    api-key: ${OPENAI_API_KEY}
    model: gpt-4
```

3. **自定义 OpenAI 兼容 API**
```yaml
ai:
  provider: custom
  custom:
    base-url: ${CUSTOM_AI_BASE_URL}
    api-key: ${CUSTOM_AI_API_KEY}
    model: ${CUSTOM_AI_MODEL}
```

## 常见问题

### 1. 数据库连接失败

**问题**: `Communications link failure`

**解决方案**:
- 检查 MySQL 是否正常运行：`docker-compose ps`
- 检查数据库配置是否正确
- 确保防火墙允许 3306 端口

### 2. Redis 连接失败

**问题**: `Unable to connect to Redis`

**解决方案**:
- 检查 Redis 是否正常运行：`docker-compose exec redis redis-cli ping`
- 检查 Redis 密码配置
- 确保防火墙允许 6379 端口

### 3. Docker 连接失败

**问题**: `Cannot connect to Docker daemon`

**解决方案**:
- 确保 Docker 服务正在运行
- 检查 Docker socket 权限：`ls -l /var/run/docker.sock`
- 将用户添加到 docker 组：`sudo usermod -aG docker $USER`

### 4. AI 服务不可用

**问题**: `AI service unavailable`

**解决方案**:
- 检查 AI 提供商配置
- 如使用 Ollama，确保服务正在运行：`curl http://localhost:11434/api/tags`
- 如使用 OpenAI，检查 API Key 是否有效
- 查看应用日志获取详细错误信息

### 5. 端口冲突

**问题**: `Port already in use`

**解决方案**:
- 修改 `docker-compose.yml` 中的端口映射
- 或停止占用端口的服务

## 监控和维护

### 查看日志

```bash
# 应用日志
tail -f logs/sentinel-dev.log

# Docker 容器日志
docker-compose logs -f
```

### 备份数据

```bash
# 备份 MySQL 数据
docker-compose exec mysql mysqldump -uroot -proot sentinel > backup.sql

# 备份 Redis 数据
docker-compose exec redis redis-cli SAVE
cp docker/redis/data/dump.rdb backup/
```

### 清理资源

```bash
# 停止服务
docker-compose stop

# 删除容器（保留数据）
docker-compose down

# 删除容器和数据卷（谨慎使用）
docker-compose down -v
```

## 性能优化

### JVM 参数

```bash
java -Xms512m -Xmx2g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -jar target/sentinel-1.0.0-SNAPSHOT.jar
```

### 数据库优化

- 定期清理过期的诊断缓存
- 为常用查询字段添加索引
- 配置合适的连接池大小

### Redis 优化

- 设置合理的内存限制
- 配置持久化策略
- 定期清理过期键

## 安全建议

1. **修改默认密码**: 生产环境必须修改所有默认密码
2. **使用 HTTPS**: 配置 SSL/TLS 证书
3. **限制访问**: 使用防火墙限制端口访问
4. **定期更新**: 及时更新依赖和镜像版本
5. **备份数据**: 定期备份数据库和配置文件

## 技术支持

如遇到问题，请：
1. 查看应用日志
2. 检查配置文件
3. 参考本文档的常见问题部分
4. 提交 Issue 到项目仓库

---

**版本**: 1.0.0  
**更新时间**: 2024-12-22
