# Sentinel 用户测试报告

## 环境与复现

- 应用：Spring Boot 3.1.11 / Java 17，开发 profile，端口 `18080`（`8080` 被无关容器占用）。
- MySQL：容器 `sentinel-mysql`，`127.0.0.1:3306`，库 `sentinel`，用户 `root` / 密码 `root`，健康检查 healthy。
- Redis：容器 `sentinel-redis`，`127.0.0.1:6379`，无密码，healthy。
- Ollama：`127.0.0.1:11434` 连接被拒绝。启动日志没有调用 Ollama，进程没有因此退出。当前也没有对外诊断接口可以触发降级。
- Docker：客户端按 `unix:///var/run/docker.sock` 初始化成功。Actuator 健康检查没有 docker 组件。
- 密钥：仓库根目录 `.env`（已被 `.gitignore` 忽略）提供长度 64 的 `JWT_SECRET`，数据库密码 `root`。未设置 `REDIS_PASSWORD`。
- 启动：`mvn -B -o -DskipTests package`，然后 `java -jar target/sentinel-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev --server.port=18080`。
- 冒烟：`BASE_URL=http://localhost:18080 bash scripts/smoke-test.sh`。

下面的 curl 都把 `BASE` 设为 `http://127.0.0.1:18080`。需要令牌时先登录：

```bash
curl -sS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'
```

修复前的「实际」来自当时正在跑的旧进程；修复后用同一条 curl 复核过。

## 缺陷

| ID | 严重性 | 标题 | 状态 |
| --- | --- | --- | --- |
| D01 | high | 坏输入、错误方法、类型不匹配返回 500 | 已修复 |
| D02 | high | 重复任务编码返回 500 | 已修复 |
| D03 | high | 超长字段或非法 JSON 依赖返回 500 | 已修复 |
| D04 | high | 黑名单 WARN 打印完整 JWT | 已修复 |
| D05 | high | 开发日志打印用户密码哈希 | 已修复 |
| D06 | medium | 负数分页返回记录但 total 为 0 | 已修复 |
| D07 | medium | 未登录访问返回空 body 的 403 | 已修复 |
| D08 | medium | 已登录访问不存在的路径返回空 403 | 已修复 |
| D09 | medium | 已配置的 CORS 没有生效 | 已修复 |
| D10 | medium | 文档中的 `/swagger-ui.html` 未匿名放行 | 已修复 |
| D11 | medium | 匿名健康检查展开磁盘与组件详情 | 已修复 |
| D12 | medium | 任意字符串都能写成任务状态 | 已修复 |
| D13 | medium | 校验失败不返回字段错误，非法类型和优先级能入库 | 已修复 |
| D14 | low | Redis 故障时黑名单日志带上完整令牌键 | 已修复 |
| U01 | medium | 任务不存在是 HTTP 400 而不是 404 | 已修复（第二轮） |
| U02 | medium | 密码错误和锁定是 HTTP 400 而不是 401 | 已修复（第二轮） |
| U03 | medium | `/actuator/prometheus` 无需认证 | 已修复（第二轮） |
| U04 | low | `pageSize` 大于 500 被静默截成 500 | 未修复 |
| U05 | low | 启动时 Thymeleaf 找不到模板目录 | 未修复 |
| U06 | low | `SecurityProperties.whitelistPaths` 没有被使用 | 已修复 |
| U07 | low | `/api/health` 不检查 MySQL / Redis | 未修复 |

### D01 坏输入返回 500

严重性 high。修复前这些请求的 HTTP 状态都是 500，body 为 `{"code":5000,"message":"系统错误"}`。

```bash
curl -sS -D - -o /dev/null -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{bad'
curl -sS -D - -o /dev/null -X POST "$BASE/api/auth/login" -d '{"username":"admin","password":"admin123"}'
curl -sS -D - -o /dev/null -X GET "$BASE/api/auth/login"
curl -sS -D - -o /dev/null "$BASE/api/tasks/abc" -H "Authorization: Bearer $TOKEN"
curl -sS -D - -o /dev/null "$BASE/api/tasks?pageNum=foo" -H "Authorization: Bearer $TOKEN"
curl -sS -D - -o /dev/null -X DELETE "$BASE/api/tasks/1" -H "Authorization: Bearer $TOKEN"
curl -sS -D - -o /dev/null -X PUT "$BASE/api/tasks/1/status" -H "Authorization: Bearer $TOKEN"
```

期望：JSON 无法解析、缺 Content-Type、路径类型不对、缺查询参数为 400；方法不匹配为 405。  
实际（修复前）：全部 500。  
已修复 (test: AuthControllerTest#shouldReturnBadRequest_whenInvalidJsonFormat, AuthControllerTest#shouldReturnUnsupportedMediaType_whenContentTypeMissing, AuthControllerTest#shouldReturnMethodNotAllowed_whenLoginUsesGet, AuthControllerTest#shouldReturnBadRequest_whenAuthorizationHeaderMissing, TaskControllerTest#shouldReturnBadRequest_whenTaskIdIsNotNumeric, TaskControllerTest#shouldReturnMethodNotAllowed_whenDeleteTask, TaskControllerTest#shouldReturnBadRequest_whenStatusParamMissing)

修复后复核：坏 JSON 为 400 / `请求体格式错误`，缺 Content-Type 为 415，GET 登录为 405，`/api/tasks/abc` 为 400。

### D02 重复任务编码

```bash
curl -sS -X POST "$BASE/api/tasks" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"taskCode":"dup-demo","serviceId":"svc","testType":"UNIT_TEST","priority":5}'
curl -sS -D - -o /dev/null -X POST "$BASE/api/tasks" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"taskCode":"dup-demo","serviceId":"svc","testType":"UNIT_TEST","priority":5}'
```

期望：400，提示编码已存在。实际（修复前）：500 / `系统错误`，日志为 `DuplicateKeyException`。  
已修复 (test: TaskServiceTest#shouldThrowWhenTaskCodeDuplicated, TaskControllerTest#shouldReturnBadRequest_whenTaskCodeDuplicated)

### D03 超长字段与非法 JSON 依赖

```bash
LONG=$(python3 -c 'print("L"*80)')
curl -sS -X POST "$BASE/api/tasks" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"taskCode\":\"${LONG}\",\"serviceId\":\"svc\",\"testType\":\"UNIT_TEST\",\"priority\":1}"
curl -sS -X POST "$BASE/api/tasks" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"taskCode":"dep-demo","serviceId":"svc","testType":"UNIT_TEST","priority":1,"dependencies":"not-json"}'
```

期望：400。实际（修复前）：500，MySQL `Data too long` 与 `Invalid JSON text`。  
已修复 (test: TaskControllerTest#shouldRejectOversizedOrIllegalCreateFields, TaskControllerTest#shouldReturnBadRequest_whenDataIntegrityViolated, TaskServiceTest#shouldRejectInvalidDependenciesJson)

### D04 黑名单日志打印完整令牌

登出后再拿同一访问令牌请求 `GET /api/tasks`。修复前 WARN 为：

`Token 已被加入黑名单: eyJhbGciOiJIUzUxMiJ9.<payload>.<signature>`

期望：只记录短前缀或哈希。  
已修复 (test: JwtAuthenticationFilterTest#doesNotLogFullBlacklistedToken)

修复后日志形如 `Token 已被加入黑名单: eyJhbGci…#57206602`，不含签名段。

### D05 开发日志打印密码哈希

`application-dev.yml` 使用 MyBatis `StdOutImpl`，并且 `com.sentinel` 为 DEBUG。登录会 `UPDATE t_user`，参数日志里出现完整 bcrypt。期望：日志不出现口令哈希。  
已修复 (test: DevProfileSafetyTest#devProfileDoesNotDumpSqlRows)

去掉 `StdOutImpl`，并把 `com.sentinel.mapper` 与 `com.baomidou.mybatisplus` 设为 INFO。修复后的启动日志里 `\$2a\$` 出现次数为 0。

### D06 负数分页

```bash
curl -sS "$BASE/api/tasks?pageNum=-1&pageSize=-5" -H "Authorization: Bearer $TOKEN"
```

期望：400。实际（修复前）：HTTP 200，`size` 被改成 500，`records` 有数据，`total` 为 0。  
已修复 (test: TaskControllerTest#shouldRejectNonPositivePagination)

### D07 未登录是空 403

```bash
curl -sS -D - "$BASE/api/tasks"
```

期望：401，以及与其它接口一致的 JSON。实际（修复前）：403，body 长度为 0。  
已修复 (test: HttpSecurityBehaviorTest#anonymousTaskRequestIsUnauthorizedJson)

修复后：401，`{"code":1001,"message":"未授权，请先登录","data":null}`。

### D08 不存在的路径是空 403

```bash
curl -sS -D - "$BASE/api/no-such-route" -H "Authorization: Bearer $TOKEN"
curl -sS -D - "$BASE/api/tasks/1/logs" -H "Authorization: Bearer $TOKEN"
```

期望：404 JSON。实际（修复前）：403，空 body。错误转发再次进入安全链，而 JWT 过滤器不会在 ERROR dispatch 上再认证。  
已修复 (test: HttpSecurityBehaviorTest#unknownPathIsNotFoundJson, ApiErrorControllerTest#unknownPathIsNotFound)

### D09 CORS 未生效

`security.cors` 写了允许的源和方法，但安全链没有启用 CORS。

```bash
curl -sS -D - -o /dev/null -X OPTIONS "$BASE/api/tasks" \
  -H 'Origin: http://localhost:3000' \
  -H 'Access-Control-Request-Method: GET'
```

期望：200，并带 `Access-Control-Allow-Origin`。实际（修复前）：403，没有该头。  
已修复 (test: HttpSecurityBehaviorTest#corsPreflightAllowsConfiguredOrigin)

开发 profile 下 `http://localhost:3000` 的预检已返回 200。测试 profile 的允许源是 `http://test-frontend:3000`。

### D10 Swagger 入口被拒绝

README 写的是 `/swagger-ui.html`。安全配置只放行了 `/swagger-ui/**`。

```bash
curl -sS -o /dev/null -w '%{http_code}\n' "$BASE/swagger-ui.html"
curl -sS -o /dev/null -w '%{http_code}\n' "$BASE/swagger-ui/index.html"
curl -sS -o /dev/null -w '%{http_code}\n' "$BASE/api-docs"
```

期望：文档入口可匿名打开。实际（修复前）：`/swagger-ui.html` 为 403，`/swagger-ui/index.html` 为 200，`/api-docs` 为 200，`/v3/api-docs` 为 403（自定义文档路径是 `/api-docs`）。  
已修复 (test: HttpSecurityBehaviorTest#swaggerUiEntryIsNotForbidden)

修复后 `/swagger-ui.html` 为 302，转向 Swagger UI。

### D11 匿名健康检查泄露详情

```bash
curl -sS "$BASE/actuator/health"
```

期望：匿名调用方只看到 `UP`。实际（修复前）：`show-details: always`，响应包含磁盘路径 `/data/home/mahrozhuang/EnvWeaver/.`、剩余空间、MySQL 与 Redis 版本。`/actuator/env`、`heapdump`、`beans` 未放行，为 403。  
已修复 (test: HttpSecurityBehaviorTest#healthHidesDetailsFromAnonymous, DevProfileSafetyTest#healthDetailsRequireAuthorization)

修复后匿名响应为 `{"status":"UP"}`。

### D12 任意任务状态

```bash
curl -sS -X PUT "$BASE/api/tasks/1/status?status=NOT_A_STATUS" -H "Authorization: Bearer $TOKEN"
```

期望：400，状态无效。实际（修复前）：200，`任务状态已更新`。普通用户调用同一接口为 403 / `权限不足`，这部分原本就是对的。  
已修复 (test: TaskServiceTest#shouldRejectUnknownStatus)

### D13 校验结果不返回给客户端

缺字段时日志有 `taskCode=任务编码不能为空`，响应 `data` 为 null。`testType=WEIRD`、`priority=-999999` 会成功入库。

期望：400，并指出字段。  
已修复 (test: AuthControllerTest#shouldReturnValidationError_whenUsernameIsEmpty, TaskControllerTest#shouldRejectOversizedOrIllegalCreateFields)

修复后非法类型和优先级的 `data` 为 `{"testType":"测试类型不合法","priority":"优先级必须在1到10之间"}`。

### D14 Redis 故障日志

`CacheService.isTokenBlacklisted` 在 Redis 连接失败时把包含完整令牌的 key 打到 WARN。  
已修复 (test: CacheServiceBlacklistTest#isTokenBlacklistedFailsClosedOnRedisConnectionFailure)

### U01 任务不存在仍是 400

```bash
curl -sS -D - "$BASE/api/tasks/999999999" -H "Authorization: Bearer $TOKEN"
```

期望（OpenAPI）：404。实际：400，`{"code":2001,"message":"任务不存在"}`。不是 500。  
已修复（第二轮，经用户确认改契约）(test: TaskControllerTest#shouldReturnNotFound_whenTaskNotExists)

`GlobalExceptionHandler` 按错误码映射 HTTP 状态：2001/3001/4002/5005 为 404。修复后同一条 curl 返回 404。

### U02 登录失败与锁定的 HTTP 状态

```bash
curl -sS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"wrong-pass"}'
curl -sS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"lockout-probe","password":"bad"}'
```

开发 profile 最多 10 次（`application-dev.yml`）。错误密码：HTTP 400，`code=1007`，`用户名或密码错误，剩余尝试次数: N`。不存在的用户是同一句文案，没有暴露用户是否存在。第 10 次以及之后：HTTP 400，`code=1005`，`账户已被锁定`。  
已修复（第二轮，经用户确认改契约）(test: AuthControllerTest#shouldReturnUnauthorized_whenInvalidCredentials, AuthControllerTest#shouldReturnAccountLocked_whenAccountIsLocked, HttpSecurityBehaviorTest#wrongPasswordIsUnauthorized)

错误码 1001–1007 映射为 401，1008（权限不足）为 403，与 OpenAPI 一致。

### U03 Prometheus 未认证可访问

```bash
curl -sS -o /dev/null -w '%{http_code} %{size_download}\n' "$BASE/actuator/prometheus"
```

实际：200，约 47KB。抽查未见口令或 JWT 明文，能看到 JVM 与请求指标。`/actuator/env`、`heapdump`、`loggers` 为 403。  
已修复（第二轮，经用户确认）(test: HttpSecurityBehaviorTest#prometheusRequiresAdmin)

除 `/actuator/health` 外的 actuator 端点只对 ADMIN 开放。修复后：匿名 401，普通用户 403，管理员 200。Prometheus 抓取需要配置管理员的 Bearer Token。

### U04 超大 pageSize

```bash
curl -sS "$BASE/api/tasks?pageNum=1&pageSize=1000000" -H "Authorization: Bearer $TOKEN"
```

实际：200，`size` 为 500（`PaginationInnerInterceptor.maxLimit`），`total` 正确。  
未修复: 这是现有上限，不是错误响应。负数页已经改为 400。若希望超过 500 也返回 400，需要单独定契约。

### U05 Thymeleaf 警告

启动日志：`Cannot find template location: classpath:/templates/`。API 不依赖页面模板。  
未修复: 只是启动噪音，不影响请求。

### U06 白名单配置是死的

`SecurityProperties.whitelistPaths` 含 `/api/auth/**`，但 `SecurityConfig` 只用自己的 `requestMatchers`。若改成使用这份清单，登出会变成匿名。  
已修复: `whitelistPaths` 字段已删除，不再存在未接入的白名单配置。

### U07 `/api/health` 恒为 UP

```bash
curl -sS "$BASE/api/health"
```

实际：200，`{"status":"UP","application":"Sentinel",...}`，不探测数据库或 Redis。依赖状态在 `/actuator/health`。  
未修复: 改变这个接口的 body 会改现有契约。

## 文档承诺但未实现的接口

`docs/api/api-specification.yaml` 中的路径现已全部有对应控制器，此前列在这里的诊断、当前用户、任务状态与日志、环境、监控接口都已补齐。

补接口时发现一个此前存在的缺陷：Docker 客户端用的 httpclient5 传输层与 Spring Boot 管理的 httpclient5 5.2 不兼容，运行时先报 `NoClassDefFoundError`，补齐类后又把 unix socket 当成 `localhost:2375`。所有真实 Docker 调用（读容器日志、停止和删除容器）因此一直失败，单元测试全是 mock 所以没有暴露。已改用 docker-java 自带的 zerodep 传输，并用真实容器验证了日志读取与环境释放。

已实现但与文档字段不一致、本次未改契约：创建任务实际是 HTTP 200 而不是 201；列表参数是 `pageNum` / `pageSize`，数据在 `data.records`，不是 `page` / `size` 和 `data.content`；取消任务是 `POST /api/tasks/{id}/cancel`，不是 `DELETE /api/tasks/{id}`。

## 测试结果

`mvn -B -o clean test`：

```text
[WARNING] Tests run: 169, Failures: 0, Errors: 0, Skipped: 8
BUILD SUCCESS
```

`WARNING` 来自原本就跳过的 8 个用例（`EndToEndIntegrationTest` 7 个，`TaskControllerTest#shouldReturnForbidden_whenUserIsNotAdmin` 1 个），没有失败。

冒烟：`BASE_URL=http://localhost:18080 bash scripts/smoke-test.sh` 退出码 0。

## 需要人工决定

- 现有数据卷里 `t_user.admin` 的 bcrypt 与 `schema.sql` / `data.sql` 种子不同，前缀是 `$2a$10$aH03DQfz0Xvlkm8KboBJqux`。`admin` / `admin123` 仍然能登录，`user` / `user123` 也能登录。没有改数据库。
- `SecurityProperties.whitelistPaths` 与 `SecurityConfig` 的放行表以哪一份为准。不要直接启用现在的 whitelist，否则 `/api/auth/**` 会把登出变成匿名。
