package uw.ops.agent.vo.sub;

import org.apache.commons.lang3.builder.ToStringBuilder;

/**
 * 网络相关信息。
 */
public class InternetStats {

    /**
     * 对方关闭连接中或异常中断中。
     */
    private int closeWait;

    /**
     * 已连接。
     */
    private int established;

    /**
     * 我方请求连接中。
     */
    private int synSend;

    /**
     * 我方关闭连接中。
     */
    private int timeWait;

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("closeWait", closeWait)
                .append("established", established)
                .append("synSend", synSend)
                .append("timeWait", timeWait)
                .toString();
    }

    public int getCloseWait() {
        return closeWait;
    }

    public void setCloseWait(int closeWait) {
        this.closeWait = closeWait;
    }

    public int getEstablished() {
        return established;
    }

    public void setEstablished(int established) {
        this.established = established;
    }

    public int getSynSend() {
        return synSend;
    }

    public void setSynSend(int synSend) {
        this.synSend = synSend;
    }

    public int getTimeWait() {
        return timeWait;
    }

    public void setTimeWait(int timeWait) {
        this.timeWait = timeWait;
    }
}
