# 多租户请求限流与配额保护网关（Java / Spring Boot）

在 Spring Boot 骨架上实现的一套**多租户请求限流与配额保护**组件。不同租户的请求在
并发、重试与配置变更下都能得到**一致、可解释**的判定结果：允许/拒绝结果确定，拒绝原因
可区分，每次拒绝与策略变更都留有含判定依据的审计记录。

## 1. 租户识别方式

- 请求头 `X-Tenant-Id`：租户标识；缺失或为空白时，请求以 `TENANT_NOT_IDENTIFIED` 明确拒绝（HTTP 403）。
- 请求头 `X-Request-Id`：业务请求唯一标识，作为**幂等键**。同一业务请求的重复提交/重试应携带相同值。
- 识别逻辑见 `TenantResolver`，判定入口见 `QuotaGatewayService.decide(...)`。

## 2. 限流与配额语义

判定严格按以下顺序执行，每一步都有确定、可区分的结论：

1. **租户识别**：无 `X-Tenant-Id` → `TENANT_NOT_IDENTIFIED`。
2. **降级检查**：策略配置不可用时按配置的降级模式处理（见第 4 节）。
3. **租户策略检查**：租户无策略 → `TENANT_NOT_CONFIGURED`（默认拒绝，可配置放行）。
4. **幂等占坑**：相同 `X-Request-Id` 的并发或重试请求，只有第一个请求真正执行判定，
   其余请求**重放首次结论**（允许或拒绝），不重复扣减、也不给出相反结论。
5. **速率限制**：每租户独立令牌桶（`rateLimitPerSecond` 速率 + `burstCapacity` 突发容量，
   惰性补充），令牌不足 → `RATE_LIMIT_EXCEEDED`（HTTP 429），不扣配额。
6. **配额计量**：每租户独立配额窗口，`used >= quotaLimit` → `QUOTA_EXCEEDED`（HTTP 403）；
   否则原子地占用 1 个单位。**只有真正放行的首个请求会计量**，拒绝、重试、降级放行均不扣减。

### 一致性保证

- **并发不超发/不少计**：令牌桶判定与扣减在同一把锁内完成；配额采用“先占坑、后提交”的
  两段式原子流程（`QuotaLedger`），先到请求提交最终结论后，并发重复请求才会读到结论，
  因此同一 requestId 在并发下也只会放行、计量一次。
- **租户隔离**：每个租户拥有独立令牌桶与独立计量锁。任一租户的突发流量只会耗尽自己的
  令牌与配额，不会阻塞或影响其他租户已被允许的请求。
- **配置刷新一致**：策略保存在不可变快照中（`PolicyRegistry` 的 `AtomicReference`），
  刷新是原子替换且版本号单调递增；在途请求要么全部基于旧快照、要么全部基于新快照，
  不会看到半新半旧的策略。刷新时令牌桶平滑更新（保留不超过新容量的已积累令牌），
  **配额账本不重置**，历史用量继续有效，不会出现判定错乱或用量丢失。

## 3. 拒绝原因（可区分）

| reason | 含义 | HTTP 状态码 |
| --- | --- | --- |
| `NONE` | 正常放行 | 200 |
| `TENANT_NOT_IDENTIFIED` | 缺少租户标识 | 403 |
| `TENANT_NOT_CONFIGURED` | 租户无生效策略 | 403 |
| `RATE_LIMIT_EXCEEDED` | 超过速率/突发限制 | 429 |
| `QUOTA_EXCEEDED` | 配额窗口已满 | 403 |
| `POLICY_UNAVAILABLE_REJECTED` | 配置不可用，FAIL_CLOSED 拒绝 | 403 |
| `POLICY_UNAVAILABLE_ALLOWED` | 配置不可用，FAIL_OPEN 放行（非拒绝，标记降级） | 200 |

响应体统一包含 `requestId / tenantId / allowed / reason / policyVersion / basis`，
其中 `basis` 为本次判定依据（剩余令牌、已用配额、生效策略版本等）。

## 4. 降级策略

当内部状态异常或策略配置不可用时（`PolicyRegistry.setAvailable(false)` 模拟配置源故障，
或计量过程抛出内部错误），结果仍由配置唯一决定：

- `gateway.degradation-mode=FAIL_CLOSED`（默认）：以 `POLICY_UNAVAILABLE_REJECTED` **明确拒绝**；
- `gateway.degradation-mode=FAIL_OPEN`：以 `POLICY_UNAVAILABLE_ALLOWED` **放行**，
  但响应与审计中保留降级标记，且放行不扣减任何配额。

两种模式结果确定、原因可区分，绝不返回含糊结论。

## 5. 可配置项（`src/main/resources/application.properties`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `gateway.degradation-mode` | `FAIL_CLOSED` | 配置/内部状态不可用时：`FAIL_CLOSED` 拒绝 / `FAIL_OPEN` 放行 |
| `gateway.unknown-tenant-mode` | `REJECT` | 未配置策略的租户：`REJECT` 拒绝 / `ALLOW` 放行 |

单租户策略字段（可通过管理接口刷新）：

| 字段 | 说明 |
| --- | --- |
| `tenantId` | 租户标识 |
| `rateLimitPerSecond` | 令牌补充速率（每秒请求数） |
| `burstCapacity` | 令牌桶容量（最大突发） |
| `quotaLimit` | 配额窗口内最大计费请求数 |
| `quotaWindowMillis` | 配额窗口长度（毫秒），窗口滚动后用量与幂等记录一同清空 |

应用启动时默认加载（`DefaultTenant`，便于开箱验证）：
`tenant-a`（2/s，突发 3，配额 5/60s）、`tenant-b`（1/s，突发 1，配额 2/60s）。

## 6. 审计

`AuditLog` 为**每次拒绝**与**每次策略变更/可用性切换**生成一条 `AuditEntry`，内容包含：
时间、`requestId`、租户、是否允许、原因、策略版本、判定依据（剩余令牌、`used/limit`、
变更前后状态等），同时输出到 logger `gateway.audit` 并保留在内存中供测试断言。

## 7. 接口

- `POST /api/invoke`：受保护接口，请求头 `X-Tenant-Id`、`X-Request-Id`。
- `GET /admin/policies`：查看当前策略快照、版本与配置可用性。
- `POST /admin/policies/refresh`：以请求体（`TenantPolicy` 数组）**原子刷新**全部策略。
- `POST /admin/config/available?available=false|true`：模拟配置源故障/恢复，验证降级。

## 8. 本地验证方法

### 8.1 运行测试（已覆盖全部要求场景）

```bash
mvn test
```

共 14 个用例，覆盖：租户隔离、临界额度、50 线程并发突发（恰好放行容量个、无超发）、
重复提交与并发重复提交（结论一致、只计一次）、配置刷新（版本递增、用量不丢失、
新策略生效）、FAIL_CLOSED/FAIL_OPEN 降级、租户识别、审计完整性。
测试输出中会逐条打印 `requestId` 与判定依据（`basis`）。

### 8.2 启动后用 curl 验证

```bash
mvn spring-boot:run

# 1) 正常请求
curl -i -X POST http://localhost:8080/api/invoke \
  -H 'X-Tenant-Id: tenant-a' -H 'X-Request-Id: req-1'

# 2) 重复提交：连续执行同一命令，结论一致且只计一次
curl -i -X POST http://localhost:8080/api/invoke \
  -H 'X-Tenant-Id: tenant-a' -H 'X-Request-Id: req-retry'

# 3) 突发触发 RATE_LIMIT_EXCEEDED（429，原因可区分）
for i in 1 2 3 4 5; do
  curl -s -o - -w ' [%{http_code}]\n' -X POST http://localhost:8080/api/invoke \
    -H 'X-Tenant-Id: tenant-a' -H "X-Request-Id: burst-$i"
done

# 4) 租户隔离：tenant-a 打满不影响 tenant-b
curl -i -X POST http://localhost:8080/api/invoke \
  -H 'X-Tenant-Id: tenant-b' -H 'X-Request-Id: b-1'

# 5) 配置刷新（原子生效，版本号递增）
curl -i -X POST http://localhost:8080/admin/policies/refresh \
  -H 'Content-Type: application/json' \
  -d '[{"tenantId":"tenant-a","rateLimitPerSecond":10,"burstCapacity":10,"quotaLimit":100,"quotaWindowMillis":60000}]'

# 6) 模拟配置不可用：默认 FAIL_CLOSED 返回 403 + POLICY_UNAVAILABLE_REJECTED
curl -i -X POST 'http://localhost:8080/admin/config/available?available=false'
curl -i -X POST http://localhost:8080/api/invoke \
  -H 'X-Tenant-Id: tenant-a' -H 'X-Request-Id: deg-1'
curl -i -X POST 'http://localhost:8080/admin/config/available?available=true'
```

审计日志会直接打印在服务端控制台（logger 名 `gateway.audit`）。

## 9. 关键代码位置

| 职责 | 类 |
| --- | --- |
| 判定编排（顺序、降级、幂等、审计） | `quota/QuotaGatewayService` |
| 幂等 + 配额原子计量 | `quota/QuotaLedger` |
| 单租户令牌桶 | `quota/TokenBucket` |
| 不可变策略快照 / 原子刷新 / 版本 | `quota/PolicyRegistry` |
| 审计 | `quota/AuditLog`、`quota/AuditEntry` |
| 租户识别 | `quota/TenantResolver` |
| 配置项 | `config/GatewayProperties` |
| HTTP / 管理接口 | `web/GatewayController`、`web/AdminController` |
