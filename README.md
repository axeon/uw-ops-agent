# uw-ops-agent

服务器运维监控 Agent，部署在被管理的宿主机上，向 [uw-ops-center](../uw-ops-center) 上报主机性能与容器指标，并拉取/执行 center 下发的运维任务（taskData 统一载荷：SHELL 脚本 / DOCKER 结构化容器操作）。

当前版本：`1.5.0`——产品名称与版本由 pom.xml 的 project.name/project.version 经 Maven filtering 注入 `project-info.properties`，运行时 `Constants.getAgentInfo()` 读取（改名改版本只动 pom）。

## 功能

- 定时采集并上报宿主机静态信息（CPU/内存/磁盘/网络/文件系统，每小时一次；文件系统过滤容器运行时挂载——overlay/aufs 与 `/var/lib/docker|containers|kubelet` 目录下的挂载，只上报真实数据盘）
- **60 秒窗口聚合**上报宿主机运行统计与全量容器指标（CPU/内存/负载/网络/磁盘 IO/容器 CPU 限流占比），均值/速率/占比全部在 Agent 端算好再上报
- 通过本机容器运行时 Engine API（UDS 直连，docker/podman 自动探测，无 CLI、无 docker-java、不暴露 TCP）采集**全部容器**（含手工/第三方容器与 exited 容器）
- 每 10 秒领取一次待执行任务（单线程循环领取，单条 CAS 协议），提交执行池**并发执行**（并发度可配置，默认 4），各任务独立上报结果
- 支持 Agent 自升级任务（只在执行池完全空闲时独占执行；下载新版本上报成功后优雅退出，由 systemd 拉起新进程）

## 采集模型（两点差分架构）

**所有指标为累计计数的两点差分**：主轮每次激发时采集当前值，与上次激发保存值做差——差分覆盖恰好等于刚结束的完整窗口，是**精确满窗均值**（非近似、无采样相位亏损）。

| 任务 | 周期 | 内容 |
|---|---|---|
| `StatsReportTask`（主轮） | **60 秒恒定激发（`scheduleAtFixedRate`）** | 一次执行完成：inventory（`GET /containers/json?all=1` 全量）→ running 容器 stats（差分第二点，并发 8、12s 预算）→ Host 边界采集（同刻 δ=0）→ 差分与事件合并组包 → 上报；初始延迟随机打散 0~59s |
| `DockerEventTask` | 10 秒 | 容器事件窗口采集（`GET /events?since&until` 单请求，全量容器事件）；高密度拉取对抗 daemon journal 环形缓冲（64 条硬编码、丢失不可检测）被冲刷：10s 间隔安全阈值 6.4 条/s（≈10 个并发 crash-loop 容器），批量脚本启停 50+ 条/s 属架构级限制防不住；独立调度错开相位，失败跳轮下轮无缝续拉；事件随主轮 payload 一起发送，不搭独立请求 |
| `UploadHostInfoTask` | 1 小时 | 主机静态信息 |
| `ProcessOpsTask` | 10 秒 | 任务领取轮（无 secret 时跳过）：**唯一的领取者**（单条 CAS 领取通道不变，串行领取天然安全），池空时进入"循环领取"连续领满空闲执行槽后提交任务执行池；池非空时本轮不领（升级任务门禁，见下）；各任务在执行池内独立执行、独立上报 |
| `ReadinessProbeTask` | **5 秒 tick（`scheduleWithFixedDelay` 防重入；条目按 nextProbeAt 到期执行：启动期 5s 密集探测、首次成功回落稳态 15s）** | 就绪探测循环（DSL 摘要模型）：配置双路径——**tcp/http 终值 DSL 经容器 label `uw.ops.probe` 声明式下发**（create 携带 + 主轮 inventory 收敛：label 在=配置在/值变=条目重建/容器删=条目消失，Docker 元数据即持久化真相源，agent 重启零恢复、改探针无需重部署）；**exec（或含 secret 占位符）经 START taskData 下发并落盘** `/etc/uw-ops-agent/probe/`（重启 `restoreFromDisk()` 重建；存量 shell 脚本自动按 exec 包装，零迁移）。执行器：tcp=JVM Socket connect、http=原生 HTTP 客户端（均零进程 spawn、毫秒级），exec=`/bin/sh -c` 默认 3s 超时（输出可能含令牌仅以退出码判定）。agent 只做无判断聚合：每容器摘要 {probeSeq 单调, probeState 0/1/2, probeDate, probeFails 连续失败, probeStreak 连续成功毫秒, probeReason 失败原因短码}，随主轮 `ContainerInfo` 上报（null 省略=不在探测集合或尚无样本）；判定阈值与动作（门禁/NOT_READY/替换）全在 Center，Center 失联期间照常探测、期望未变的实例保持现状。探测配置为 center 受信终值 DSL 不经过 ShellRiskChecker；inventory 失败轮不清条目 |

主轮各环节硬预算：inventory 5s + stats 12s + 上报 15s ≈ **33s < 60s 周期**——`fixedRate` 激发时刻恒定（窗口恒定 60s），执行永远追不上激发；极端停摆后的追赶轮 gap 异常 → 该窗口 NULL，无雪崩。

### Host 侧（一次 OSHI 读取，主轮激发时采）

- `HostBoundaryCounters`（**唯一保存的"当前数值"**）：CPU ticks / cs / int / 网卡字节 / 磁盘计数
- 封箱时与上次激发的快照做差：CPU 占比 = Δtick/ΣΔtick；cs/int/网卡速率 = 边界差 ÷ 实际间隔秒；磁盘 = 窗口增量（次数/字节，不除秒）
- 防护（组级 NULL）：首窗无基线 → NULL；任一 tick 回退（主机重启，累计特性保证必检出）→ 整组 NULL；边界间隔 ∉ [45s, 90s]（停摆/追赶）→ NULL
- mem/load/fs/TCP 连接数/进程线程数：激发时刻快照
- 启动时 `HostStatsCollector.init()` 立即建 Host 基线，主轮初始延迟 ≥45s 时首个窗口即产出有效差值（否则首个有效窗口为第二窗）

### 容器侧（Engine API，UDS 自实现 HTTP/1.1 reader）

- 端点：启动 `GET /version` 协商 API 版本前缀并探测 socket（docker/podman 候选序）；采集 3 个 GET（`/containers/json?all=1`、`/containers/{id}/stats?stream=false&one-shot=1`、`/events?since&until`），批量并发 8、单请求 5 秒超时、响应上限 8MB；`/containers/{id}/json`（inspect）由结构化任务通道消费（幂等定位与停止后退出码复核；配额/SizeRw 等独家字段的采集接入点未来再开）。写操作（create/start/stop/remove）仅限 `StructuredTaskExecutor` 触发，采集路径只读不变
- **60 秒两点采样**（每窗 2 次：上次激发 + 本次激发）：stats 为精确累计计数，Δtotal ÷ Δt 即精确满窗均值；daemon 侧负载千容器约 ~17 QPS（~1.7% 单核）
- 每容器保存一个差分基准快照（上次激发的归一化计数）：本轮快照 − 基准 = 窗口差，差分完基准即更新为本轮；消失容器由 inventory 成功轮同步清理基准
- 指标语义：`cpuPercent` 单核口径百分比（可超 100%，×10 = milliCores）；`cpuThrottle` 窗口限流时长占比（0-100 两位小数）；net/disk 为窗口平均速率（B/s）。容器指标使用可空数值：差分失效（无基准/gap 越界/计数回退）输出 NULL；整轮未采集使用 statsList=null；host 网络模式容器无 networks 块 → 网络项为 NULL；真实测得零增量才输出 0
- **容器视图三合一**：主轮组包把 observed（inventory）+ 窗口统计（stats 差分）+ 事件（10s 轮累积）聚合在 `ContainerInfo` 上，**容器 hash 全链路只传一遍**——`statsList: List<Stats>`（`Stats` 为静态嵌套类；List 形状前瞻多段统计，当前每窗一条；null=未采集）与 `eventList: List<Event>`（`Event` 仅 action/timeNano/exitCode，hash 由外层承载；null=本窗无事件）。事件容器不在存活快照时以 `state="destroyed"` 兜底 info 承载（状态语义统一走 state 字段，无独立 boolean；--rm 秒退等瞬时容器事件不丢，Center 只处理其事件不动台账）；本轮快照失败时 payload 仍含事件兜底容器，Center 据成员构成推断快照成败（存在非 destroyed 成员即成功），失败时只处理事件不动台账
- 事件管道：写入路径平铺累积（append + 超限丢最旧，上限 10000 条 ≈2MB 防失控），按容器分组合并在主轮组包最后一步完成（此前步骤抛异常事件仍留缓存）；异常口径判定（exitCode 过滤 0/143/137——137=OOM 归 oom 专列两列互斥）、台账累加、告警全部收口在 Center 单点，明细落 `ops_container_event`（(host_id, time_nano) 唯一键幂等）；上报全败从 payload 提取事件回灌，下轮重报不丢

### 数值语义与上报幂等

- **组级 NULL**（主机侧）：CPU 组无效整组 NULL、磁盘/网卡组仅骨架——NULL=失败/不适用
- **行级存在性**（容器侧）：`statsList=null`=未采集（exited 容器不在 running 采集集/本轮 stats 失败）、`eventList=null`=本窗无事件；字段级值可为 NULL（失败/不适用），0 仅表示真实测量为零
- 上报幂等：Center 以 `(host_id, create_date)` 唯一键去重（stats 窗口）+ `(host_id, time_nano)` 唯一键去重（事件明细）；`create_date` = 窗口结束时间（协议字段 `statsDate`）
- 发送与采集解耦：上报失败由 MainService **发送层当场重试同一 payload**（3 次×5s），全失败放弃该窗口——但事件从 payload 提取回灌缓存下轮再报（窗口快照可弃、事件不可再生）；每轮 `buildHostStats()` 恒产出全新窗口对象

## 与 center 的交互协议

### 鉴权（per-host secret + 双向 HMAC，下行响应 v2）

Agent 启动后连接 center（地址见环境变量 `OPS_CENTER_HOST`），流程：

1. Agent 上报 `uploadHostInfo` 注册主机（state=INIT，无密钥）
2. 运维在 center 控制台审核通过（enable）：center **生成 per-host `agentSecret` 存入 DB**，并标记 `secret_delivered=0`（等待 Agent 领取）、`auth_version=1`
3. Agent 下一次 `uploadHostStats` 请求（携带身份头但无签名），center 检测 `secret_delivered=0` → 通过响应头 `X-Agent-Secret` **一次性下发** DB 中那个 secret，并置 `secret_delivered=1`
4. Agent 落盘 secret 到 `/etc/uw-ops-agent/agent.secret`（权限 0600，原子写），后续所有请求携带上行签名头

上行请求头（`uploadHostStats` / `getTaskList` / `reportTaskResult`）：

| 头 | 说明 |
|---|---|
| `X-Agent-HostHash` | 身份头，**始终携带**（无论有无 secret），center 据此识别请求来源 |
| `X-Agent-Stamp` | 签名时间戳（毫秒），仅有 secret 时携带 |
| `X-Agent-Nonce` | 每次任务领取新生成的 32 位十六进制随机值，纳入上行签名；任务响应须回显且纳入响应签名 |
| `X-Agent-Sign` | `HMAC-SHA256(hostHash + ":" + body + ":" + stamp, agentSecret)`；任务领取额外追加 `":" + nonce`，仅有 secret 时携带 |

下行响应头（仅 `getTaskList` 任务下发通道）：

| 头 | 说明 |
|---|---|
| `X-Center-Nonce` | 回显本次请求的随机值，Agent 必须匹配发送时固定的 request identity |
| `X-Center-Stamp` | 响应签名时间戳（毫秒），限制响应时效（±300s） |
| `X-Center-Sign` | `HMAC-SHA256("center-response-v2:" + hostHash + ":" + nonce + ":" + body + ":" + stamp, agentSecret)` 的十六进制值；`center-response-v2:` 前缀与上行消息结构隔离（防上行签名被挪用为下行签名） |

任务通道（agent 为纯客户端，无监听面）：`getTaskList` 领取任务（一次最多一条到期任务，
executeDate=领取时间随响应下发）→ Agent 验证响应 nonce 与签名（缺头/nonce不符/超窗/签名不符丢弃本轮响应，
**绝不执行来路不明的任务**）→ 提交执行池并发执行 → `reportTaskResult` 各任务独立上报结果。
上行签名只认证 Agent 身份、不能证明响应来自 center——任务载荷可指定任意镜像/命令/密钥 env，
伪造响应=直接控制宿主机，故下行必须验签（任务下发通道专用；上传/上报响应无任务载荷不签）。
已知边界（有意保留现状）：TLS 不验证服务端证书——响应签名解决任务被伪造（完整性/真实性），
不解决载荷被主动 MITM 读取（机密性，任务载荷含注入的 secret env）；secret 首次下发
（`X-Agent-Secret` 响应头）存在 TOFU 信任窗口。
网络响应重放由每次请求独立的 nonce 阻止，包含 Agent 重启后的旧响应。Center 合法超时重领仍为至少一次执行语义，
START/STOP 由执行器幂等处理；非幂等 CMD 不保证业务恰好执行一次，重跑需由任务自身提供幂等性。
任务超时与迟到结果由 center 侧 `execute_date` 比对裁决——**迟到判定为"上报 executeDate 等于行内
execute_date 才接受"**：Agent 把领取时下发的 executeDate **原样随上报回传（不覆写）**——
正常执行回传恒等于行内值 → 接受；任务被超时重领后行内已刷新为新时刻，旧执行者回传的旧时刻
< 行内 → 拒绝（迟到上报不覆盖新领取）。行内值与回传值同源（均为 center 时钟写入），比对
不依赖跨机器墙钟（agent 本机时钟覆写会让慢时钟主机的正常结果被误判 stale 拒绝，已废弃该
做法）。center 的执行超时判定仍按行内 execute_date=领取时刻计时（领了没执行也计时，
崩溃兜底语义）。

任务执行并发模型：**领取单线程 + 执行池并发**。`ProcessOpsTask`（10s 轮）是唯一领取者，
每轮池空时循环领取直到填满空闲槽（并发度 `uw.ops.agent.max-concurrent-tasks`，默认 4，
执行池为固定线程池 daemon 线程 `agent-task-exec-N`）。**升级任务门禁**：升级任务（三 id 全 0）
会退出进程，只能在执行池完全空闲时独占执行——领取无法预读队列头（升级任务可能就在队头），
因此池非空时本轮不领任何任务，等池排空后的下一轮再领；同轮内先提交普通任务后领到升级任务
的罕见场景不丢弃，暂存等池排空后由领取线程同步执行（成功上报 → 执行池 shutdown+10s 短等待
→ 退出进程）。center 串行投放模式（GLOBAL_SERIAL/PER_HOST_SERIAL）下每主机同时最多一条到期
任务，并发模型自然退化为"同一时刻只有一条在执行"，无副作用。

任务载荷（v2.1 容器操作 API 化）：`taskData`（本地契约类 `vo/TaskData`，与 center 侧结构逐字一致）统一承载，
按 `execType` 分发——`SHELL` → `/bin/sh -c`（ShellRiskChecker 检查后原样执行最终脚本）。
Center 是端口分配和模板替换的唯一来源；SHELL 通道 Agent 不对领取时已展开的脚本二次替换、不改写已分配端口，避免损坏含字面 `${APP_PORT}` 的秘密值或偏离端口台账；
`DOCKER` → `StructuredTaskExecutor`（幂等定位 → 本机端口校验 → 镜像准备 → create+start+等待 running / stop?t=30 后 remove
消除已停残留中间态，各步 404 幂等、remove 失败任务 FAILED 供重跑补删），
Env（含密钥）随 create 请求体经 UDS 直传 daemon（无临时文件），日志与 taskError 不输出 Env 值（脱敏）；
**本机端口校验**：Center 台账分配只覆盖受管容器，非受管进程/手工容器/内核临时端口的占用 Center 不可见——
create 前对每个分配端口从分配值起本机 bind 探测（`NetworkUtils`，`synchronized` 串行探测+短时缓存互斥防并发双分配），
空闲原样保留、被占递增至下一个空闲并整体改写 hostPorts 为实际值（幂等命中分支不校验：容器已在运行，
端口被自身监听属预期）；PortBindings 与 `actualPorts` 回程均用实际使用端口，Center 台账比对不符即告警
（不信任回程覆盖台账）；端口空间耗尽任务 FAILED（固定英文摘要）。
**镜像准备（ensureImage）**：create 前先 GET /images/{ref}/json 探测本地镜像，缺失则 POST /images/create
按确定引用拉取（单次最多 8 分钟，并受整条任务的 10 分钟剩余预算限制；事件流逐行检查 error/errorDetail 字段，空流或损坏事件失败，认证失败/tag 不存在等以
{"error":...} 行报告）——Engine API 的 create 不具备 CLI run 的自动拉取行为，本地无镜像直接 404；
registry 凭据随任务下发（`RegistryAuth`=base64 json，center 按镜像域名匹配注入，用后即弃）；
`dockerId`/`actualPorts` 随 `reportTaskResult` 回程。

任务最小结果必须包含 `id/hostId/hostHash/taskType/executeDate/finishDate/state` 以及可选运行结果，CMD/START/STOP类型原样回传。
凡任务领取使用秘密，Center 标记 `taskData.sensitiveOutput=true`，Agent 固定屏蔽结果与错误详情；不含秘密的 CMD 保留输出。
敏感任务新建容器写入 `uw.ops.sensitive=true` 标签；inventory对全部受管容器（包括旧容器）及带此敏感标签的容器省略 Command，防止秘密回流，普通手工容器保留。历史已保存明文不会自动删除。
`taskData.secretKeys` 只记录秘密键名，值仅在领取响应中按需注入。
任何任务的脚本、输出、异常原文和 daemon 响应都不写日志，日志保留 taskId/state/errorType；Shell固定错误摘要保留退出码。
主机静态信息由 OSHI 实际采集 `cpuLogicalCount`，每小时上报刷新，Center 用于 CPU 容量核验。

### 主机禁用与密钥重置

Center disable 主机时清空 `agentSecret`、置 `state=DISABLED`，后续请求返回 HTTP 401。
Agent 拒绝失败响应但**保留本地可信 secret**：`X-Agent-Reason: host-disabled` 未认证，不能据此清钥并重新引导注册。
任务响应或任何稳态响应携带 `X-Agent-Secret` 都会被拒绝。仅无密钥 `uploadHostStats` 的成功响应允许首次保存；
任务验签使用发请求前已固定的可信 secret 与 nonce，无密钥、缺签、缺 nonce、nonce 不符、超窗、错签一律拒绝。

重新启用、密钥丢失/损坏或主动轮换时，由管理员先停止 Agent，在 Center 执行 disable→enable，
删除本机 `OPS_AGENT_SECRET_FILE` 指定的旧密钥文件，再启动 Agent 完成首次领取。首次领取仍依赖既定的可信部署网络/TLS 边界；
HTTP 响应不能触发这套重置流程。首次上报重试也不会覆盖并发已保存的密钥。

### 探测持久化与恢复

探测脚本以权限 `0600` 的临时文件写入并 fsync，再原子替换到 `OPS_AGENT_PROBE_DIR`（默认 `/etc/uw-ops-agent/probe`）。
恢复仅接受与容器 ID 同名的普通 JSON 文件，忽略临时文件/符号链接，坏条目不阻塞其它容器。
恢复后的样本是 UNKNOWN，必须重新探测；状态与 probeDate 作为同一样本原子上报。
并发清理旧条目不会删除新脚本；STOP 失败时保留仍在运行容器的探测。落盘失败会 WARN 并暂用内存，需按日志处理磁盘/权限问题。
模型 `ProbeScriptEntry`（探测脚本条目）位于 VO 包，自动纳入 Native 注册和 `--verify-native-metadata` 的落盘恢复绑定检查。

### 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/agent/ops/uploadHostInfo` | 注册/更新主机静态信息（注册前接口，仅 IP 限制） |
| POST | `/agent/ops/uploadHostStats` | 上报 60 秒窗口批次（容器视图三合一：observed+statsList+eventList）；首次下发 secret 通过响应头返回 |
| GET | `/agent/ops/getTaskList` | 领取任务：一次最多一条到期任务（executeDate=领取时间随响应下发；响应携带 X-Center-Sign 下行签名，验签失败丢弃本轮） |
| POST | `/agent/ops/reportTaskResult` | 上报任务执行结果（executeDate 原样回领取值回传，迟到结果由 center 比对裁决；重复上报幂等；各任务独立调用天然并发安全；回程仅含领取身份和结果，taskData/taskInfo 不回传；日志只记 id/type/state） |

### 安全约束

- **无 secret 不执行任何任务**：`ProcessOpsTask` 检测到本地无 secret 时跳过任务拉取与执行
- **下行响应验签**：`getTaskList` 响应必须携带有效 `X-Center-Sign`（缺头/超窗/签名不符丢弃本轮响应）——**升级约束：先暂停任务领取，协同升级 Center 和 Agent，再恢复**；下行 v2 不回退旧签名，两端版本不匹配时拒绝任务领取
- **危险命令拦截**：`ShellRiskChecker` 用正则黑名单拦截 `rm -rf /`、`mkfs`、`dd` 写裸盘、fork bomb、关机、覆写 `/etc/passwd` 等破坏性命令（辅助防线，挡误操作，不挡对抗）
- **任务执行约束**：普通任务 10 分钟、升级任务 5 分钟超时；超时终止进程树，非零退出码按失败上报
- **升级任务门禁**：升级任务只在执行池完全空闲时独占执行（避免其 `System.exit` 丢弃并发中的其它任务）；池忙时本轮不领任何任务（升级任务可能在队列头，领了不能执行会被 center 超时标失败），同轮领到的不丢弃、暂存等池排空后执行；升级退出前执行池 shutdown+10s 短等待（超时放弃，在途任务按崩溃兜底语义由 center 超时回收），JVM shutdown hook 兜底外部停机（systemd SIGTERM）

## 配置

### 环境变量

| 变量 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `OPS_CENTER_HOST` | 否 | `http://127.0.0.1:1000` | center 地址 |
| `OPS_DOCKER_SOCK`（未设置时自动探测 docker/podman 候选 socket） | 否 | `/var/run/docker.sock` | Engine API UDS socket 路径（非默认路径/rootless 部署时覆盖） |
| `OPS_AGENT_SECRET_FILE` | 否 | `/etc/uw-ops-agent/agent.secret` | secret 文件路径 |
| `OPS_AGENT_PROBE_DIR` | 否 | `/etc/uw-ops-agent/probe` | 受管容器探测脚本持久化目录 |
| `OPS_AGENT_MAX_CONCURRENT_TASKS` | 否 | `4` | 任务执行池并发度（也可用 System property `uw.ops.agent.max-concurrent-tasks` 覆盖，property 优先）；center 串行投放模式（GLOBAL_SERIAL/PER_HOST_SERIAL）下自然退化无害 |

> 环境变量名与默认值统一收口在 `constant/Constants.java`。secret 由 center 审核后首次上报时自动下发，**无需手动配置**；密钥丢失或轮换时，运维按上文先停止 Agent、在 Center disable→enable、显式删除本地旧 secret，再启动领取。

## 构建与部署

### 安装（推荐）

通过 center 的安装脚本一键安装（自动检测架构、下载二进制、配置 systemd）：

```sh
curl -s http://<ops-center-host>/agent/installer/install | bash
```

安装脚本会：
- 检测架构（x86_64 → amd64，aarch64 → arm64），不支持则回退 java 模式
- 下载 native 二进制或 fat jar 到 `/usr/sbin/uw-ops-agent`
- 生成 `/usr/lib/systemd/system/uw-ops-agent.service`（以 root 运行，`Restart=always`）
- 启动并 enable 服务

> 安装脚本**不含任何密钥**，secret 在主机审核后由 Agent 首次上报时从 center 换取。

### 手动构建

```sh
mvn clean package        # 生成 fat jar
# 或用 GraalVM native-image 编译原生二进制（见 .gitea/workflows/build.yml）
```

CI 使用 Oracle GraalVM Java 25 的浮动镜像标签 `container-registry.oracle.com/graalvm/native-image:25`；构建期 Feature API 当前依赖 `25.3.4.1`。两个架构均使用 `-march=compatibility`，避免默认 x86-64-v3/armv8.1-a 指令集限制；操作系统和 glibc 兼容性仍由构建镜像决定。

### native-image 配置采集（开发用）

原生元数据统一使用 schema v1.2.0 的 `META-INF/native-image/com.umtone/uw-ops-agent/reachability-metadata.json`，由 classpath 自动发现，不传递 `-H:ConfigurationFileDirectories`。该文件**只登记第三方**（JDK crypto/JNA/oshi/Jackson 等）反射、动态代理和资源；应用自身的 JSON 模型——`uw.ops.agent.vo`（上报/下行协议，含 `vo.sub` 子包与嵌套类）与 `uw.ops.agent.helper.vo`（Engine API 输入形状）——由 `AgentReflectionFeature` 在镜像构建期按 `.class` 文件递归扫描注册（**嵌套类是独立 .class 文件且同包路径，天然被收集**；协议 vo 的增删改/嵌套化均无需改元数据），仅新增**模型包或子包**时需在 `native-image.properties` 的 `--exact-reachability-metadata` 包白名单补登记。

构建产物会执行 `--verify-native-metadata`，覆盖 JSON 绑定（含嵌套类 statsList/eventList 序列化）、HMAC、TLS、资源、Linux JNA/OSHI、**UDS SocketChannel 自环收发**（Docker 采集的 NIO 传输路径）以及 shell 执行；tracing agent 未覆盖的动态路径仍需人工验证。

JNA、加密实现等第三方库仍使用 tracing agent 采集元数据。在每个目标架构的 Debian 12 环境分别运行：

```sh
ARCH=$(uname -m)
java -agentlib:native-image-agent=config-output-dir=target/native-metadata/${ARCH} \
     -jar target/uw-ops-agent-1.5.0.jar --verify-native-metadata
```

> 当前 `reachability-metadata.json` 已于 2026-09-03 使用 GraalVM CE 25.3.4.1 / JDK 25.0.4.1，在 Debian 12 的 linux/arm64 与 linux/amd64 环境分别采集并取并集。两份结果仅 JNA 本地库资源路径不同，最终文件同时保留 `linux-aarch64` 和 `linux-x86-64` 资源。

> ⚠️ **升级 GraalVM、JDK、OSHI 或 JNA 后必须在两个目标架构重新采集**。`config-output-dir` 会保留两份独立结果；review 架构差异后再取并集，不要直接覆盖正式文件。

## 运行模型

`MainService.main` 启动一个 5 线程的 `ScheduledExecutorService`，调度五个定时任务（见上文"采集模型"表）；另启动一个任务执行池（固定并发度 `uw.ops.agent.max-concurrent-tasks`，daemon 线程 `agent-task-exec-N`）承接 ops 任务的并发执行与独立上报——任务轮（`ProcessOpsTask`）只剩毫秒级领取与提交，结构化执行（拉镜像可占线程分钟级）全部在执行池，探测循环不被饿死。升级退出路径先执行池 shutdown+10s 短等待再 `System.exit`；JVM shutdown hook 兜底外部停机。就绪探测（DSL 解析/tcp=http 原生执行器/label 声明式收敛/无判断聚合）内聚在 `helper/ReadinessProber`（静态单例，与采集解耦）。`HostStatsCollector` 是采集与组包的单点门面：主机侧保存上一次激发的 `HostBoundaryCounters`，容器侧每容器一个差分基准快照（`lastContainerCounters`），事件侧一条平铺 FIFO 缓存（`pendingEvents`，10s 事件线程写 / 60s 主轮 drain 分组）。核心方法 `buildHostStats(hostHash, inventory, statsMap)` 一体完成基准推进→容器差分→inventory 投影→Host 差分→事件合并（最后一步）→组包；对 MainService 暴露的入口为 `init()` / `buildHostInfo()` / `buildHostStats()` / `pollContainerEvents()` / `restoreContainerEventsFromPayload()` 五个，其余全部 private。

### 隔离回归与冷镜像验证

日常 `mvn test` 使用 `target/test-agent` 下的测试密钥和探测目录（Surefire 经 `test.agent.secret-file`/`test.agent.probe-dir` 两个 property 注入环境变量），不写系统配置。
手工 `SmokeManualTest` 联调如需保留既有隔离路径，可显式传 `-Dtest.agent.secret-file=/tmp/uw-ops-smoke/agent.secret` 与
`-Dtest.agent.probe-dir=/tmp/uw-ops-smoke/probe`；Surefire 的这两个配置优先于外部同名环境变量。
真实冷镜像回归只创建带随机名的独立容器，配置 `network=none`，使用生产 `ensureImage → create → start → probe → stop → remove` 链，
不连接 Center/数据库，也不删除已有镜像；可使用 `-Dsmoke.cold-image-ref=<尚未缓存且默认持续运行的镜像>`。

```sh
OPS_DOCKER_SOCK=/path/to/docker.sock mvn -B -Dtest=DockerColdImageSmokeTest -Dsmoke.cold-image=true test
```

默认镜像 `traefik/whoami:v1.11.0` 已存在时该测试跳过，避免为冷缓存条件删除共享镜像。
本次修复在 macOS 的 GraalVM 25.0.3 完成原生产物构建及 `-XX:MissingRegistrationReportingMode=Exit --verify-native-metadata`；
Linux amd64/arm64 仍应按既有 CI 构建并运行同一验证器。
