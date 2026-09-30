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

### 预占—结算（两阶段计量，面向耗时异步业务）

对于大文件转码、批量导出、报表生成这类**开始时不知道最终用量、结果稍后才出**的异步业务，
一次性判定不合适，网关在同一套租户状态上再提供一层「先占用、后结算」模型。它与上面的
一次性计量**共用同一个配额窗口和同一把租户锁**，二者可以混用，互不算漏。

- **预占 `POST /api/quotas/reservations`**：body `{"estimated": N}`，以 `Idempotency-Key`
  作为预占键。网关在租户锁内原子地：消耗 1 个速率令牌 + 从当前配额窗口占用 `N`。
  被占住的 `N` 在结算或超时释放前**计入窗口已用量**，因此同租户并发突发时，其它请求
  （无论一次性还是新预占）能分到的额度天然是「上限 − 已结算消耗 − 全部在途占用」，
  放行总量严格不超过上限，也不会把别人占住的量算漏。预估量放不下时返回 429
  `QUOTA_EXHAUSTED`，速率令牌不足返回 429 `RATE_LIMITED`，两种情况下都**不占用任何额度**。
- **结算 `POST /api/quotas/settlements`**：body `{"actual": M}`，凭预占键定位本笔业务，
  在租户锁内一次性完成**多退少补**：`M < 预占` 退回差额，`M > 预占` 补扣差额。
  补扣允许窗口已用量瞬时超过上限（真实用量必须如实入账），此后新的预占/普通请求会被挡在
  门外直到窗口滚动。结算本身不消耗速率令牌。
- **恰好一次**：预占键就是业务幂等键。重复预占同一在途键返回 200 `RESERVATION_REPLAY`
  （不二次占用）；同一笔业务的结算无论**重复提交、乱序到达还是中途超时重试**，都以**最先
  到达**的一次为准真正生效，之后全部返回 `RESERVATION_REPLAY`（携带首次结算 requestId 与
  首次真实用量），绝不重复计费或多扣。每个结果的原因（成功/重放/超时/找不到）都可区分。
- **结果可解释**：响应含 `X-Decision-Reason`、`X-Reservation-Status`
  （`ACTIVE`/`SETTLED`/`EXPIRED`）、占用量、已结算量、剩余配额与 `basis` 判定依据。

预占/结算端点不经一次性计量过滤器（见 `QuotaLimitFilter#shouldNotFilter`），因此不会被
重复计量；但租户识别、降级、审计、策略快照等机制与普通请求完全一致。

#### 占用超时自动释放

- 占住却迟迟等不到结算（业务失败、调用方挂掉）时，占用不会一直挂着：每笔预占带一个确定的
  绝对过期时刻 `reservedAt + reservation-ttl-millis`（默认 5 分钟）。判定边界确定：
  **`now >= expiresAt` 即失效**。
- 过期是**惰性扫描**：该租户下一次任意计量操作（预占/结算/普通判定）进入租户锁时，先把
  到期占用标记为 `EXPIRED` 并把占用量退回当前窗口，再做本次判定；每笔释放写一条
  `RESERVATION_EXPIRED` 审计。
- 失效后旧占用不再是有效额度：迟到的结算返回 409 `RESERVATION_EXPIRED`，不会对已释放的
  占用再次计费，需要时业务方应重新预占。**已经正常结算过的业务不受超时扫描影响**——超时只
  作用于仍处于 `ACTIVE` 的记录。

#### 窗口滚动与策略刷新期间的在途占用

- 配额窗口自然滚动时，旧窗口的已结算消耗清零，**仍在途（未超时）的占用会被改绑并迁移到新
  窗口**，占用量不凭空消失；随后到达的结算落在**当前窗口与当前上限**上完成多退少补。
  （因此业务耗时若可能跨越整个窗口，建议让业务时长 + 结算延迟不超过 TTL/窗口，或拆分为
  多笔预占。）
- 策略刷新（不可变快照整体替换）期间，上限/窗口未变时保留当前窗口已用量与全部在途占用，
  正在进行的判定不会错乱、用量不会消失；仅当配额维度（上限或窗口长度）变化时才开新窗口，
  此时在途占用同样迁移到新窗口，之后的结算按新上限计算剩余。
- 终态（已结算/已过期）记录在内存中保留 `idempotency-ttl-millis`（默认 24h）用于重复请求
  去重，之后惰性清理；清理后再用该键结算返回 404 `RESERVATION_NOT_FOUND`。

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
| `ALLOWED` | 允许，已原子扣减（一次性计量） | 200 |
| `IDEMPOTENT_REPLAY` | 允许，命中幂等缓存，未重复扣减 | 200 |
| `RESERVED` | 预占成功，已占用预估配额 | 200 |
| `SETTLED` | 结算成功，已多退少补 | 200 |
| `RESERVATION_REPLAY` | 允许，预占/结算的重复或乱序提交，未重复占用/计费 | 200 |
| `INVALID_ARGUMENT` | 预占/结算参数非法（缺键、用量非法） | 400 |
| `MISSING_TENANT` | 缺少租户标识 | 400 |
| `UNKNOWN_TENANT` | 租户无生效策略 | 403 |
| `RESERVATION_NOT_FOUND` | 结算引用了不存在的预占键 | 404 |
| `RESERVATION_EXPIRED` | 预占已超时释放，迟到结算被拒 | 409 |
| `RESERVATION_CLOSED` | 预占键已处终态，不能再发起新预占 | 409 |
| `RATE_LIMITED` | 超出速率（突发）限制 | 429 |
| `QUOTA_EXHAUSTED` | 周期配额（含在途占用）用尽 | 429 |
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
| `idempotency-ttl-millis` | `86400000` | 幂等成功记录保留时长（同时是预占终态记录保留时长） |
| `reservation-ttl-millis` | `300000` | 预占占用超时：到期未结算自动释放，迟到结算被拒 |
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
- `GET  /admin/tenants/{tenantId}`：查看租户当前令牌与配额用量（`quotaUsed` 含在途占用，
  另含 `activeReservationCount`/`activeReservedAmount`）；
- `GET  /admin/audit`：查看最近的审计记录（拒绝、幂等重放、策略变更、降级、预占、结算、
  预占超时释放）。

## 6. 本地验证方法

前置：JDK 21+、Maven 3.9+。

```bash
# 运行全部测试（25 个，含并发/刷新/重试/降级与预占-结算场景，输出含 requestId 与判定依据）
mvn test

# 只跑某一类场景
mvn test -Dtest=TenantIsolationTest          # 租户隔离、临界额度、拒绝原因区分
mvn test -Dtest=ConcurrentBurstTest          # 200 并发突发严格不超发
mvn test -Dtest=IdempotencyTest              # 重复提交/并发同键/TTL
mvn test -Dtest=ReservationSettlementTest    # 预占并发不超额/多退少补/重复乱序只一次/超时释放/刷新迁移
mvn test -Dtest=ReservationHttpTest          # 预占/结算 HTTP 端到端状态码与响应头
mvn test -Dtest=PolicyRefreshTest            # 配置刷新不错乱、不丢用量
mvn test -Dtest=DegradationTest              # fail-open/fail-closed 降级
mvn test -Dtest=JsonPolicyProviderTest       # 策略加载/校验/原子替换
mvn test -Dtest=WebEndToEndTest              # HTTP 端到端状态码与响应头

# 启动应用后手工验证
mvn spring-boot:run
curl -i localhost:8080/api/echo -H 'X-Tenant-Id: tenant-a' -H 'X-Request-Id: demo-1'
# 连续调用可观察 200 -> 429(QUOTA_EXHAUSTED)；带相同 Idempotency-Key 的调用只扣一次

# —— 预占—结算两阶段（以 tenant-http，配额上限 5 为例）——
# 1) 预占 3：返回 reason=RESERVED, X-Reservation-Status=ACTIVE
curl -i -X POST localhost:8080/api/quotas/reservations \
  -H 'X-Tenant-Id: tenant-http' -H 'Idempotency-Key: job-123' \
  -H 'X-Request-Id: job-123-reserve' -H 'Content-Type: application/json' \
  -d '{"estimated":3}'
# 2) 重复预占同一键：reason=RESERVATION_REPLAY，不二次占用
curl -i -X POST localhost:8080/api/quotas/reservations \
  -H 'X-Tenant-Id: tenant-http' -H 'Idempotency-Key: job-123' \
  -H 'Content-Type: application/json' -d '{"estimated":3}'
# 3) 真实只用了 1：结算返回 reason=SETTLED，basis 含「多占退回 2」
curl -i -X POST localhost:8080/api/quotas/settlements \
  -H 'X-Tenant-Id: tenant-http' -H 'Idempotency-Key: job-123' \
  -H 'X-Request-Id: job-123-settle' -H 'Content-Type: application/json' \
  -d '{"actual":1}'
# 4) 再次结算：reason=RESERVATION_REPLAY（恰好一次，不重复计费）
#    等待超过 reservation-ttl-millis 未结算的预占，再来结算会得到 409 RESERVATION_EXPIRED

curl -s localhost:8080/admin/audit | jq .
curl -s localhost:8080/admin/tenants/tenant-http | jq .   # 含 activeReservationCount/activeReservedAmount
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
- **预占一致性**：在途占用与已结算消耗记在同一窗口同一把租户锁内（`quotaUsed` 同时包含
  两者），预占的"速率+配额"检查与占用、结算的多退少补、超时释放与窗口滚动全部在该锁内
  原子完成；预占键即幂等键，使重复预占/重复或乱序结算只生效一次；窗口滚动与配额维度
  刷新时在途占用改绑迁移，用量不消失、结算始终落在当前窗口与上限。
