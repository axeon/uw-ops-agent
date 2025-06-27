package uw.ops.agent.vo.sub;


import java.util.Date;

/**
 * docker容器统计
 */
public class DockerPs {

    /**
     * 容器id。
     */
    public String id;

    /**
     * 容器名。
     */
    public String name;

    /**
     * 镜像。
     */
    public String image;

    /**
     * 创建时间。2023-03-27 14:37:43 +0800 CST
     */
    private Date createDate;

    /**
     * 执行的命令。
     */
    private String command;

    /**
     * 网络模式。
     */
    private String network;

    /**
     * 运行状态。running
     */
    private String state;

    /**
     * 启动时间。Up 5 weeks
     */
    private String status;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public Date getCreateDate() {
        return createDate;
    }

    public void setCreateDate(Date createDate) {
        this.createDate = createDate;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getNetwork() {
        return network;
    }

    public void setNetwork(String network) {
        this.network = network;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
