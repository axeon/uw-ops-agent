package uw.ops.agent.vo;

import java.io.Serializable;
import java.util.Date;

/**
 * 运维中心下发给 Agent 的任务数据。
 * <p>
 * 执行载荷统一为 {@link #taskData}（本地契约类 {@link TaskData}，按 ExecType=SHELL/DOCKER
 * 分发）；taskInfo 为展示元数据列。executeDate 为领取时间随领取响应下发，agent 原样随上报
 * 回传、不覆写——center 侧迟到判定为"上报 executeDate 等于行内 execute_date 才接受"
 * （任务被超时重领后行内已刷新为新时刻，旧执行者回传的旧时刻被拒绝），迟到结果不覆盖新领取。
 * 回程字段 dockerId（Docker 返回的容器 Id，containerHash 台账唯一来源）与 actualPorts
 * （实际端口核对，Properties 格式）由 DOCKER 任务回填。
 * 协议键名统一 camelCase（默认 bean 命名，无 @JsonProperty 注解，与 center 侧契约逐字一致）。
 *
 * @author axeon
 */
public class OpsTask implements Serializable {

    /**
     * 任务id。
     */
    private long id;

    /**
     * 部署计划id（升级任务为 0）。
     */
    private long planId;

    /**
     * 计划容器id（升级任务为 0；label 与幂等定位材料）。
     */
    private long containerId;

    /**
     * 集群id（升级任务为 0）。
     */
    private long clusterId;

    /**
     * 目标主机id。
     */
    private long hostId;

    /**
     * 目标主机 hash（结果上报时回填本机 hostHash）。
     */
    private String hostHash;

    /**
     * 任务类型：CMD(0)/START(1)/STOP(2)，取值见 {@link uw.ops.agent.constant.TaskType}
     */
    private int taskType;

    /**
     * 统一执行载荷（ExecType=SHELL/DOCKER 分发；taskInfo 独立保留）
     */
    private TaskData taskData;

    /**
     * 任务信息（展示元数据，结果上报不回传）
     */
    private String taskInfo;

    /**
     * 任务执行结果（sensitiveOutput 任务上报固定文案）。
     */
    private String taskResult;

    /**
     * 错误信息（固定英文摘要，不含秘密与内部细节）。
     */
    private String taskError;

    /**
     * 回程：Docker 返回的容器 ID（DOCKER 任务必填，containerHash 台账来源）
     */
    private String dockerId;

    /**
     * 回程：实际监听端口核对（可选，Properties 格式，center 与台账分配值比对）
     */
    private String actualPorts;

    /**
     * 任务下发时间。
     */
    private Date createDate;

    /**
     * 计划执行时间。
     */
    private Date scheduleDate;

    /**
     * 领取标识时间：Center 下发的 execute_date 原样回传，Agent 不覆盖；
     * Center 在结果事务内与当前领取值等值校验以拒绝旧领取结果。
     */
    private Date executeDate;

    /**
     * 执行结束时间（结果上报前写回）。
     */
    private Date finishDate;

    /**
     * 状态（TaskState.value）。
     */
    private int state;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getPlanId() {
        return planId;
    }

    public void setPlanId(long planId) {
        this.planId = planId;
    }

    public long getContainerId() {
        return containerId;
    }

    public void setContainerId(long containerId) {
        this.containerId = containerId;
    }

    public long getClusterId() {
        return clusterId;
    }

    public void setClusterId(long clusterId) {
        this.clusterId = clusterId;
    }

    public long getHostId() {
        return hostId;
    }

    public void setHostId(long hostId) {
        this.hostId = hostId;
    }

    public String getHostHash() {
        return hostHash;
    }

    public void setHostHash(String hostHash) {
        this.hostHash = hostHash;
    }

    public int getTaskType() {
        return taskType;
    }

    public void setTaskType(int taskType) {
        this.taskType = taskType;
    }

    public TaskData getTaskData() {
        return taskData;
    }

    public void setTaskData(TaskData taskData) {
        this.taskData = taskData;
    }

    public String getTaskInfo() {
        return taskInfo;
    }

    public void setTaskInfo(String taskInfo) {
        this.taskInfo = taskInfo;
    }

    public String getTaskResult() {
        return taskResult;
    }

    public void setTaskResult(String taskResult) {
        this.taskResult = taskResult;
    }

    public String getTaskError() {
        return taskError;
    }

    public void setTaskError(String taskError) {
        this.taskError = taskError;
    }

    public String getDockerId() {
        return dockerId;
    }

    public void setDockerId(String dockerId) {
        this.dockerId = dockerId;
    }

    public String getActualPorts() {
        return actualPorts;
    }

    public void setActualPorts(String actualPorts) {
        this.actualPorts = actualPorts;
    }

    public Date getCreateDate() {
        return createDate;
    }

    public void setCreateDate(Date createDate) {
        this.createDate = createDate;
    }

    public Date getScheduleDate() {
        return scheduleDate;
    }

    public void setScheduleDate(Date scheduleDate) {
        this.scheduleDate = scheduleDate;
    }

    public Date getExecuteDate() {
        return executeDate;
    }

    public void setExecuteDate(Date executeDate) {
        this.executeDate = executeDate;
    }

    public Date getFinishDate() {
        return finishDate;
    }

    public void setFinishDate(Date finishDate) {
        this.finishDate = finishDate;
    }

    public int getState() {
        return state;
    }

    public void setState(int state) {
        this.state = state;
    }

}
