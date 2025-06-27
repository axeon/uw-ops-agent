package uw.ops.agent.vo;

import java.io.Serializable;
import java.util.Date;

/**
 * OpsDeployTask实体类
 * 部署任务对列表
 *
 * @author axeon
 */
public class OpsTask implements Serializable {

    /**
     * id
     */
    private long id;

    /**
     * 计划id
     */
    private long planId;

    /**
     * 计划实例id
     */
    private long instanceId;

    /**
     * 集群id
     */
    private long clusterId;

    /**
     * 主机id
     */
    private long hostId;

    /**
     * hostHash
     */
    private String hostHash;

    /**
     * 任务端口
     */
    private String taskPorts;

    /**
     * 任务类型
     */
    private int taskType;

    /**
     * 任务信息
     */
    private String taskInfo;

    /**
     * 任务脚本
     */
    private String taskScript;

    /**
     * 任务执行结果
     */
    private String taskResult;

    /**
     * 错误信息
     */
    private String taskError;

    /**
     * 任务下发时间
     */
    private java.util.Date createDate;

    /**
     * 计划时间
     */
    private java.util.Date scheduleDate;

    /**
     * 开始执行时间
     */
    private java.util.Date executeDate;

    /**
     * 执行结束时间
     */
    private java.util.Date finishDate;

    /**
     * 状态
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

    public long getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(long instanceId) {
        this.instanceId = instanceId;
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

    public String getTaskPorts() {
        return taskPorts;
    }

    public void setTaskPorts(String taskPorts) {
        this.taskPorts = taskPorts;
    }

    public String getTaskInfo() {
        return taskInfo;
    }

    public void setTaskInfo(String taskInfo) {
        this.taskInfo = taskInfo;
    }

    public String getTaskScript() {
        return taskScript;
    }

    public void setTaskScript(String taskScript) {
        this.taskScript = taskScript;
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
