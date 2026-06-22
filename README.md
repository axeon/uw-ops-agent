# uw-ops-agent

服务器运维监控 Agent，部署在被管理的宿主机上，向 [uw-ops-center](../uw-ops-center) 上报主机性能与 Docker 容器状态，并拉取/执行 center 下发的运维任务（Shell 脚本）。

当前版本：`1.2.0`（见 `AgentInfo.AGENT_INFO`）。

## 功能

- 定时采集并上报宿主机静态信息（CPU/内存/磁盘/网络/文件系统，每小时一次）
- 定时采集并上报宿主机运行统计（CPU/内存/负载/网络/磁盘 IO，每 60 秒一次）
- 采集并上报 Docker 容器列表（`docker ps`）与容器性能统计（`docker stats`）
- 每 10 秒拉取一次待执行任务，校验签名后本地执行并上报结果
- 支持 Agent 自升级任务（下载新版本后退出，由 systemd 拉起新进程）

## 与 center 的交互协议

### 鉴权（v1：双向 HMAC 签名）

Agent 启动后通过环境变量 `OPS_CENTER_HOST` 连接 center，流程：

1. Agent 上报 `uploadHostInfo` 注册主机（state=INIT，无密钥）
2. 运维在 center 控制台审核通过（state=AUDIT），此时 center 为该主机标记 `secret_delivered=0`
3. Agent 下一次 `uploadHostStats` 请求，center 生成 per-host `agentSecret` 并通过响应头 `X-Agent-Secret` **一次性下发**，同时置 `secret_delivered=1`
4. Agent 落盘 secret 到 `/etc/uw-ops-agent/agent.secret`（权限 0400），后续所有请求携带上行签名头

上行签名头（`uploadHostStats` / `getTaskList` / `reportTaskResult`）：

| 头 | 说明 |
|---|---|
| `X-Agent-HostHash` | 主机 hash（硬件指纹） |
| `X-Agent-Stamp` | 请求时间戳（毫秒） |
| `X-Agent-Sign` | `HMAC-SHA256(hostHash + ":" + body + ":" + stamp, agentSecret)` 的十六进制值 |

下行任务签名：center 在 `getTaskList` 响应的每个任务上附带
`taskSign = HMAC-SHA256(taskId + ":" + taskScript + ":" + taskStamp, agentSecret)`，
Agent 执行前用本地 secret 验签，校验原始脚本与时间窗（5 分钟）。

### 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/agent/ops/uploadHostInfo` | 注册/更新主机静态信息（注册前接口，仅 IP 限制） |
| POST | `/agent/ops/uploadHostStats` | 上报主机统计；首次下发 secret 通过响应头返回 |
| GET | `/agent/ops/getTaskList` | 拉取待执行任务列表（含下行签名） |
| POST | `/agent/ops/reportTaskResult` | 上报任务执行结果 |

### 安全约束

- **无 secret 不执行任何任务**：`ProcessOpsTask` 检测到本地无 secret 时跳过任务拉取，`verifyTaskSign` 无 secret 时直接抛异常
- **危险命令拦截**：`ShellRiskChecker` 用正则黑名单拦截 `rm -rf /`、`mkfs`、`dd` 写裸盘、fork bomb、关机、覆写 `/etc/passwd` 等破坏性命令（辅助防线，挡误操作，不挡对抗）
- **任务执行超时**：普通任务 10 分钟、升级任务 5 分钟超时，超时 `destroyForcibly` 子进程，防止脚本 hang 拖垮调度线程
- **升级任务排序**：同批次任务里升级任务排到最后执行，避免其 `System.exit` 丢弃后续任务

## 配置

### 环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `OPS_CENTER_HOST` | 是 | center 地址，如 `http://ops-center:1000` |
| `OPS_AGENT_SECRET_FILE` | 否 | secret 文件路径，默认 `/etc/uw-ops-agent/agent.secret` |

> secret 文件由 center 审核后首次上报时自动下发，**无需手动配置**。密钥丢失或轮换时，在 center 控制台点"重置密钥"，下次上报会重新下发。

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

### native-image 配置采集（开发用）

在参考机器上跑一次 agent，用 native-image-agent 采集反射/资源配置：

```sh
java -agentlib:native-image-agent=config-output-dir=./src/main/resources/META-INF/native-image/config \
     -jar target/uw-ops-agent-1.2.0.jar
```

> 当前 `src/main/resources/META-INF/native-image/config/` 下的配置是在 Dell R720 + Rocky Linux 9 上采集的，其它机型可能需要重新采集。

> ⚠️ **升级 oshi 主版本后必须重新采集**：oshi 内部的 JNA 包装类（如 `oshi.jna.Struct$CloseableSysinfo`）在不同主版本之间不保证类名稳定，旧配置在 7.x 构建时可能报 class not found。6.8.2 → 7.3.1 这次升级后请务必跑一遍上面的 agent 命令重新生成 4 个 config 文件，再 diff 确认变化点。

## 运行模型

`MainService.main` 启动一个 4 线程的 `ScheduledExecutorService`，调度三个定时任务：

| 任务 | 周期 | 说明 |
|---|---|---|
| `UploadHostInfoTask` | 1 小时 | 上报主机静态信息 |
| `UploadHostStatsTask` | 60 秒 | 上报主机统计（首次上报用于换取 secret） |
| `ProcessOpsTask` | 10 秒 | 拉取并执行任务（无 secret 时跳过） |
