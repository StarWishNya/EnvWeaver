# AI diagnosis evaluation

Calls `DiagnosisService.diagnosisSystemPrompt`, `diagnosisUserPrompt`, and `parseModelContent`, then `AIRouter.complete` with JSON mode. No Docker and no diagnosis cache.

Scores are independent: errorType exact match, severity exact match, any rootCause/summary keyword (case-insensitive), parse success (`fallback=false`), latency.

The 14 logs are shorter than 8000 characters, so trimming does not change the bytes sent for this set. Over the cap, the prompt keeps a short preamble, the first exception (bounded stack frames plus every `Caused by` line), and the tail.

`.env` sets `AI_PROVIDER=ollama` and the DeepSeek key. The owner's process also sets `AI_PROVIDER_ORDER=deepseek,ollama`. When that variable is unset and the DeepSeek key is present, this runner uses `deepseek,ollama`. Ollama is only a fallback.

## Taxonomy notes

Assertion failure, business NPE, and the flaky race have no dedicated errorType. Expected value is UNKNOWN.
Port already in use is NETWORK_ERROR (bind failure). There is no PORT_CONFLICT value.
MySQL connection refused is NETWORK_ERROR. Authentication failure is CONFIG_ERROR (bad credentials), not a separate AUTH value.
Class not found is DEPENDENCY_FAILURE. A missing placeholder is CONFIG_ERROR.
Redis command timeout and JUnit test timeout are TIMEOUT. Downstream HTTP 500 is DEPENDENCY_FAILURE.
Severity used by the set: CRITICAL only for memory or disk exhaustion; HIGH for a broken startup or a failed live request; MEDIUM for a single test failure, test timeout, or intermittent race.

## baseline

- provider-order: `deepseek,ollama`
- recorded: run 1 @ 2026-10-10 12:27:12; run 2 @ 2026-10-10 12:27:37

| provider | protocol | model | timeout | temperature | maxTokens | thinking | available |
| --- | --- | --- | --- | --- | --- | --- | --- |
| deepseek | OPENAI | deepseek-flash | 60 | 0.3 | 2000 | disabled | true |
| ollama | OLLAMA | qwen2.5:7b | 120 | 0.3 | 2000 |  | true |

### Run 1

| case | expected type | actual type | type | expected sev | actual sev | sev | keyword | parse | ms | provider | model |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| oom-heap | RESOURCE_EXHAUSTED | RESOURCE_EXHAUSTED | Y | CRITICAL | CRITICAL | Y | Y | Y | 2075 | deepseek | deepseek-flash |
| port-in-use | NETWORK_ERROR | CONFIG_ERROR | N | HIGH | HIGH | Y | Y | Y | 1422 | deepseek | deepseek-flash |
| db-connection-refused | NETWORK_ERROR | NETWORK_ERROR | Y | HIGH | CRITICAL | N | Y | Y | 2007 | deepseek | deepseek-flash |
| db-auth-failure | CONFIG_ERROR | CONFIG_ERROR | Y | HIGH | CRITICAL | N | Y | Y | 1879 | deepseek | deepseek-flash |
| redis-timeout | TIMEOUT | TIMEOUT | Y | HIGH | HIGH | Y | Y | Y | 2503 | deepseek | deepseek-flash |
| assertion-failure | UNKNOWN | CONFIG_ERROR | N | MEDIUM | MEDIUM | Y | Y | Y | 1893 | deepseek | deepseek-flash |
| test-timeout | TIMEOUT | TIMEOUT | Y | MEDIUM | MEDIUM | Y | Y | Y | 1989 | deepseek | deepseek-flash |
| npe-business | UNKNOWN | CONFIG_ERROR | N | HIGH | MEDIUM | N | Y | Y | 1573 | deepseek | deepseek-flash |
| class-not-found | DEPENDENCY_FAILURE | DEPENDENCY_FAILURE | Y | HIGH | CRITICAL | N | Y | Y | 2182 | deepseek | deepseek-flash |
| disk-full | RESOURCE_EXHAUSTED | RESOURCE_EXHAUSTED | Y | CRITICAL | CRITICAL | Y | Y | Y | 2180 | deepseek | deepseek-flash |
| dns-failure | NETWORK_ERROR | NETWORK_ERROR | Y | HIGH | HIGH | Y | Y | Y | 1991 | deepseek | deepseek-flash |
| http-5xx-downstream | DEPENDENCY_FAILURE | DEPENDENCY_FAILURE | Y | HIGH | HIGH | Y | Y | Y | 2152 | deepseek | deepseek-flash |
| flaky-race | UNKNOWN | CONFIG_ERROR | N | MEDIUM | HIGH | N | Y | Y | 1795 | deepseek | deepseek-flash |
| missing-property | CONFIG_ERROR | CONFIG_ERROR | Y | HIGH | HIGH | Y | Y | Y | 1474 | deepseek | deepseek-flash |

- **oom-heap** summary: 月度报表任务在构建客户-月份汇总时因 Java 堆内存耗尽抛出 OutOfMemoryError，导致报表生成中止并返回错误页面。 rootCause: MonthlyReportJob 将 184 万行数据在内存中通过 StringBuilder 拼接渲染（renderCustomer 方法），堆占用达到 91% 后继续追加数据，最终超出 JVM 堆上限触发 java.lang.OutOfMemoryError: Java heap space。 keyword: OutOfMemory
- **port-in-use** summary: Spring Boot 应用启动失败，内嵌 Tomcat 无法绑定 8080 端口，因为该端口已被占用。 rootCause: 端口 8080 已被其他进程占用，导致 Tomcat 在初始化 ServerSocket 时抛出 java.net.BindException: Address already in use，Spring 上下文刷新失败并终止应用启动。 keyword: 端口
- **db-connection-refused** summary: 应用启动时 HikariCP 无法连接 MySQL 数据库，连接被拒绝，导致数据源初始化失败、Tomcat 上下文启动失败，应用启动终止。 rootCause: 目标 MySQL 服务地址 mysql:3306（解析为 10.0.0.8:3306）拒绝 TCP 连接（Connection refused），通常表示数据库容器/服务未启动、端口未监听、服务名或端口配置错误，或网络策略阻止了连接。 keyword: Connection refused
- **db-auth-failure** summary: 应用启动时因数据库认证失败而终止：MySQL 拒绝用户 billing 的访问，导致 Hikari 连接池初始化失败、Flyway 迁移无法获取连接，最终 Spring 上下文启动失败。 rootCause: MySQL 返回 'Access denied for user billing@172.16.8.23 (using password: YES)'，说明提供的数据库凭据（用户名或密码）不正确，或该用户未被授权从 172.16.8.23 主机连接。密码已配置但被服务端拒绝，属于认证/授权配置问题，而非网络或资源问题。 keyword: Access denied
- **redis-timeout** summary: 应用在通过 Lettuce 客户端访问 Redis（10.1.4.20:6379）执行 GET price:sku:88421 时，命令在 10 秒后超时，导致 SkuController 价格查询接口抛出 RedisSystemException，请求失败。 rootCause: Redis 命令执行超时（RedisCommandTimeoutException: Command timed out after 10 second(s)）。从堆栈看，超时发生在 RedisHandshakeHandler.channelInactive，说明底层 Netty 连接在命令执行期间被关闭/断开，随后 ConnectionWatchdog 触发重连。可能原因包括：Redis 服务端负载过高或阻塞（如慢查询、大 key、持久化阻塞）、网络抖动或连接被中间设备/防火墙中断、连接池或 Lettuce 连接资源不足、Redis 实例不可用或正在故障转移。 keyword: 超时
- **assertion-failure** summary: 单元测试 InvoiceCalculatorTest.taxRoundsHalfUp 断言失败：税额四舍五入期望 1150 最小货币单位，实际得到 1149，导致 Maven 构建失败。 rootCause: 税额计算使用了错误的舍入模式（如向下取整/截断，或默认 RoundingMode.HALF_EVEN 等），未按业务要求的四舍五入（HALF_UP）处理，导致 10000×0.15=1500 相关计算在边界值上少 1 个最小单位；也可能存在浮点/整数精度处理不当。 keyword: 断言
- **test-timeout** summary: LedgerImportTest.importsLargeBatch 测试在 5 秒超时限制内未完成，处理 250000 行数据时仅完成 42000 行，导致 CountDownLatch 等待超时。 rootCause: 测试用例对 250000 行的大批量导入设置了 5000ms 的 JUnit 超时，而实际处理速度约为每秒 8000-9000 行，无法在超时前完成全部批次，awaitBatch 中的 CountDownLatch.await 被中断抛出 TimeoutException。 keyword: 超时
- **npe-business** summary: 订单报价接口因查询不到订单记录返回 null，随后在 OrderPricingService 中触发 NullPointerException，导致请求返回 500。 rootCause: OrderRepository 根据 orderId=88421 查询订单时未返回任何行，但 OrderPricingService.quote 未对 null 结果做判空处理，直接调用 order.getCustomerId() 导致空指针异常。属于业务代码缺少对不存在订单的防御性处理。 keyword: NullPointer
- **class-not-found** summary: Spring 应用启动失败，创建 kafkaTemplate Bean 时抛出 NoClassDefFoundError/ClassNotFoundException，缺少 Kafka 客户端类 org.apache.kafka.clients.producer.KafkaProducer。 rootCause: 运行时类路径中缺少 kafka-clients 依赖（或该依赖被错误地声明为 provided/optional、被依赖排除、版本冲突或打包时未包含），导致 KafkaConfig 中创建 KafkaTemplate 时无法加载 KafkaProducer 类，Bean 创建失败并中止上下文刷新。 keyword: ClassNotFound
- **disk-full** summary: 应用所在文件系统磁盘空间耗尽，导致审计日志写入、会话持久化等所有写操作失败。 rootCause: 承载 /var/log/acme 与 /var/lib/tomcat/work 的文件系统已无可用空间，Java 写入时抛出 java.io.IOException: No space left on device。可能由审计日志文件持续增长、日志轮转/清理策略缺失或磁盘配额不足引起。 keyword: 磁盘
- **dns-failure** summary: 调用订单服务时 DNS 解析失败，导致请求返回 500 错误 rootCause: Java 抛出 UnknownHostException: orders.internal.svc.cluster.local: Temporary failure in name resolution，说明容器内 DNS 无法解析该 Kubernetes 服务域名，可能是 CoreDNS 异常、服务名或命名空间错误、Service 不存在或网络策略/DNS 配置问题 keyword: UnknownHost
- **http-5xx-downstream** summary: checkout 服务调用下游 payment-service 的 /v1/charges 接口时收到 HTTP 500，导致本次支付请求失败并以 502 返回给调用方。 rootCause: 下游 payment-service 内部处理扣款时失败，返回 500 且响应体 code=UPSTREAM，说明其自身依赖的上游（如银行/支付网关）捕获扣款失败，并非网络或超时问题；checkout 侧未对该错误做降级或重试处理，直接向上抛出异常。 keyword: 500
- **flaky-race** summary: InventoryBoardTest.parallelRefreshIsStable 测试失败，两个刷新线程并发修改共享的 ArrayList 行列表，导致 ConcurrentModificationException。 rootCause: InventoryBoard.merge 方法在并发刷新时对共享的 ArrayList 进行迭代和排序，但未进行同步或使用线程安全的数据结构，导致一个线程在另一个线程迭代期间替换了快照，触发 fail-fast 检查。 keyword: ConcurrentModification
- **missing-property** summary: Spring Boot 应用启动失败，无法解析配置占位符 'sentinel.jwt.secret'，导致 JwtProperties Bean 创建失败。 rootCause: 应用配置中缺少必需的属性 sentinel.jwt.secret，可能是配置文件未提供该属性、环境变量未设置或配置源未正确加载。 keyword: jwt.secret

### Run 2

| case | expected type | actual type | type | expected sev | actual sev | sev | keyword | parse | ms | provider | model |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| oom-heap | RESOURCE_EXHAUSTED | RESOURCE_EXHAUSTED | Y | CRITICAL | CRITICAL | Y | Y | Y | 1858 | deepseek | deepseek-flash |
| port-in-use | NETWORK_ERROR | CONFIG_ERROR | N | HIGH | HIGH | Y | Y | Y | 1352 | deepseek | deepseek-flash |
| db-connection-refused | NETWORK_ERROR | NETWORK_ERROR | Y | HIGH | CRITICAL | N | Y | Y | 1722 | deepseek | deepseek-flash |
| db-auth-failure | CONFIG_ERROR | CONFIG_ERROR | Y | HIGH | CRITICAL | N | Y | Y | 1916 | deepseek | deepseek-flash |
| redis-timeout | TIMEOUT | TIMEOUT | Y | HIGH | HIGH | Y | Y | Y | 1929 | deepseek | deepseek-flash |
| assertion-failure | UNKNOWN | CONFIG_ERROR | N | MEDIUM | MEDIUM | Y | Y | Y | 1733 | deepseek | deepseek-flash |
| test-timeout | TIMEOUT | TIMEOUT | Y | MEDIUM | MEDIUM | Y | Y | Y | 2104 | deepseek | deepseek-flash |
| npe-business | UNKNOWN | DEPENDENCY_FAILURE | N | HIGH | MEDIUM | N | Y | Y | 1902 | deepseek | deepseek-flash |
| class-not-found | DEPENDENCY_FAILURE | DEPENDENCY_FAILURE | Y | HIGH | CRITICAL | N | Y | Y | 1682 | deepseek | deepseek-flash |
| disk-full | RESOURCE_EXHAUSTED | RESOURCE_EXHAUSTED | Y | CRITICAL | CRITICAL | Y | Y | Y | 1849 | deepseek | deepseek-flash |
| dns-failure | NETWORK_ERROR | NETWORK_ERROR | Y | HIGH | HIGH | Y | Y | Y | 1787 | deepseek | deepseek-flash |
| http-5xx-downstream | DEPENDENCY_FAILURE | UNKNOWN | N | HIGH | MEDIUM | N | Y | N | 2248 | deepseek | deepseek-flash |
| flaky-race | UNKNOWN | CONFIG_ERROR | N | MEDIUM | MEDIUM | Y | Y | Y | 1978 | deepseek | deepseek-flash |
| missing-property | CONFIG_ERROR | CONFIG_ERROR | Y | HIGH | CRITICAL | N | Y | Y | 1227 | deepseek | deepseek-flash |

- **oom-heap** summary: 月度报表任务在构建客户月度汇总时因 Java 堆内存耗尽抛出 OutOfMemoryError，导致请求 /api/reports/monthly 失败。 rootCause: MonthlyReportJob 在单次请求中处理约 184 万行数据，并通过 StringBuilder 在内存中拼接渲染所有客户报表内容，堆占用达到 91% 后继续追加数据，最终触发 Java heap space 溢出。 keyword: OutOfMemory
- **port-in-use** summary: Spring Boot 应用启动失败，内嵌 Tomcat 无法绑定 8080 端口，因为该端口已被占用。 rootCause: 端口 8080 已被其他进程占用，导致内嵌 Tomcat 在启动时抛出 java.net.BindException: Address already in use，进而引发 PortInUseException 和应用上下文启动失败。 keyword: 端口
- **db-connection-refused** summary: 应用启动时无法连接 MySQL 数据库，HikariCP 连接池初始化失败，导致 Spring 应用启动失败。 rootCause: 目标地址 mysql:3306（解析为 10.0.0.8:3306）拒绝连接（Connection refused），说明数据库服务未运行、端口未监听或网络/服务发现配置错误，而非认证或 SQL 问题。 keyword: Connection refused
- **db-auth-failure** summary: 应用启动时因数据库认证失败而终止，HikariCP 连接池初始化与 Flyway 迁移均无法获取连接。 rootCause: MySQL 拒绝用户 'billing'@'172.16.8.23' 的访问（Access denied，using password: YES），说明 spring.datasource 中配置的数据库用户名或密码与 MySQL 实际账号不匹配，或该账号未授权从当前 Pod IP 连接。 keyword: Access denied
- **redis-timeout** summary: 访问 Redis 缓存读取价格数据时命令超时，导致 SKU 价格查询接口失败。 rootCause: Lettuce 客户端向 Redis (10.1.4.20:6379) 发送 GET price:sku:88421 命令后，在 10 秒内未收到响应，触发 RedisCommandTimeoutException；从堆栈中的 RedisHandshakeHandler.channelInactive 和后续 ConnectionWatchdog 重连日志看，连接在握手/读取阶段被中断，可能是 Redis 服务端响应缓慢、连接被网络中断或 Redis 实例负载过高/不可用。 keyword: 超时
- **assertion-failure** summary: Maven 构建因 InvoiceCalculatorTest.taxRoundsHalfUp 单元测试断言失败而中断，期望税额 1150 最小货币单位，实际得到 1149。 rootCause: 税额计算中的舍入模式与测试预期不一致。测试期望对 10000×0.15=1500 的税额采用 HALF_UP（四舍五入）得到 1150，但实际实现使用了 HALF_EVEN（银行家舍入）或截断/向下取整，导致结果为 1149。 keyword: 断言
- **test-timeout** summary: LedgerImportTest.importsLargeBatch 测试在 5000ms 超时后失败，导入 250000 行数据时仅处理了 42000 行，CountDownLatch 等待未完成。 rootCause: 测试方法设置了 5000ms 的 JUnit 超时限制，但导入 250000 行的大批量数据耗时超过该限制。日志显示在超时时刻仅处理了 42000/250000 行，说明导入逻辑处理速度不足以在 5 秒内完成，或批处理并发/异步机制未能及时触发 CountDownLatch 的 countDown。 keyword: 超时
- **npe-business** summary: 订单报价接口对不存在的订单号 88421 返回 500，因查询结果为空导致空指针异常。 rootCause: OrderRepository 根据 id=88421 查询订单返回无记录（no row），OrderPricingService.quote 第 86 行未对 order 为 null 做校验，直接调用 order.getCustomerId() 触发 NullPointerException，异常未被业务层捕获处理，最终由 DispatcherServlet 抛出并返回 500。 keyword: NullPointer
- **class-not-found** summary: Spring 应用启动失败，创建 kafkaTemplate Bean 时抛出 NoClassDefFoundError，原因是运行时类路径缺少 Kafka 客户端依赖。 rootCause: 类路径中缺少 org.apache.kafka.clients.producer.KafkaProducer，即 kafka-clients 依赖未被打包或未正确引入，导致 KafkaConfig 中创建 kafkaTemplate Bean 时类加载失败。 keyword: NoClassDefFound
- **disk-full** summary: 审计日志写入失败，磁盘空间耗尽（No space left on device），导致审计记录无法持久化，并连带影响 Tomcat 会话持久化。 rootCause: 承载 /var/log/acme 与 /var/lib/tomcat/work 的文件系统磁盘空间已满，Java NIO 写入审计日志文件时抛出 IOException: No space left on device，属于存储资源耗尽。 keyword: 磁盘
- **dns-failure** summary: 调用订单服务时 DNS 解析失败，导致请求返回 500 错误。 rootCause: 应用无法解析主机名 orders.internal.svc.cluster.local，报 UnknownHostException: Temporary failure in name resolution。通常由 Kubernetes 集群 DNS（CoreDNS）不可用、Service 名称或命名空间错误、Pod 的 DNS 配置（/etc/resolv.conf、ndots、search 域）异常，或网络策略阻断 DNS 流量导致。 keyword: UnknownHost
- **http-5xx-downstream** summary: {"errorType":"DEPENDENCY_FAILURE","severity":"HIGH","summary":"checkout 服务调用下游 payment-service 的 /v1/charges 接口时，对方返回 HTTP 500（body code=UPSTREAM，message=payment-service failed to capture），导致本次支付捕获失败， rootCause: {"errorType":"DEPENDENCY_FAILURE","severity":"HIGH","summary":"checkout 服务调用下游 payment-service 的 /v1/charges 接口时，对方返回 HTTP 500（body code=UPSTREAM，message=payment-service failed to capture），导致本次支付捕获失败，checkout 最终向客户端返回 502 BAD_GATEWAY。","rootCause":"下游 payment-service 内部处理失败并返回 500，其响应体 code=UPSTREAM 表明该服务自身依赖的上游（如银行/支付网关或内部账务服务）捕获失败，错误被透传为 500。checkout 侧网络连接正常（164ms 内收到完整响应，连接保持打开），因此并非网络或超时问题，而是 keyword: 500
- **flaky-race** summary: InventoryBoardTest.parallelRefreshIsStable 测试失败，抛出 ConcurrentModificationException，原因是两个刷新线程并发修改共享的 row 列表且未同步。 rootCause: InventoryBoard.merge 方法在并发调用时对共享的 ArrayList 进行迭代和排序，没有加锁或使用线程安全集合，导致一个线程在遍历时另一个线程替换快照并修改列表，触发 fail-fast 的 ConcurrentModificationException。 keyword: ConcurrentModification
- **missing-property** summary: Spring Boot 应用启动失败，无法解析占位符 'sentinel.jwt.secret'，导致 JwtProperties Bean 创建失败。 rootCause: 配置文件中缺少必需的属性 'sentinel.jwt.secret'，或者该属性未通过环境变量、命令行参数等方式提供，导致 Spring 无法解析占位符。 keyword: jwt.secret

### baseline totals

| metric | hits | rate | mean latency ms |
| --- | --- | --- | --- |
| errorType | 19/28 | 67.9% | 1871 |
| severity | 17/28 | 60.7% | 1871 |
| keyword | 28/28 | 100.0% | 1871 |
| parse | 27/28 | 96.4% | 1871 |

Variance: cases whose runs disagree on errorType: npe-business, http-5xx-downstream. On severity: http-5xx-downstream, flaky-race, missing-property.

<details><summary>System prompt (baseline)</summary>

````
你是容器与 Spring 应用故障诊断专家。
只输出一个 JSON 对象，不要使用 Markdown，不要输出解释。
文本字段使用中文。字段必须是：
errorType（RESOURCE_EXHAUSTED、NETWORK_ERROR、DEPENDENCY_FAILURE、TIMEOUT、CONFIG_ERROR、UNKNOWN 之一），
severity（LOW、MEDIUM、HIGH、CRITICAL 之一），
summary（问题摘要），
rootCause（根因），
suggestions（修复建议字符串数组），
confidence（0 到 1 的小数）。
````

</details>

## tuned

- provider-order: `deepseek,ollama`
- recorded: run 1 @ 2026-10-10 12:33:06; run 2 @ 2026-10-10 12:33:27

| provider | protocol | model | timeout | temperature | maxTokens | thinking | available |
| --- | --- | --- | --- | --- | --- | --- | --- |
| deepseek | OPENAI | deepseek-flash | 60 | 0.3 | 2000 | disabled | true |
| ollama | OLLAMA | qwen2.5:7b | 120 | 0.3 | 2000 |  | true |

### Run 1

| case | expected type | actual type | type | expected sev | actual sev | sev | keyword | parse | ms | provider | model |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| oom-heap | RESOURCE_EXHAUSTED | RESOURCE_EXHAUSTED | Y | CRITICAL | CRITICAL | Y | Y | Y | 1911 | deepseek | deepseek-flash |
| port-in-use | NETWORK_ERROR | NETWORK_ERROR | Y | HIGH | HIGH | Y | Y | Y | 1242 | deepseek | deepseek-flash |
| db-connection-refused | NETWORK_ERROR | NETWORK_ERROR | Y | HIGH | HIGH | Y | Y | Y | 1343 | deepseek | deepseek-flash |
| db-auth-failure | CONFIG_ERROR | CONFIG_ERROR | Y | HIGH | HIGH | Y | Y | Y | 1409 | deepseek | deepseek-flash |
| redis-timeout | TIMEOUT | TIMEOUT | Y | HIGH | HIGH | Y | Y | Y | 1388 | deepseek | deepseek-flash |
| assertion-failure | UNKNOWN | UNKNOWN | Y | MEDIUM | MEDIUM | Y | Y | Y | 1446 | deepseek | deepseek-flash |
| test-timeout | TIMEOUT | TIMEOUT | Y | MEDIUM | MEDIUM | Y | Y | Y | 1677 | deepseek | deepseek-flash |
| npe-business | UNKNOWN | UNKNOWN | Y | HIGH | HIGH | Y | Y | Y | 1245 | deepseek | deepseek-flash |
| class-not-found | DEPENDENCY_FAILURE | DEPENDENCY_FAILURE | Y | HIGH | HIGH | Y | Y | Y | 1305 | deepseek | deepseek-flash |
| disk-full | RESOURCE_EXHAUSTED | RESOURCE_EXHAUSTED | Y | CRITICAL | CRITICAL | Y | Y | Y | 1528 | deepseek | deepseek-flash |
| dns-failure | NETWORK_ERROR | NETWORK_ERROR | Y | HIGH | HIGH | Y | Y | Y | 1517 | deepseek | deepseek-flash |
| http-5xx-downstream | DEPENDENCY_FAILURE | DEPENDENCY_FAILURE | Y | HIGH | HIGH | Y | Y | Y | 1586 | deepseek | deepseek-flash |
| flaky-race | UNKNOWN | UNKNOWN | Y | MEDIUM | MEDIUM | Y | Y | Y | 1436 | deepseek | deepseek-flash |
| missing-property | CONFIG_ERROR | CONFIG_ERROR | Y | HIGH | HIGH | Y | Y | Y | 1532 | deepseek | deepseek-flash |

- **oom-heap** summary: 月度报表任务在拼接客户数据时耗尽 JVM 堆内存，导致请求失败。 rootCause: 最底层异常为 java.lang.OutOfMemoryError: Java heap space，发生在 MonthlyReportJob.renderCustomer 使用 StringBuilder 追加内容时，日志显示堆占用已达 91%，属于内存耗尽。 keyword: OutOfMemory
- **port-in-use** summary: Spring Boot 应用启动失败，内嵌 Tomcat 无法绑定 8080 端口。 rootCause: 最底层异常为 java.net.BindException: Address already in use，导致 PortInUseException: Port 8080 is already in use，即端口 8080 已被其他进程占用。 keyword: 端口
- **db-connection-refused** summary: 应用启动时 Hikari 连接池初始化失败，无法连接 MySQL 数据库，导致 Spring 上下文启动失败。 rootCause: 最底层异常为 java.net.ConnectException: Connection refused，目标地址 mysql/10.0.0.8:3306 拒绝连接，说明数据库服务未启动或网络不可达，而非超时或认证问题。 keyword: Connection refused
- **db-auth-failure** summary: 应用启动时数据库连接池初始化失败，导致 Spring 上下文启动失败。 rootCause: 最底层异常为 com.mysql.cj.exceptions.CJException: Access denied for user 'billing'@'172.16.8.23' (using password: YES)，即数据库用户名或口令被拒绝。 keyword: Access denied
- **redis-timeout** summary: 商品价格查询接口访问 Redis 缓存时命令超时，导致本次请求失败。 rootCause: 最底层异常为 io.lettuce.core.RedisCommandTimeoutException: Command timed out after 10 second(s)，即对 Redis 10.1.4.20:6379 的 GET 命令超过 10 秒未返回，属于超时类故障。 keyword: 超时
- **assertion-failure** summary: 单元测试 InvoiceCalculatorTest.taxRoundsHalfUp 断言失败，期望税额 1150 但实际为 1149。 rootCause: 业务代码的税额取整逻辑有缺陷，未按四舍五入（half-up）处理，导致 AssertionFailedError: expected: <1150> but was: <1149>。 keyword: 断言
- **test-timeout** summary: LedgerImportTest.importsLargeBatch 测试在 5 秒超时限制内未完成，处理 250000 行批次时卡在 CountDownLatch 等待上。 rootCause: 最底层异常为 java.util.concurrent.TimeoutException: Execution timed out after 5000 ms，由 JUnit 超时扩展触发，测试线程在 LedgerImporter.awaitBatch 的 CountDownLatch.await 处被中断，说明大批量导入耗时超过 5 秒的测试时限。 keyword: 超时
- **npe-business** summary: 订单报价接口在处理 orderId=88421 时因订单不存在抛出空指针异常，返回 500。 rootCause: 数据库查询未命中（query returned no row for id=88421），返回的 order 为 null，业务代码未做空值校验，直接调用 order.getCustomerId() 触发 java.lang.NullPointerException。 keyword: NullPointer
- **class-not-found** summary: ShippingApplication 启动失败，创建 kafkaTemplate bean 时抛出 NoClassDefFoundError，导致 Spring 上下文刷新中止。 rootCause: 最底层异常为 java.lang.ClassNotFoundException: org.apache.kafka.clients.producer.KafkaProducer，即 Kafka 客户端库不在运行时 classpath 上，导致 kafkaTemplate bean 无法创建。 keyword: ClassNotFound
- **disk-full** summary: 审计服务在写入审计日志文件时因磁盘空间耗尽失败，导致请求处理中断，同时 Tomcat 会话持久化也写入失败。 rootCause: 底层文件系统磁盘空间耗尽，日志中反复出现 java.io.IOException: No space left on device，写入 /var/log/acme/audit-2026-10-09.log 和 /var/lib/tomcat/work/sessions 均失败。 keyword: 磁盘
- **dns-failure** summary: 调用订单服务时域名解析失败，导致请求返回 500。 rootCause: 最底层异常为 java.net.UnknownHostException: orders.internal.svc.cluster.local: Temporary failure in name resolution，说明 DNS 无法解析该主机名，属于名字解析失败。 keyword: UnknownHost
- **http-5xx-downstream** summary: 调用 payment-service 的 /v1/charges 接口返回 HTTP 500，导致本次结账请求失败并对外返回 502。 rootCause: 下游 payment-service 已连通但处理失败，返回 500 Internal Server Error，异常类为 org.springframework.web.client.HttpServerErrorException$InternalServerError，响应体 code=UPSTREAM、message=payment-service failed to capture。 keyword: 500
- **flaky-race** summary: InventoryBoardTest.parallelRefreshIsStable 在并发刷新时抛出 ConcurrentModificationException，导致测试失败。 rootCause: InventoryBoard.merge 在多线程下未同步地修改共享的 ArrayList，遍历过程中被另一线程替换快照，触发 java.util.ConcurrentModificationException（ArrayList$Itr.checkForComodification）。 keyword: ConcurrentModification
- **missing-property** summary: GatewayApplication 启动失败，因配置占位符 sentinel.jwt.secret 无法解析。 rootCause: 最底层异常为 java.lang.IllegalArgumentException: Could not resolve placeholder 'sentinel.jwt.secret' in value "${sentinel.jwt.secret}"，即缺少必需的配置项 sentinel.jwt.secret，导致 jwtProperties Bean 注入失败。 keyword: placeholder

### Run 2

| case | expected type | actual type | type | expected sev | actual sev | sev | keyword | parse | ms | provider | model |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| oom-heap | RESOURCE_EXHAUSTED | RESOURCE_EXHAUSTED | Y | CRITICAL | CRITICAL | Y | Y | Y | 1560 | deepseek | deepseek-flash |
| port-in-use | NETWORK_ERROR | NETWORK_ERROR | Y | HIGH | HIGH | Y | Y | Y | 1411 | deepseek | deepseek-flash |
| db-connection-refused | NETWORK_ERROR | NETWORK_ERROR | Y | HIGH | HIGH | Y | Y | Y | 1467 | deepseek | deepseek-flash |
| db-auth-failure | CONFIG_ERROR | CONFIG_ERROR | Y | HIGH | HIGH | Y | Y | Y | 1568 | deepseek | deepseek-flash |
| redis-timeout | TIMEOUT | TIMEOUT | Y | HIGH | HIGH | Y | Y | Y | 1630 | deepseek | deepseek-flash |
| assertion-failure | UNKNOWN | UNKNOWN | Y | MEDIUM | MEDIUM | Y | Y | Y | 1350 | deepseek | deepseek-flash |
| test-timeout | TIMEOUT | TIMEOUT | Y | MEDIUM | MEDIUM | Y | Y | Y | 1461 | deepseek | deepseek-flash |
| npe-business | UNKNOWN | UNKNOWN | Y | HIGH | HIGH | Y | Y | Y | 1441 | deepseek | deepseek-flash |
| class-not-found | DEPENDENCY_FAILURE | DEPENDENCY_FAILURE | Y | HIGH | HIGH | Y | Y | Y | 1475 | deepseek | deepseek-flash |
| disk-full | RESOURCE_EXHAUSTED | RESOURCE_EXHAUSTED | Y | CRITICAL | CRITICAL | Y | Y | Y | 1306 | deepseek | deepseek-flash |
| dns-failure | NETWORK_ERROR | NETWORK_ERROR | Y | HIGH | HIGH | Y | Y | Y | 1459 | deepseek | deepseek-flash |
| http-5xx-downstream | DEPENDENCY_FAILURE | DEPENDENCY_FAILURE | Y | HIGH | HIGH | Y | Y | Y | 1582 | deepseek | deepseek-flash |
| flaky-race | UNKNOWN | UNKNOWN | Y | MEDIUM | MEDIUM | Y | Y | Y | 1539 | deepseek | deepseek-flash |
| missing-property | CONFIG_ERROR | CONFIG_ERROR | Y | HIGH | HIGH | Y | Y | Y | 1448 | deepseek | deepseek-flash |

- **oom-heap** summary: 月度报表任务在拼接客户数据时耗尽 JVM 堆内存，导致请求失败。 rootCause: 最底层异常为 java.lang.OutOfMemoryError: Java heap space，发生在 MonthlyReportJob.renderCustomer 中 StringBuilder 扩容时，日志显示堆占用已达 91%。 keyword: OutOfMemory
- **port-in-use** summary: Spring Boot 应用启动失败，内嵌 Tomcat 无法绑定 8080 端口，因为该端口已被占用。 rootCause: 最底层异常为 java.net.BindException: Address already in use，由 PortInUseException 抛出，说明 8080 端口已被其他进程监听，导致 Web 服务器无法启动。 keyword: 端口
- **db-connection-refused** summary: 应用启动时 HikariCP 初始化数据库连接池失败，无法连接 MySQL 服务，导致 Spring 上下文启动失败。 rootCause: 最底层异常为 java.net.ConnectException: Connection refused，目标地址 mysql/10.0.0.8:3306 拒绝连接，说明 MySQL 服务未启动或端口未监听，而非超时或认证问题。 keyword: Connection refused
- **db-auth-failure** summary: 应用启动时数据库连接池初始化失败，Flyway 无法获取数据库连接，导致 Spring 上下文启动失败。 rootCause: 最底层异常为 com.mysql.cj.exceptions.CJException: Access denied for user 'billing'@'172.16.8.23' (using password: YES)，即数据库用户名或口令被拒绝，属于认证配置错误。 keyword: Access denied
- **redis-timeout** summary: SKU 价格查询请求访问 Redis 缓存时命令超时，导致该次线上请求失败。 rootCause: 最底层异常为 io.lettuce.core.RedisCommandTimeoutException: Command timed out after 10 second(s)，即 Redis GET 命令超过 10 秒未返回，属于超过时限的失败。 keyword: 超时
- **assertion-failure** summary: Maven 运行 InvoiceCalculatorTest 时 8 个测试中 1 个失败，taxRoundsHalfUp 断言期望 1150 实际得到 1149。 rootCause: 业务代码的税额四舍五入逻辑存在缺陷，导致 AssertionFailedError: expected: <1150> but was: <1149>，属于测试断言失败而非环境或依赖问题。 keyword: 断言
- **test-timeout** summary: LedgerImportTest.importsLargeBatch 测试在 5 秒内未完成，抛出 TimeoutException，导致 Maven 构建失败。 rootCause: 最底层异常为 java.util.concurrent.TimeoutException: Execution timed out after 5000 ms，测试线程在 CountDownLatch.await 处等待批处理完成时被 JUnit 超时机制中断。 keyword: 超时
- **npe-business** summary: 订单报价接口处理请求时抛出空指针异常，返回 500。 rootCause: OrderRepository 查询 id=88421 返回空行，导致 order 为 null，OrderPricingService.quote 第 86 行调用 order.getCustomerId() 时抛出 java.lang.NullPointerException。 keyword: NullPointer
- **class-not-found** summary: ShippingApplication 启动失败，创建 kafkaTemplate bean 时抛出 NoClassDefFoundError。 rootCause: 最底层异常为 java.lang.ClassNotFoundException: org.apache.kafka.clients.producer.KafkaProducer，即 Kafka 客户端类不在运行时 classpath 上，导致 kafkaTemplate bean 创建失败、应用启动中止。 keyword: ClassNotFound
- **disk-full** summary: 审计服务在写入审计日志文件时磁盘空间耗尽，导致请求处理失败。 rootCause: 底层文件系统空间已满，日志中反复出现 java.io.IOException: No space left on device，导致 AuditFileWriter.append 写入失败，并连带影响 Tomcat 会话持久化。 keyword: 磁盘
- **dns-failure** summary: 订单服务请求失败，域名解析异常导致接口返回 500。 rootCause: 最底层异常为 java.net.UnknownHostException: orders.internal.svc.cluster.local: Temporary failure in name resolution，DNS 无法解析下游服务域名，导致 RestTemplate 抛出 ResourceAccessException。 keyword: UnknownHost
- **http-5xx-downstream** summary: 调用下游 payment-service 的 /v1/charges 接口返回 HTTP 500，导致本次结账请求失败并对外返回 502。 rootCause: 下游 payment-service 已连通但处理失败，返回 500 Internal Server Error，触发 HttpServerErrorException$InternalServerError，响应体 code=UPSTREAM、message=payment-service failed to capture。 keyword: 500
- **flaky-race** summary: InventoryBoardTest.parallelRefreshIsStable 在并行刷新时抛出 ConcurrentModificationException，测试失败。 rootCause: 两个刷新线程在无同步的情况下并发修改共享的 ArrayList，导致迭代过程中检测到并发修改，抛出 java.util.ConcurrentModificationException（ArrayList$Itr.checkForComodification）。 keyword: ConcurrentModification
- **missing-property** summary: GatewayApplication 启动失败，因缺少必填配置项 sentinel.jwt.secret，占位符无法解析导致 jwtProperties Bean 创建失败。 rootCause: 最底层异常为 java.lang.IllegalArgumentException: Could not resolve placeholder 'sentinel.jwt.secret' in value "${sentinel.jwt.secret}"，即配置中未提供 sentinel.jwt.secret 属性，Spring 无法解析该占位符，导致 jwtProperties Bean 注入失败、应用启动中止。 keyword: placeholder

### tuned totals

| metric | hits | rate | mean latency ms |
| --- | --- | --- | --- |
| errorType | 28/28 | 100.0% | 1473 |
| severity | 28/28 | 100.0% | 1473 |
| keyword | 28/28 | 100.0% | 1473 |
| parse | 28/28 | 100.0% | 1473 |

Variance: cases whose runs disagree on errorType: none. On severity: none.

<details><summary>System prompt (tuned)</summary>

````
你是容器与 Spring 应用故障诊断专家。
只输出一个 JSON 对象，不要使用 Markdown，不要代码围栏，不要输出解释或思考过程。
文本字段使用中文。rootCause 用一两句说明最底层原因，并引用日志里的异常类名或关键错误短语。
suggestions 是 1 到 4 条中文修复建议。confidence 是 0 到 1 的小数。
字段必须且只能是 errorType、severity、summary、rootCause、suggestions、confidence。

errorType 必须是下列英文大写之一。先看最底层的 Caused by，再按顺序归类，命中即停止：
1. TIMEOUT：失败的直接原因是超过时限。例：TimeoutException、timed out。连接被拒绝不要归入此类。
2. RESOURCE_EXHAUSTED：进程或机器耗尽内存、磁盘、文件句柄或线程。例：OutOfMemoryError、Java heap space、No space left on device。
3. CONFIG_ERROR：只有配置缺失、占位符未解析，或用户名口令被拒绝。例：Could not resolve placeholder、Access denied for user。端口占用、空指针、断言失败、并发修改都不是 CONFIG_ERROR。
4. NETWORK_ERROR：名字解析失败、连接被拒绝，或端口绑定失败。例：UnknownHostException、Connection refused、Address already in use。
5. DEPENDENCY_FAILURE：依赖的类或库不在 classpath，或下游已经连通但返回失败。例：ClassNotFoundException、NoClassDefFoundError、HTTP 500/502/503。
6. UNKNOWN：业务代码缺陷、测试断言或竞态，且不符合以上类别。例：NullPointerException、AssertionFailedError、ConcurrentModificationException。

severity 必须是 LOW、MEDIUM、HIGH、CRITICAL 之一：
- CRITICAL：只有内存或磁盘耗尽。应用启动失败本身不是 CRITICAL。
- HIGH：服务无法完成启动，或一次线上请求失败，例如连不上、认证失败、端口被占用、缺配置、缺类、下游 5xx、请求线程里的空指针。
- MEDIUM：自动化测试里的断言失败、测试超时，或测试中的偶发竞态。
- LOW：已经自动恢复，或只是不影响本次结果的告警。
````

</details>

## Before / after

| case | baseline type | baseline sev | baseline kw | baseline parse | tuned type | tuned sev | tuned kw | tuned parse |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| oom-heap | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| port-in-use | 0/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| db-connection-refused | 2/2 | 0/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| db-auth-failure | 2/2 | 0/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| redis-timeout | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| assertion-failure | 0/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| test-timeout | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| npe-business | 0/2 | 0/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| class-not-found | 2/2 | 0/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| disk-full | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| dns-failure | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| http-5xx-downstream | 1/2 | 1/2 | 2/2 | 1/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| flaky-race | 0/2 | 1/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| missing-property | 2/2 | 1/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |

| total | 19/28 | 17/28 | 28/28 | 27/28 | 28/28 | 28/28 | 28/28 | 28/28 |


## Held-out evaluation

Twelve new logs in `src/test/resources/ai-eval/holdout`, not used to tune the prompt. Same prompt, parser, and provider order as the tuned runs (`AI_PROVIDER_ORDER=deepseek,ollama` from the environment; every call was answered by `deepseek` / `deepseek-flash`; Ollama was configured and unused). Raw rows: `reports/ai-eval-holdout/holdout-1.json` (2026-10-10 16:12:32) and `holdout-2.json` (2026-10-10 16:12:53). No API key is stored in those files.

`errorType` scoring now accepts a case's `acceptable` list as well as the primary label. In the tables, **Y** is the primary label, **A** is another listed label, **N** is neither. The runner's type total counts **A** as a hit. A strict column below counts only **Y**, which is how the original 14 cases were scored (none of them have an `acceptable` list).

Labels were assigned from the published taxonomy, not from the tuned prompt's example sentences. Where rules 1–5 do not name the failure, the primary label is `UNKNOWN` and the nearby operational label is listed as acceptable, with the reason in `labelNote`.

### Run 1

| case | expected type | acceptable | actual type | type | expected sev | actual sev | sev | keyword | parse | ms |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| kafka-rebalance-timeout | TIMEOUT |  | TIMEOUT | Y | HIGH | HIGH | Y | Y | Y | 2335 |
| ssl-pkix-path | UNKNOWN | UNKNOWN, CONFIG_ERROR, NETWORK_ERROR | CONFIG_ERROR | A | HIGH | HIGH | Y | Y | Y | 1712 |
| liquibase-checksum | UNKNOWN | UNKNOWN, CONFIG_ERROR | UNKNOWN | Y | HIGH | HIGH | Y | Y | Y | 1929 |
| too-many-open-files | RESOURCE_EXHAUSTED |  | RESOURCE_EXHAUSTED | Y | HIGH | CRITICAL | N | Y | Y | 1887 |
| thread-pool-rejected | RESOURCE_EXHAUSTED |  | RESOURCE_EXHAUSTED | Y | HIGH | HIGH | Y | Y | Y | 1326 |
| grpc-unavailable | NETWORK_ERROR | NETWORK_ERROR, DEPENDENCY_FAILURE | NETWORK_ERROR | Y | HIGH | HIGH | Y | Y | Y | 1550 |
| mysql-deadlock | UNKNOWN | UNKNOWN, DEPENDENCY_FAILURE | UNKNOWN | Y | HIGH | HIGH | Y | Y | Y | 1779 |
| json-downstream-body | DEPENDENCY_FAILURE | DEPENDENCY_FAILURE, UNKNOWN | UNKNOWN | A | HIGH | HIGH | Y | Y | Y | 1839 |
| readiness-probe | TIMEOUT | TIMEOUT, DEPENDENCY_FAILURE | TIMEOUT | Y | HIGH | HIGH | Y | Y | Y | 2072 |
| selenium-element-missing | UNKNOWN |  | UNKNOWN | Y | MEDIUM | MEDIUM | Y | Y | Y | 1522 |
| jar-method-conflict | DEPENDENCY_FAILURE | DEPENDENCY_FAILURE, UNKNOWN | DEPENDENCY_FAILURE | Y | HIGH | HIGH | Y | Y | Y | 1295 |
| file-permission-denied | UNKNOWN | UNKNOWN, CONFIG_ERROR | CONFIG_ERROR | A | HIGH | HIGH | Y | Y | Y | 1447 |

- **too-many-open-files** is the only severity miss: `Too many open files` at fd 1021/1024 was called CRITICAL. The taxonomy reserves CRITICAL for memory or disk exhaustion; a live request that fails on the open-file limit is HIGH.
- **ssl-pkix-path** primary is UNKNOWN. The model called it CONFIG_ERROR because the JVM trust store has no path to the peer certificate. TCP had already connected.
- **json-downstream-body** primary is DEPENDENCY_FAILURE. The model called it UNKNOWN: HTTP 200, then `UnrecognizedPropertyException` on `display_name`. It treated that as a client DTO defect, which the acceptable list allows.
- **file-permission-denied** primary is UNKNOWN. The model called it CONFIG_ERROR for `AccessDeniedException: Permission denied` on `/var/lib/acme/exports`. Not disk-full and not a rejected database password.

### Run 2

| case | expected type | acceptable | actual type | type | expected sev | actual sev | sev | keyword | parse | ms |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| kafka-rebalance-timeout | TIMEOUT |  | TIMEOUT | Y | HIGH | HIGH | Y | Y | Y | 2042 |
| ssl-pkix-path | UNKNOWN | UNKNOWN, CONFIG_ERROR, NETWORK_ERROR | CONFIG_ERROR | A | HIGH | HIGH | Y | Y | Y | 1509 |
| liquibase-checksum | UNKNOWN | UNKNOWN, CONFIG_ERROR | UNKNOWN | Y | HIGH | HIGH | Y | Y | Y | 1939 |
| too-many-open-files | RESOURCE_EXHAUSTED |  | RESOURCE_EXHAUSTED | Y | HIGH | HIGH | Y | Y | Y | 1820 |
| thread-pool-rejected | RESOURCE_EXHAUSTED |  | RESOURCE_EXHAUSTED | Y | HIGH | HIGH | Y | Y | Y | 1628 |
| grpc-unavailable | NETWORK_ERROR | NETWORK_ERROR, DEPENDENCY_FAILURE | NETWORK_ERROR | Y | HIGH | HIGH | Y | Y | Y | 1598 |
| mysql-deadlock | UNKNOWN | UNKNOWN, DEPENDENCY_FAILURE | UNKNOWN | Y | HIGH | HIGH | Y | Y | Y | 1841 |
| json-downstream-body | DEPENDENCY_FAILURE | DEPENDENCY_FAILURE, UNKNOWN | UNKNOWN | A | HIGH | HIGH | Y | Y | Y | 1716 |
| readiness-probe | TIMEOUT | TIMEOUT, DEPENDENCY_FAILURE | DEPENDENCY_FAILURE | A | HIGH | HIGH | Y | Y | Y | 1549 |
| selenium-element-missing | UNKNOWN |  | UNKNOWN | Y | MEDIUM | MEDIUM | Y | Y | Y | 1741 |
| jar-method-conflict | DEPENDENCY_FAILURE | DEPENDENCY_FAILURE, UNKNOWN | DEPENDENCY_FAILURE | Y | HIGH | HIGH | Y | Y | Y | 2014 |
| file-permission-denied | UNKNOWN | UNKNOWN, CONFIG_ERROR | CONFIG_ERROR | A | HIGH | HIGH | Y | Y | Y | 1346 |

- **readiness-probe** is the only type flip. Run 1 followed the probe deadline (`context deadline exceeded`) to TIMEOUT. Run 2 followed `/ready` HTTP 503 `payment:DOWN` to DEPENDENCY_FAILURE. Both are on the acceptable list. Severity stayed HIGH.
- **ssl-pkix-path**, **json-downstream-body**, and **file-permission-denied** repeated run 1's non-primary labels. Those three are stable disagreements with the primary label, not noise.

### Hold-out totals

| metric | run 1 | run 2 | both | rate |
| --- | --- | --- | --- | --- |
| errorType, primary or acceptable | 12/12 | 12/12 | 24/24 | 100.0% |
| errorType, primary only | 9/12 | 8/12 | 17/24 | 70.8% |
| severity | 11/12 | 12/12 | 23/24 | 95.8% |
| keyword | 12/12 | 12/12 | 24/24 | 100.0% |
| parse | 12/12 | 12/12 | 24/24 | 100.0% |

Mean latency 1724 ms (run 1) and 1728 ms (run 2). Variance: errorType disagrees on `readiness-probe`; severity disagrees on `too-many-open-files`.

### Comparison with the tuned set

| set | runs | errorType | severity | keyword | parse | mean ms |
| --- | --- | --- | --- | --- | --- | --- |
| tuned, original scoring (exact type) | 2 x 14, recorded earlier | 28/28 (100%) | 28/28 | 28/28 | 28/28 | 1473 |
| tuned recheck, same 14 logs, this session | 1 x 14, `reports/ai-eval-runs/tuned-recheck-1.json`, 2026-10-10 16:13:25 | 14/14 (100%) | 14/14 | 14/14 | 14/14 | 1589 |
| hold-out, counting acceptable | 2 x 12 | 24/24 (100%) | 23/24 (95.8%) | 24/24 | 24/24 | 1726 |
| hold-out, primary label only | 2 x 12 | 17/24 (70.8%) | 23/24 (95.8%) | 24/24 | 24/24 | 1726 |

The recheck shows the tuned 14 are still solved under the same provider an hour later, so the 28/28 result was not a one-off. It does not transfer as an exact-label score. On the four hold-out cases with a single legal type (Kafka rebalance timeout, open-file limit, rejected thread pool, Selenium missing element) the model was 8/8 on type and 7/8 on severity. The drop to 17/24 is concentrated on labels the six-value taxonomy does not name cleanly, not on a failure to read the stack.

Keyword rate is not evidence of generalization. The prompt already requires quoting the exception class or the error phrase, and the keywords are those phrases.

### What the taxonomy and prompt handle poorly

- **CONFIG_ERROR is narrower than the model's use of it.** The prompt allows it only for a missing setting, an unresolved placeholder, or a rejected username and password, and it lists port-in-use, NPE, assertion, and concurrent modification as not CONFIG_ERROR. Those negatives held on the recheck. They do not stop the model from calling a PKIX trust-path failure and a filesystem `Permission denied` CONFIG_ERROR, twice each. Both are operational misconfiguration; neither is in the closed list, so the primary label is UNKNOWN. The model will not pick UNKNOWN when a nearby "this environment is wrong" label exists.
- **DEPENDENCY_FAILURE does not cover a connected HTTP 200 with a body the client cannot bind.** Both runs called the Jackson `UnrecognizedPropertyException` UNKNOWN and said the local `UserRecord` was missing `display_name`. The prompt's dependency examples are a missing class and HTTP 500/502/503. A 200 plus a schema mismatch does not match them. `NoSuchMethodError` from `commons-codec` 1.11 versus a 1.15 method did match, both runs, by analogy with `ClassNotFoundException` / `NoClassDefFoundError`. Linkage errors generalize; response-contract errors do not.
- **A failure that contains both a deadline and a 503 is not stable.** The readiness log has `context deadline exceeded` and six HTTP 503 answers for `payment:DOWN`. Rule order says TIMEOUT wins if the direct cause is the deadline. Run 1 did that. Run 2 called the payment component a dependency failure. The prompt's "stop at the first matching rule" instruction is not enough when both phrases are in the same log.
- **File-handle exhaustion is pulled toward CRITICAL.** The type rule names 文件句柄 next to memory and disk. The severity rule says CRITICAL only for memory or disk, and that a failed live request is HIGH. Run 1 promoted `Too many open files` to CRITICAL; run 2 stayed HIGH. The rejected thread pool, which the type rule also lists, stayed HIGH both times. The severity boundary is learnable but not stable for EMFILE.
- **Categories the prompt already names did generalize.** Kafka `TimeoutException` on rebalance stayed TIMEOUT, not a consumer-group dependency failure. gRPC `UNAVAILABLE` whose deepest cause is `Connection refused` stayed NETWORK_ERROR, not DEPENDENCY_FAILURE. MySQL `Deadlock found when trying to get lock` stayed UNKNOWN both times; the model explicitly rejected timeout, resource, config, network, and dependency. Liquibase checksum mismatch stayed UNKNOWN rather than CONFIG_ERROR. Selenium `NoSuchElementException` in an E2E test stayed UNKNOWN / MEDIUM, by analogy with assertion failures, even though that exception is not in the example list.

### Recommendations

Not implemented. Do not paste these twelve logs into the prompt and score them again; that repeats the original 14-case mistake. If the prompt changes, keep this directory frozen and add a third set.

1. Decide the CONFIG_ERROR boundary in one sentence that names trust-store / PKIX failures and filesystem `Permission denied`, either as in or as out. The current negative list only blocks the training misses. Leaving them as UNKNOWN predicts a label the model will not emit.
2. Say whether HTTP 200 plus a JSON deserialization error is DEPENDENCY_FAILURE or UNKNOWN. The 5xx examples do not decide it. One sentence is enough; a new errorType is not required unless product code needs to branch on it.
3. For readiness and similar mixed logs, state the winner: probe deadline means TIMEOUT even when the body is 503, or a 503 from a named component means DEPENDENCY_FAILURE even when the probe times out. Until that sentence exists, score both and do not treat the flip as a regression.
4. Split the resource sentences. Type: file descriptors and a rejected pool are RESOURCE_EXHAUSTED. Severity: CRITICAL stays memory and disk only; EMFILE and `RejectedExecutionException` on a live request are HIGH. The type rule currently lists 文件句柄 in the same breath as heap and disk, which is why one run marked EMFILE CRITICAL.
5. Keep reporting primary and acceptable separately. 24/24 with alternates counted is a taxonomy-fit number. 17/24 is the exact-label number comparable to the tuned 28/28.
