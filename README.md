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

### 预占与结算（先占用、后结算）

针对大文件转码、批量导出、报表生成这类**开始时无法确定最终用量、结果稍后才产生**的
异步业务，网关在一次性计量之外新增一层两阶段配额模型，与老路径共存、互不影响：

1. **预占（reserve）**：`POST /quota/reservations`，请求体
   `{"reservationId":"...","estimatedAmount":N}`。请求先过速率闸门（扣 1 令牌），
   再在租户锁内检查 `已用 + 在途占用 + 本次预占 <= 上限`；通过则把 N 个配额**占住**
   并放行。被占住的额度在结算/超时前对任何其他请求（含普通一次性请求）都不可用，
   因此同一租户并发突发时放行总量严格不超过上限，也不会漏算别人已占住的量。
2. **结算（settle）**：业务结束后调用 `POST /quota/settlements`，请求体
   `{"reservationId":"...","actualAmount":M}`。在同一把租户锁内把预占 N 换成真实
   用量 M：**M<N 退回差额、M>N 补扣差额、M=N 无退补**。结算结果用
   `X-Settlement-Status` 与 body 的 `status` 明确区分（见下表）。

| 结算 status | 含义 | 是否生效 | HTTP |
| --- | --- | --- | --- |
| `SETTLED_EXACT` | 实际=预占，无退补 | 是 | 200 |
| `SETTLED_REFUNDED` | 实际<预占，差额已退回 | 是 | 200 |
| `SETTLED_SUPPLEMENTED` | 实际>预占，差额已补扣 | 是 | 200 |
| `SETTLED_OVER_LIMIT` | 真实用量超过窗口上限，已按真实用量记账并标记超用 | 是 | 200 |
| `SETTLED_CLOSED_WINDOW` | 预占窗口已滚动/维度已变更，结算落到正确的旧窗口记账，不动当前窗口 | 是 | 200 |
| `SETTLEMENT_DUPLICATE` | 重复/乱序结算：返回首次结论，计量未再次改变 | 否 | 200 |
| `SETTLEMENT_EXPIRED` | 预占已超时失效，迟到结算不计费 | 否 | 409 |
| `SETTLEMENT_NOT_FOUND` | 预占不存在（从未创建或记录已过保留期） | 否 | 404 |
| `SETTLEMENT_INVALID` | 参数非法（实际用量为负、缺 reservationId） | 否 | 400 |
| `SETTLEMENT_DEGRADED` | 配置/内部状态不可用，fail-closed 拒绝结算，可安全重试 | 否 | 503 |

- **同一笔业务只结算一次**：`reservationId` 是预占/结算的唯一键。结算在租户锁内
  CAS 式地把 HELD 记录翻成 SETTLED 并固化首次结论；重复提交、乱序到达（后到的先
  结算、先到的后结算）、调用方超时重试，都只有首个结算真正改变计量，其余得到
  `SETTLEMENT_DUPLICATE` 与首次的实际用量/退补结论。同一 `reservationId` 重复预占
  返回 `IDEMPOTENT_REPLAY`，已处于终态的标识不允许复用（`INVALID_REQUEST`）。
- **占用超时自动失效（确定、可预期）**：每个预占有失效时刻
  `createdAt + reservation-ttl-millis`（默认 15 分钟）。失效有两条等价路径：
  后台守护线程 `reservation-reaper` 每 `reservation-sweep-interval-millis`（默认 10s）
  主动扫描释放；任何预占/结算/普通判定进入租户锁时也会**惰性释放**超时占用。
  因此即使没有新请求、或没有后台扫描，判定也永远不会把失效占用当成有效额度。
  释放只退回"占用"，不计费；**已正常结算的业务不受影响**。超时后才到达的结算得到
  `SETTLEMENT_EXPIRED`，不发生任何计费。已结算/失效的记录按
  `reservation-record-ttl-millis`（默认 24h）保留用于去重，之后物理清除（再来按
  NOT_FOUND 处理）。
- **窗口与刷新**：预占记录钉住创建时的窗口起点、策略版本与上限。窗口滚动后、TTL
  内到达的结算落到**正确的旧窗口**（`SETTLED_CLOSED_WINDOW`），不占用当前窗口额度；
  策略同维度刷新（只改速率）时在途占用与已用量原样保留，判定不错乱、用量不消失；
  配额维度（上限/窗口长度）变更按既有规则开新窗口，旧在途不结转，其迟到结算仍引用
  旧窗口版本。内部状态或配置不可用时，预占沿用全局 fail-open/fail-closed 降级
  （原因 `DEGRADED_DENY`/放行依据标注），结算独立给出 `SETTLEMENT_DEGRADED`
  （fail-closed 不生效，业务可在恢复后用同一标识安全重试）。
- **与老一次性计量如何共存**：不带预占的普通请求走原 `/api/**` 路径，行为完全不变；
  唯一的口径变化是其"窗口剩余配额"会扣除当前在途占用（上限 − 已用 − 在途占用），
  从而不会把别人占住的额度发出去。预占/结算接口位于 `/quota/**`，不经过
  `/api/**` 的限流过滤器，一次异步业务不会被双重计量；预占自身的速率/配额判定在
  引擎内完成。

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
| `INVALID_REQUEST` | 请求参数非法（缺预占标识、预占用量<1 等） | 400 |
| `UNKNOWN_TENANT` | 租户无生效策略 | 403 |
| `RATE_LIMITED` | 超出速率（突发）限制 | 429 |
| `QUOTA_EXHAUSTED` | 周期配额（含在途占用）用尽 | 429 |
| `DEGRADED_DENY` | fail-closed 降级拒绝 | 503 |

拒绝响应统一包含：`X-Request-Id`、`X-Tenant-Id`、`X-Decision-Reason`、
`X-Policy-Version`、`X-RateLimit-Remaining`、`X-Quota-Remaining`、
`Retry-After`（如适用），以及含 `basis`（判定依据）的 JSON body。
审计日志 logger 名称为 `AUDIT`（拒绝/降级为 WARN，幂等重放/策略变更/预占/结算/超时释放为 INFO）。

预占/结算接口（`/quota/**`）除 `X-Request-Id`、`X-Tenant-Id`、`X-Policy-Version` 外：
预占返回 `X-Decision-Reason`、`X-Quota-Remaining`（已扣除在途占用），body 含
`heldTotal`（当前窗口在途占用合计）与 `expiresAtEpochMilli`（失效时刻）；结算返回
`X-Settlement-Status` 与 `X-Settlement-Effective`，body 含 `reservedAmount`、
`actualAmount`、`delta`、`settledUsed` 与判定依据 `basis`。

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
| `reservation-ttl-millis` | `900000` | 预占占用最长保留时长，超时自动失效释放 |
| `reservation-sweep-interval-millis` | `10000` | 后台超时扫描间隔；`<=0` 仅靠惰性释放 |
| `reservation-record-ttl-millis` | `86400000` | 已结束预占记录保留时长（重复/迟到结算去重） |

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
- `GET  /admin/tenants/{tenantId}`：查看租户当前令牌、配额用量与在途预占
  （`quotaUsed` / `quotaHeld` / `reservationCount`）；
- `GET  /admin/audit`：查看最近的审计记录（拒绝、幂等重放、策略变更、降级、
  预占 `RESERVATION`、结算 `SETTLEMENT`、超时释放 `RESERVATION_EXPIRED`）。

## 6. 本地验证方法

前置：JDK 21+、Maven 3.9+。

```bash
# 运行全部测试（25 个，含并发/刷新/重试/降级/预占结算场景，输出含 requestId 与判定依据）
mvn test

# 只跑某一类场景
mvn test -Dtest=TenantIsolationTest          # 租户隔离、临界额度、拒绝原因区分
mvn test -Dtest=ConcurrentBurstTest          # 200 并发突发严格不超发
mvn test -Dtest=IdempotencyTest              # 重复提交/并发同键/TTL
mvn test -Dtest=PolicyRefreshTest            # 配置刷新不错乱、不丢用量
mvn test -Dtest=DegradationTest              # fail-open/fail-closed 降级
mvn test -Dtest=JsonPolicyProviderTest       # 策略加载/校验/原子替换
mvn test -Dtest=WebEndToEndTest              # HTTP 端到端状态码与响应头
mvn test -Dtest=ReservationSettlementTest    # 预占/结算：并发不超额、多退少补、
                                             #   重复乱序只生效一次、超时释放、刷新落窗口
mvn test -Dtest=QuotaHttpEndToEndTest        # /quota 预占结算 HTTP 端到端

# 启动应用后手工验证（先占用、后结算）
mvn spring-boot:run
# 1) 预占 3 个配额（tenant-async 上限 20）
curl -i -X POST localhost:8080/quota/reservations \
  -H 'X-Tenant-Id: tenant-async' -H 'X-Request-Id: job-1-req' \
  -H 'Content-Type: application/json' \
  -d '{"reservationId":"job-1","estimatedAmount":3}'
# 2) 业务跑完，按真实用量 1 结算 -> status=SETTLED_REFUNDED, delta=-2
curl -i -X POST localhost:8080/quota/settlements \
  -H 'X-Tenant-Id: tenant-async' -H 'X-Request-Id: job-1-settle' \
  -H 'Content-Type: application/json' \
  -d '{"reservationId":"job-1","actualAmount":1}'
# 3) 重复结算 -> 200 但 X-Settlement-Status=SETTLEMENT_DUPLICATE, effective=false
curl -s -X POST localhost:8080/quota/settlements \
  -H 'X-Tenant-Id: tenant-async' -H 'Content-Type: application/json' \
  -d '{"reservationId":"job-1","actualAmount":9}' | jq .
# 4) 查看租户用量与在途占用（quotaUsed / quotaHeld / reservationCount）
curl -s localhost:8080/admin/tenants/tenant-async | jq .
curl -s localhost:8080/admin/audit | jq .
curl -s -X POST localhost:8080/admin/policies/reload
```

超时释放的手工验证：把 `reservation-ttl-millis` 调小（如 5000），预占后等待超过 TTL
再观察 `/admin/tenants/{id}` 的 `quotaHeld` 归零、`/admin/audit` 出现
`RESERVATION_EXPIRED` 记录；之后再结算同一笔得到 `SETTLEMENT_EXPIRED`（409，不计费）。

## 7. 关键设计说明（一致性从何而来）

- **不超发/不少计**：速率与配额的"检查 + 扣减"在每个租户对象的同一把监视器锁内完成；
  配额用量与允许数严格相等有并发测试佐证（200 线程/允许严格=容量）。
- **租户互不影响**：租户状态按租户分桶、各自加锁；一个租户的突发只会耗尽自己的桶。
- **重试一致性**：幂等成功记录 + 同键 in-flight 占位（基于 `CountDownLatch` 的
  happens-before 传递首请求结论），等待者从不重新进入计量逻辑。
- **刷新一致性**：判定只读不可变快照；快照通过原子引用替换；计量状态独立于策略存放，
  刷新本身不触碰已扣用量。
- **预占不超发**：可放行额度按 `上限 − 已用 − 在途占用` 计算，检查与占用登记在租户
  锁内原子完成；普通请求与预占请求竞争的是同一把锁、同一份在途占用合计。
- **结算只生效一次**：结算在租户锁内把 HELD 记录翻转为 SETTLED 并固化首次结论；重复/
  乱序/重试读到终态后只回放结论，绝不二次退补。窗口滚动后、TTL 内的结算按记录钉住的
  旧窗口/版本/上限记账；超过 TTL 的占用已被惰性或后台扫描释放，结算拒绝且不计费。
- **超时确定失效**：失效是纯时间规则 `createdAt + TTL`，后台扫描与请求路径惰性释放
  双保险且语义完全一致（都在同一把租户锁内执行同一释放逻辑），只释放占用、不误伤
  已结算业务。
