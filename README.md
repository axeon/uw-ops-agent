# uw-ops-agent

服务器运维监控 Agent，部署在被管理的宿主机上，向 [uw-ops-center](../uw-ops-center) 上报主机性能与容器指标，并拉取/执行 center 下发的运维任务（Shell 脚本）。

当前版本：`1.5.0`——产品名称与版本由 pom.xml 的 project.name/project.version 经 Maven filtering 注入 `project-info.properties`，运行时 `Constants.getAgentInfo()` 读取（改名改版本只动 pom）。

## 功能

- 定时采集并上报宿主机静态信息（CPU/内存/磁盘/网络/文件系统，每小时一次；文件系统过滤容器运行时挂载——overlay/aufs 与 `/var/lib/docker|containers|kubelet` 目录下的挂载，只上报真实数据盘）
- **60 秒窗口聚合**上报宿主机运行统计与全量容器指标（CPU/内存/负载/网络/磁盘 IO/容器 CPU 限流占比），均值/速率/占比全部在 Agent 端算好再上报
- 通过本机容器运行时 Engine API（UDS 直连，docker/podman 自动探测，无 CLI、无 docker-java、不暴露 TCP）采集**全部容器**（含手工/第三方容器与 exited 容器）
- 每 10 秒拉取一次待执行任务，校验签名后本地执行并上报结果
- 支持 Agent 自升级任务（下载新版本后退出，由 systemd 拉起新进程）

## 采集模型（两点差分架构）

**所有指标为累计计数的两点差分**：主轮每次激发时采集当前值，与上次激发保存值做差——差分覆盖恰好等于刚结束的完整窗口，是**精确满窗均值**（非近似、无采样相位亏损）。

| 任务 | 周期 | 内容 |
|---|---|---|
| `StatsReportTask`（主轮） | **60 秒恒定激发（`scheduleAtFixedRate`）** | 一次执行完成：inventory（`GET /containers/json?all=1` 全量）→ running 容器 stats（差分第二点，并发 8、12s 预算）→ Host 边界采集（同刻 δ=0）→ 差分与事件合并组包 → 上报；初始延迟随机打散 0~59s |
| `DockerEventTask` | 10 秒 | 容器事件窗口采集（`GET /events?since&until` 单请求，全量容器事件）；高密度拉取对抗 daemon journal 环形缓冲（64 条硬编码、丢失不可检测）被冲刷：10s 间隔安全阈值 6.4 条/s（≈10 个并发 crash-loop 容器），批量脚本启停 50+ 条/s 属架构级限制防不住；独立调度错开相位，失败跳轮下轮无缝续拉；事件随主轮 payload 一起发送，不搭独立请求 |
| `UploadHostInfoTask` | 1 小时 | 主机静态信息 |
| `ProcessOpsTask` | 10 秒 | 拉取并执行任务（无 secret 时跳过） |

主轮各环节硬预算：inventory 5s + stats 12s + 上报 15s ≈ **33s < 60s 周期**——`fixedRate` 激发时刻恒定（窗口恒定 60s），执行永远追不上激发；极端停摆后的追赶轮 gap 异常 → 该窗口 NULL，无雪崩。

### Host 侧（一次 OSHI 读取，主轮激发时采）

- `HostBoundaryCounters`（**唯一保存的"当前数值"**）：CPU ticks / cs / int / 网卡字节 / 磁盘计数
- 封箱时与上次激发的快照做差：CPU 占比 = Δtick/ΣΔtick；cs/int/网卡速率 = 边界差 ÷ 实际间隔秒；磁盘 = 窗口增量（次数/字节，不除秒）
- 防护（组级 NULL）：首窗无基线 → NULL；任一 tick 回退（主机重启，累计特性保证必检出）→ 整组 NULL；边界间隔 ∉ [45s, 90s]（停摆/追赶）→ NULL
- mem/load/fs/TCP 连接数/进程线程数：激发时刻快照
- 启动时 `HostStatsCollector.init()` 立即建 Host 基线，主轮初始延迟 ≥45s 时首个窗口即产出有效差值（否则首个有效窗口为第二窗）

### 容器侧（Engine API，UDS 自实现 HTTP/1.1 reader）

- 端点：启动 `GET /version` 协商 API 版本前缀并探测 socket（docker/podman 候选序）；采集 3 个 GET（`/containers/json?all=1`、`/containers/{id}/stats?stream=false&one-shot=1`、`/events?since&until`），批量并发 8、单请求 5 秒超时、响应上限 8MB；`/containers/{id}/json`（inspect）方法保留备用（配额/SizeRw 等独家字段的未来接入点），当前无调用方
- **60 秒两点采样**（每窗 2 次：上次激发 + 本次激发）：stats 为精确累计计数，Δtotal ÷ Δt 即精确满窗均值；daemon 侧负载千容器约 ~17 QPS（~1.7% 单核）
- 每容器保存一个差分基准快照（上次激发的归一化计数）：本轮快照 − 基准 = 窗口差，差分完基准即更新为本轮；消失容器由 inventory 成功轮同步清理基准
- 指标语义：`cpuPercent` 单核口径百分比（可超 100%，×10 = milliCores）；`cpuThrottle` 窗口限流时长占比（0-100 两位小数）；net/disk 为窗口平均速率（B/s）。容器指标字段全基本类型：差分失效（无基准/gap 越界/计数回退）输出 0，**未采集由行级存在性表达**（statsList=null）；host 网络模式容器无 networks 块 → 网络项为 0
- **容器视图三合一**：主轮组包把 observed（inventory）+ 窗口统计（stats 差分）+ 事件（10s 轮累积）聚合在 `ContainerInfo` 上，**容器 hash 全链路只传一遍**——`statsList: List<Stats>`（`Stats` 为静态嵌套类；List 形状前瞻多段统计，当前每窗一条；null=未采集）与 `eventList: List<Event>`（`Event` 仅 action/timeNano/exitCode，hash 由外层承载；null=本窗无事件）。事件容器不在存活快照时以 `state="destroyed"` 兜底 info 承载（状态语义统一走 state 字段，无独立 boolean；--rm 秒退等瞬时容器事件不丢，Center 只处理其事件不动台账）；本轮快照失败时 payload 仍含事件兜底容器，Center 据成员构成推断快照成败（存在非 destroyed 成员即成功），失败时只处理事件不动台账
- 事件管道：写入路径平铺累积（append + 超限丢最旧，上限 10000 条 ≈2MB 防失控），按容器分组合并在主轮组包最后一步完成（此前步骤抛异常事件仍留缓存）；异常口径判定（exitCode 过滤 0/143/137——137=OOM 归 oom 专列两列互斥）、台账累加、告警全部收口在 Center 单点，明细落 `ops_container_event`（(host_id, time_nano) 唯一键幂等）；上报全败从 payload 提取事件回灌，下轮重报不丢

### 数值语义与上报幂等

- **组级 NULL**（主机侧）：CPU 组无效整组 NULL、磁盘/网卡组仅骨架——NULL=失败/不适用
- **行级存在性**（容器侧）：`statsList=null`=未采集（exited 容器不在 running 采集集/本轮 stats 失败）、`eventList=null`=本窗无事件；字段级值恒为基本类型（差分失效=0）
- 上报幂等：Center 以 `(host_id, create_date)` 唯一键去重（stats 窗口）+ `(host_id, time_nano)` 唯一键去重（事件明细）；`create_date` = 窗口结束时间（协议字段 `statsDate`）
- 发送与采集解耦：上报失败由 MainService **发送层当场重试同一 payload**（3 次×5s），全失败放弃该窗口——但事件从 payload 提取回灌缓存下轮再报（窗口快照可弃、事件不可再生）；每轮 `buildHostStats()` 恒产出全新窗口对象

## 与 center 的交互协议

### 鉴权（v1：per-host secret + 双向 HMAC 签名）

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
| `X-Agent-Sign` | `HMAC-SHA256(hostHash + ":" + body + ":" + stamp, agentSecret)` 的十六进制值，仅有 secret 时携带 |

下行任务签名：center 在 `getTaskList` 响应的每个任务上附带
`taskSign = HMAC-SHA256(taskId + ":" + taskScript + ":" + taskStamp, agentSecret)`，
Agent 执行前用本地 secret 验签，校验原始脚本与时间窗（5 分钟）。

### host-disabled 自愈

center disable 主机时清空 `agentSecret`、置 `state=DISABLED`。Agent 下次任意请求收到：

- HTTP 401 + 响应头 `X-Agent-Reason: host-disabled`

Agent 据此**自动清空本地 secret** 并停止拉取任务（`ProcessOpsTask` 检测无 secret 即跳过）。
重新 enable 时 center 生成全新 secret，Agent 下次 `uploadHostStats` 重新领取。

> 注意：只有 `host-disabled` 标记的 401 才清 secret。其它 401（签名错/IP 不符等）保留 secret 重试，避免偶发抖动导致密钥丢失。

### 密钥丢失恢复

若 Agent 本地 secret 文件丢失/损坏，但 center 端 `secret_delivered=1`（认为已下发），Agent 会因无签名头持续被拒。**此死锁只能由运维 disable→enable 恢复**（重新生成密钥）。前端 `secret_delivered=1` 但 `last_update` 长期不推进可识别此情况。

### 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/agent/ops/uploadHostInfo` | 注册/更新主机静态信息（注册前接口，仅 IP 限制） |
| POST | `/agent/ops/uploadHostStats` | 上报 60 秒窗口批次（容器视图三合一：observed+statsList+eventList）；首次下发 secret 通过响应头返回 |
| GET | `/agent/ops/getTaskList` | 拉取待执行任务列表（含下行签名） |
| POST | `/agent/ops/reportTaskResult` | 上报任务执行结果 |

### 安全约束

- **无 secret 不执行任何任务**：`ProcessOpsTask` 检测到本地无 secret 时跳过任务拉取，`verifyTaskSign` 无 secret 时直接抛异常
- **危险命令拦截**：`ShellRiskChecker` 用正则黑名单拦截 `rm -rf /`、`mkfs`、`dd` 写裸盘、fork bomb、关机、覆写 `/etc/passwd` 等破坏性命令（辅助防线，挡误操作，不挡对抗）
- **任务执行约束**：普通任务 10 分钟、升级任务 5 分钟超时；超时终止进程树，非零退出码按失败上报
- **升级任务排序**：同批次任务里升级任务排到最后执行，避免其 `System.exit` 丢弃后续任务

## 配置

### 环境变量

| 变量 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `OPS_CENTER_HOST` | 否 | `http://127.0.0.1:1000` | center 地址 |
| `OPS_DOCKER_SOCK`（未设置时自动探测 docker/podman 候选 socket） | 否 | `/var/run/docker.sock` | Engine API UDS socket 路径（非默认路径/rootless 部署时覆盖） |
| `OPS_AGENT_SECRET_FILE` | 否 | `/etc/uw-ops-agent/agent.secret` | secret 文件路径 |

> 环境变量名与默认值统一收口在 `constant/Constants.java`。secret 由 center 审核后首次上报时自动下发，**无需手动配置**；密钥丢失或轮换时，运维在 center 控制台 disable→enable 该主机即可重新生成并下发。

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

`MainService.main` 启动一个 4 线程的 `ScheduledExecutorService`，调度四个定时任务（见上文"采集模型"表）。`HostStatsCollector` 是采集与组包的单点门面：主机侧保存上一次激发的 `HostBoundaryCounters`，容器侧每容器一个差分基准快照（`lastContainerCounters`），事件侧一条平铺 FIFO 缓存（`pendingEvents`，10s 事件线程写 / 60s 主轮 drain 分组）。核心方法 `buildHostStats(hostHash, inventory, statsMap)` 一体完成基准推进→容器差分→inventory 投影→Host 差分→事件合并（最后一步）→组包；对 MainService 暴露的入口为 `init()` / `buildHostInfo()` / `buildHostStats()` / `pollContainerEvents()` / `restoreContainerEventsFromPayload()` 五个，其余全部 private。
