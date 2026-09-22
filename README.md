# 多租户限流与配额网关（tenant-quota-gateway-java）

基于 Spring Boot 3 / Java 21 的多租户请求限流与配额保护组件。不同租户在并发、重试与
配置变更下都能得到**一致且可解释**的判定结果：每次判定都携带原因、剩余额度与文字依据，
每次拒绝与策略变更都留下审计记录。

## 1. 租户识别方式

限流过滤器仅作用于 `/api/**`（见 `WebConfig`），通过 HTTP 请求头识别请求身份：

| 请求头 | 含义 | 缺失时行为 |
| --- | --- | --- |
| `X-Tenant-Id`（可配置） | 租户标识 | 判定为 `MISSING_TENANT`，HTTP 400 |
| `Idempotency-Key`（可配置） | 业务幂等键，标识"同一笔业务请求" | 不做幂等去重，按普通请求判定 |
| `X-Request-Id`（可配置） | 请求标识，用于审计串联与结果解释 | 网关自动生成 UUID |

租户策略按 `X-Tenant-Id` 在当前策略快照中精确匹配；未配置的租户判定为
`UNKNOWN_TENANT`，HTTP 403。

## 2. 限流与配额语义

每个租户拥有互相独立的计量状态（`TenantState`）与独立的锁，判定由两道闸门组成，
在**同一把租户锁内原子完成**，任一闸门不通过则两者都不扣减：

1. **速率限制（令牌桶）**
   - `rateCapacity`：桶容量，即允许的瞬时突发请求数；
   - `rateRefillPerSecond`：每秒补充令牌数（持续速率）；
   - 桶中令牌不足 1 个时判定 `RATE_LIMITED`，HTTP 429，响应给出 `Retry-After`。
2. **周期配额（固定窗口）**
   - `quotaLimit`：每个窗口的总用量上限；`quotaWindowSeconds`：窗口长度（秒）；
   - 窗口滚动时已用量清零；窗口内额度用尽判定 `QUOTA_EXHAUSTED`，HTTP 429，
     响应给出距窗口重置的 `Retry-After`。

两种 429 通过 `X-Decision-Reason` 响应头与 JSON body 中的 `reason`/`basis` 明确区分。

### 幂等（重复提交/重试不重复扣减）

- 携带相同 `Idempotency-Key` 的请求视为同一笔业务：首个成功判定会被缓存
  （默认保留 24 小时），随后的重复提交/重试直接返回 `IDEMPOTENT_REPLAY`（HTTP 200），
  **绝不二次扣减**速率与配额，响应中注明对应的首次 `requestId`。
- 同一键的**并发**请求通过 in-flight 占位串行化：只有首个请求真正判定，其余请求等待并
  共享其结论（成功则幂等重放；被拒绝则共享同一拒绝原因），从机制上杜绝并发二次扣减。
- 被拒绝的结论不缓存：业务在额度恢复后的重试会被当作新的正常判定；缓存键超过 TTL 后
  同样按新业务请求计费。

### 配置刷新语义

- 策略以**不可变快照**（`PolicySnapshot`，带单调递增 `version`）整体替换引用。
  进行中的判定永远使用旧快照或新快照中的一个，不会读到半刷新状态。
- 刷新不清理计量状态：配额上限/窗口长度未变时保留当前窗口已用量（用量不丢失）；
  仅当配额维度发生变化时才开启新窗口；令牌桶按新容量重置。
- 非法配置（文件缺失、JSON 损坏、参数非法、版本号回退）会被拒绝，**不会替换**
  最后已知良好的快照。
- 每次成功刷新写入一条 `POLICY_CHANGE` 审计记录（含旧版本→新版本与租户数）。

## 3. 降级策略

当策略不可用（无快照且加载失败）或租户计量发生未预期异常时，按配置二选一，结果确定、
原因可区分，并写入 `DEGRADATION` 审计：

- `tenant-quota.fail-open=false`（**默认 fail-closed**）：明确拒绝，原因
  `DEGRADED_DENY`，HTTP 503，body 的 `basis` 注明降级原因；
- `tenant-quota.fail-open=true`：按既定语义放行，原因仍为 `ALLOWED` 但 `basis`
  明确标注"fail-open 放行"。注意：请求自身错误（如缺租户头）不受 fail-open 影响，
  依旧 400 拒绝。

已有良好快照时，即使底层策略文件暂时不可读，网关继续使用最后已知良好配置提供服务。

## 4. 拒绝原因总览

| reason | 含义 | HTTP 状态 |
| --- | --- | --- |
| `ALLOWED` | 允许，已原子扣减 | 200 |
| `IDEMPOTENT_REPLAY` | 允许，命中幂等缓存，未重复扣减 | 200 |
| `MISSING_TENANT` | 缺少租户标识 | 400 |
| `UNKNOWN_TENANT` | 租户无生效策略 | 403 |
| `RATE_LIMITED` | 超出速率（突发）限制 | 429 |
| `QUOTA_EXHAUSTED` | 周期配额用尽 | 429 |
| `DEGRADED_DENY` | fail-closed 降级拒绝 | 503 |

拒绝响应统一包含：`X-Request-Id`、`X-Tenant-Id`、`X-Decision-Reason`、
`X-Policy-Version`、`X-RateLimit-Remaining`、`X-Quota-Remaining`、
`Retry-After`（如适用），以及含 `basis`（判定依据）的 JSON body。
审计日志 logger 名称为 `AUDIT`（拒绝/降级为 WARN，幂等重放/策略变更为 INFO）。

## 5. 可配置项

前缀 `tenant-quota`（见 `application.properties`）：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `tenant-header` | `X-Tenant-Id` | 租户标识请求头 |
| `idempotency-header` | `Idempotency-Key` | 幂等键请求头（留空关闭） |
| `request-id-header` | `X-Request-Id` | 请求标识请求头 |
| `policy-location` | `classpath:policies.json` | 策略文件（支持 `classpath:`/`file:`） |
| `fail-open` | `false` | 降级策略：false=拒绝，true=放行 |
| `idempotency-ttl-millis` | `86400000` | 幂等成功记录保留时长 |
| `audit-buffer-size` | `1000` | 内存审计记录保留条数 |
| `admin-endpoints-enabled` | `true` | 是否开放 `/admin` 接口 |

策略文件格式（`src/main/resources/policies.json`）：

```json
{
  "version": 1,
  "tenants": {
    "tenant-a": {
      "rateCapacity": 10,
      "rateRefillPerSecond": 1.0,
      "quotaLimit": 5,
      "quotaWindowSeconds": 60
    }
  }
}
```

约束：`rateCapacity >= 1`、`rateRefillPerSecond >= 0`、`quotaLimit >= 1`、
`quotaWindowSeconds >= 1`。

### 管理端接口（不参与限流）

- `POST /admin/policies/reload`：手动重新加载策略文件；
- `GET  /admin/tenants/{tenantId}`：查看租户当前令牌与配额用量；
- `GET  /admin/audit`：查看最近的审计记录（拒绝、幂等重放、策略变更、降级）。

## 6. 本地验证方法

前置：JDK 21+、Maven 3.9+。

```bash
# 运行全部测试（17 个，含并发/刷新/重试/降级场景，输出含 requestId 与判定依据）
mvn test

# 只跑某一类场景
mvn test -Dtest=TenantIsolationTest      # 租户隔离、临界额度、拒绝原因区分
mvn test -Dtest=ConcurrentBurstTest      # 200 并发突发严格不超发
mvn test -Dtest=IdempotencyTest          # 重复提交/并发同键/TTL
mvn test -Dtest=PolicyRefreshTest        # 配置刷新不错乱、不丢用量
mvn test -Dtest=DegradationTest          # fail-open/fail-closed 降级
mvn test -Dtest=JsonPolicyProviderTest   # 策略加载/校验/原子替换
mvn test -Dtest=WebEndToEndTest          # HTTP 端到端状态码与响应头

# 启动应用后手工验证
mvn spring-boot:run
curl -i localhost:8080/api/echo -H 'X-Tenant-Id: tenant-a' -H 'X-Request-Id: demo-1'
# 连续调用可观察 200 -> 429(QUOTA_EXHAUSTED)；带相同 Idempotency-Key 的调用只扣一次
curl -s localhost:8080/admin/audit | jq .
curl -s -X POST localhost:8080/admin/policies/reload
```

## 7. 关键设计说明（一致性从何而来）

- **不超发/不少计**：速率与配额的"检查 + 扣减"在每个租户对象的同一把监视器锁内完成；
  配额用量与允许数严格相等有并发测试佐证（200 线程/允许严格=容量）。
- **租户互不影响**：租户状态按租户分桶、各自加锁；一个租户的突发只会耗尽自己的桶。
- **重试一致性**：幂等成功记录 + 同键 in-flight 占位（基于 `CountDownLatch` 的
  happens-before 传递首请求结论），等待者从不重新进入计量逻辑。
- **刷新一致性**：判定只读不可变快照；快照通过原子引用替换；计量状态独立于策略存放，
  刷新本身不触碰已扣用量。
