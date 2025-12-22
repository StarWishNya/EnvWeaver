# Sentinel API 使用示例

本文档提供 Sentinel 系统的 API 使用示例。

## 📋 目录

- [认证 API](#认证-api)
- [任务管理 API](#任务管理-api)
- [健康检查 API](#健康检查-api)

## 基础信息

- **Base URL**: `http://localhost:8080`
- **Content-Type**: `application/json`
- **认证方式**: Bearer Token (JWT)

## 认证 API

### 1. 用户登录

**请求**:
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "admin",
    "password": "admin123"
  }'
```

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "tokenType": "Bearer",
    "expiresIn": 86400,
    "username": "admin",
    "role": "ADMIN"
  },
  "timestamp": "2024-12-22T12:00:00",
  "fromCache": false
}
```

### 2. 用户登出

**请求**:
```bash
curl -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN"
```

**响应**:
```json
{
  "code": 200,
  "message": "登出成功",
  "data": null,
  "timestamp": "2024-12-22T12:00:00",
  "fromCache": false
}
```

## 任务管理 API

### 1. 创建任务

**请求**:
```bash
curl -X POST http://localhost:8080/api/tasks \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "taskCode": "build-1234",
    "serviceId": "user-service",
    "testType": "INTEGRATION_TEST",
    "dependencies": "[\"mysql:8.0\", \"redis:7.0\"]",
    "priority": 5
  }'
```

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "taskCode": "build-1234",
    "serviceId": "user-service",
    "testType": "INTEGRATION_TEST",
    "dependencies": "[\"mysql:8.0\", \"redis:7.0\"]",
    "status": "PENDING",
    "priority": 5,
    "environmentId": null,
    "errorMessage": null,
    "startedAt": null,
    "completedAt": null,
    "createdAt": "2024-12-22T12:00:00",
    "updatedAt": "2024-12-22T12:00:00"
  },
  "timestamp": "2024-12-22T12:00:00",
  "fromCache": false
}
```

### 2. 获取任务详情

**请求**:
```bash
curl -X GET http://localhost:8080/api/tasks/1 \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN"
```

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "taskCode": "build-1234",
    "serviceId": "user-service",
    "testType": "INTEGRATION_TEST",
    "status": "RUNNING",
    "priority": 5,
    "startedAt": "2024-12-22T12:01:00",
    "createdAt": "2024-12-22T12:00:00",
    "updatedAt": "2024-12-22T12:01:00"
  },
  "timestamp": "2024-12-22T12:05:00",
  "fromCache": false
}
```

### 3. 分页查询任务列表

**请求**:
```bash
# 查询所有任务
curl -X GET "http://localhost:8080/api/tasks?pageNum=1&pageSize=10" \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN"

# 按状态过滤
curl -X GET "http://localhost:8080/api/tasks?pageNum=1&pageSize=10&status=RUNNING" \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN"
```

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "records": [
      {
        "id": 1,
        "taskCode": "build-1234",
        "status": "RUNNING",
        "priority": 5,
        "createdAt": "2024-12-22T12:00:00"
      }
    ],
    "total": 1,
    "size": 10,
    "current": 1,
    "pages": 1
  },
  "timestamp": "2024-12-22T12:05:00",
  "fromCache": false
}
```

### 4. 根据状态查询任务

**请求**:
```bash
curl -X GET http://localhost:8080/api/tasks/status/PENDING \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN"
```

**响应**:
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 2,
      "taskCode": "build-1235",
      "status": "PENDING",
      "priority": 3,
      "createdAt": "2024-12-22T12:10:00"
    }
  ],
  "timestamp": "2024-12-22T12:15:00",
  "fromCache": false
}
```

### 5. 取消任务

**请求**:
```bash
curl -X POST http://localhost:8080/api/tasks/1/cancel \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN"
```

**响应**:
```json
{
  "code": 200,
  "message": "任务已取消",
  "data": null,
  "timestamp": "2024-12-22T12:20:00",
  "fromCache": false
}
```

### 6. 更新任务状态（管理员）

**请求**:
```bash
curl -X PUT "http://localhost:8080/api/tasks/1/status?status=SUCCESS" \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN"
```

**响应**:
```json
{
  "code": 200,
  "message": "任务状态已更新",
  "data": null,
  "timestamp": "2024-12-22T12:25:00",
  "fromCache": false
}
```

## 健康检查 API

### 1. 应用健康检查

**请求**:
```bash
curl -X GET http://localhost:8080/api/health
```

**响应**:
```json
{
  "status": "UP",
  "timestamp": "2024-12-22T12:00:00",
  "application": "Sentinel",
  "version": "1.0.0-SNAPSHOT"
}
```

### 2. Actuator 健康检查

**请求**:
```bash
curl -X GET http://localhost:8080/actuator/health
```

**响应**:
```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "MySQL",
        "validationQuery": "isValid()"
      }
    },
    "redis": {
      "status": "UP",
      "details": {
        "version": "7.0.0"
      }
    },
    "diskSpace": {
      "status": "UP",
      "details": {
        "total": 500000000000,
        "free": 250000000000,
        "threshold": 10485760
      }
    }
  }
}
```

### 3. Prometheus 指标

**请求**:
```bash
curl -X GET http://localhost:8080/actuator/prometheus
```

**响应**:
```text
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap",id="PS Eden Space",} 1.234567E8
...
```

## 错误响应

### 认证失败

```json
{
  "code": 1007,
  "message": "用户名或密码错误，剩余尝试次数: 2",
  "data": null,
  "timestamp": "2024-12-22T12:00:00",
  "fromCache": false
}
```

### 账户锁定

```json
{
  "code": 1005,
  "message": "账户已被锁定",
  "data": null,
  "timestamp": "2024-12-22T12:00:00",
  "fromCache": false
}
```

### Token 过期

```json
{
  "code": 1002,
  "message": "Token 已过期",
  "data": null,
  "timestamp": "2024-12-22T12:00:00",
  "fromCache": false
}
```

### 权限不足

```json
{
  "code": 1008,
  "message": "权限不足",
  "data": null,
  "timestamp": "2024-12-22T12:00:00",
  "fromCache": false
}
```

### 资源不存在

```json
{
  "code": 2001,
  "message": "任务不存在",
  "data": null,
  "timestamp": "2024-12-22T12:00:00",
  "fromCache": false
}
```

### 参数校验失败

```json
{
  "code": 5004,
  "message": "参数校验失败",
  "data": null,
  "timestamp": "2024-12-22T12:00:00",
  "fromCache": false
}
```

## 完整示例：创建并查询任务

```bash
#!/bin/bash

# 1. 登录获取 Token
echo "1. 登录..."
LOGIN_RESPONSE=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}')

TOKEN=$(echo $LOGIN_RESPONSE | jq -r '.data.accessToken')
echo "Token: $TOKEN"
echo ""

# 2. 创建任务
echo "2. 创建任务..."
CREATE_RESPONSE=$(curl -s -X POST http://localhost:8080/api/tasks \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "taskCode": "build-1234",
    "serviceId": "user-service",
    "testType": "INTEGRATION_TEST",
    "dependencies": "[\"mysql:8.0\", \"redis:7.0\"]",
    "priority": 5
  }')

TASK_ID=$(echo $CREATE_RESPONSE | jq -r '.data.id')
echo "任务 ID: $TASK_ID"
echo ""

# 3. 查询任务详情
echo "3. 查询任务详情..."
curl -s -X GET http://localhost:8080/api/tasks/$TASK_ID \
  -H "Authorization: Bearer $TOKEN" | jq '.'
echo ""

# 4. 查询任务列表
echo "4. 查询任务列表..."
curl -s -X GET "http://localhost:8080/api/tasks?pageNum=1&pageSize=10" \
  -H "Authorization: Bearer $TOKEN" | jq '.'
```

## 使用 Postman

1. 导入 Postman Collection（如果提供）
2. 设置环境变量：
   - `base_url`: `http://localhost:8080`
   - `access_token`: 登录后获取的 Token
3. 在请求头中使用 `{{access_token}}`

## 使用 Swagger UI

访问 http://localhost:8080/swagger-ui.html 可以：
- 查看所有 API 接口
- 在线测试 API
- 查看请求/响应模型

---

**提示**: 
- 所有需要认证的接口都需要在请求头中携带 `Authorization: Bearer YOUR_ACCESS_TOKEN`
- Token 有效期为 24 小时
- 登录失败 3 次后账户将被锁定 15 分钟
