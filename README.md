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

- **超时怎么算**：每笔预占带一个确定的绝对过期时刻 `reservedAt + reservation-ttl-millis`
  （默认 5 分钟），判定边界确定：**`now >= expiresAt` 即失效**，与用哪个时钟无关、无歧义。
- **没有后续请求也会自动释放（后台巡检）**：网关内置一个守护线程巡检器
  （`ReservationReaper`，节奏由 `reservation-reaper-interval-millis` 配置，默认 1 秒），
  按固定延迟遍历所有出现过的租户，在**各自的租户锁内**做"只维护不扣减"：一旦到点，
  仍处于 `ACTIVE` 的占用立即标记为 `EXPIRED`，占用量**退回当前配额窗口**。
  因此即使该租户预占后业务失败、调用方挂掉、**此后再没有任何请求**，最迟
  `TTL + 一个巡检间隔` 后额度也会自动还回来，不必等下一次请求顺带清理。
- **释放后额度与记录的表现**：释放后该笔占用不再计入窗口已用量，`quotaUsed` 回落、
  `activeReservationCount`/`activeReservedAmount` 归零，退回的额度在当前窗口立即可被
  其它预占/普通请求使用。每笔释放写一条独立、可区分的 `RESERVATION_EXPIRED` 审计
  （可在 `/admin/audit` 查看），记录中可看出：是**哪笔预占**（预占键、首次预占
  requestId）、**放回了多少**（占用量）、预占/到期/实际释放时刻、以及**触发来源**
  （后台巡检 `scheduled-reaper` / 业务请求顺带 / 管理端查看 `admin-view:<租户>`）。
- **管理端查看始终自洽**：`GET /admin/tenants/{tenantId}` 在返回前也会在租户锁内先做
  一次同样的超时维护再读数，因此即使巡检线程恰好尚未跑到、租户也无后续请求，管理员看到的
  "已用量"与"在途占用"两个数也始终自洽（不会出现在途已为 0、已用量却还把它算成已用的
  情况）；查看顺带触发的释放同样写审计（触发来源标为管理端查看）。
- **对已结算业务的影响（恰好一次，互不误伤）**：
  - 已失效的占用**不能再被当成有效额度**参与后续判定；它迟到的结算返回 **409
    `RESERVATION_EXPIRED`** 被明确挡掉，**不会对已还回去的额度重复计费**（需要继续用
    额度时业务方应重新预占）；重复迟到的结算仍是同一拒绝原因。
  - **已经正常结算完成的业务不会被超时逻辑误伤**：超时只作用于仍处于 `ACTIVE` 的记录，
    `SETTLED` 是终态；对已结算业务重复提交的结算仍只返回 `RESERVATION_REPLAY`（携带首次
    结算 requestId 与首次真实用量），真正生效永远只有一次，窗口用量不被多扣。
- **释放方式有两条且共用同一把锁**：除上述后台巡检外，业务请求（预占/结算/普通判定）
  进入租户锁时仍会顺带做一次到期扫描（惰性兜底），管理端查看也会顺带维护。三者调用的是
  租户锁内**同一个状态迁移**（`ACTIVE → EXPIRED` 与退回用量原子完成），因此：
  - 巡检与并发请求互斥，**同一笔占用绝不会被释放两次**，重复跑巡检不产生第二条记录；
  - 释放与额度判定不会把同一份额度重复使用，并发下放行总量严格不超过窗口上限；
  - 把 `reservation-reaper-interval-millis` 设为 `<=0` 可关闭后台线程，退化为仅靠请求/
    管理端顺带清理的惰性模式（不建议在生产关闭）。

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
| `reservation-reaper-interval-millis` | `1000` | 超时自动释放的后台巡检节奏（毫秒）；到点占用无需后续请求即释放，`<=0` 关闭后台巡检 |
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
# 运行全部测试（32 个，含并发/刷新/重试/降级、预占-结算与超时自动释放场景，输出含 requestId 与判定依据）
mvn test

# 只跑某一类场景
mvn test -Dtest=TenantIsolationTest          # 租户隔离、临界额度、拒绝原因区分
mvn test -Dtest=ConcurrentBurstTest          # 200 并发突发严格不超发
mvn test -Dtest=IdempotencyTest              # 重复提交/并发同键/TTL
mvn test -Dtest=ReservationSettlementTest    # 预占并发不超额/多退少补/重复乱序只一次/超时释放/刷新迁移
mvn test -Dtest=ReservationReaperTest        # 无请求自动释放并留痕/迟到结算被拒已结算不受影响/并发不双释放不超发/后台线程
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

# —— 预占超时自动释放（无需租户再有任何请求）——
# 用短 TTL + 短巡检节奏启动，便于本地观察（也可写进 application.properties）：
mvn spring-boot:run -Dspring-boot.run.arguments=\
"--tenant-quota.reservation-ttl-millis=5000,--tenant-quota.reservation-reaper-interval-millis=1000"
# a) 预占 4 后不再发任何 /api 请求
curl -i -X POST localhost:8080/api/quotas/reservations \
  -H 'X-Tenant-Id: tenant-http' -H 'Idempotency-Key: job-lost' \
  -H 'Content-Type: application/json' -d '{"estimated":4}'
# b) 立刻看：quotaUsed=4, activeReservationCount=1, activeReservedAmount=4
curl -s localhost:8080/admin/tenants/tenant-http | jq .
# c) 等待约 6 秒（TTL 5s + 巡检 1s），期间不发任何业务请求；再看：
#    quotaUsed 回落、activeReservationCount=0、activeReservedAmount=0（额度已自动回到窗口）
curl -s localhost:8080/admin/tenants/tenant-http | jq .
# d) 释放痕迹：出现一条独立的 RESERVATION_EXPIRED，含预占键 job-lost、首次请求、
#    占用=4、到期/释放时刻、触发来源 scheduled-reaper
curl -s 'localhost:8080/admin/audit' | jq '.[] | select(.kind=="RESERVATION_EXPIRED")'
# e) 迟到结算被明确拒绝 409，且不重复计费
curl -i -X POST localhost:8080/api/quotas/settlements \
  -H 'X-Tenant-Id: tenant-http' -H 'Idempotency-Key: job-lost' \
  -H 'Content-Type: application/json' -d '{"actual":4}'

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
- **超时自动释放一致性**：后台巡检线程（`ReservationReaper`）只负责"按节奏叫一次"，真正的
  `ACTIVE → EXPIRED` 状态迁移与额度退回仍发生在每个租户自己的监视器锁内，与业务请求、
  管理端查看调用的是**同一个锁内维护流程**。因此租户无任何后续请求时占用也会在
  `TTL+一个巡检间隔` 内确定释放；巡检与并发判定天然互斥，同一笔占用不可能被释放两次、
  释放后的额度不可能被重复使用导致超发；管理端查看在锁内先维护后读数，`quotaUsed` 与
  `activeReservedAmount` 永远自洽。
