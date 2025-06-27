package uw.ops.agent.vo.sub;

/**
 * 网络相关信息。
 */
public class InternetStats {

    /**
     * 已连接。
     */
    private int established;

    /**
     * 对方关闭连接中或异常中断中。
     */
    private int closeWait;

    /**
     * 我方关闭连接中。
     */
    private int timeWait;

    /**
     * 本地端主动发起连接后，等待对方确认（客户端主动连接时的初始状态）。
     */
    private int synSent;

    /**
     * 服务器收到 SYN 包后，正在等待本地应用程序处理连接。
     */
    private int synRecv;

    public int getEstablished() {
        return established;
    }

    public void setEstablished(int established) {
        this.established = established;
    }

    public int getCloseWait() {
        return closeWait;
    }

    public void setCloseWait(int closeWait) {
        this.closeWait = closeWait;
    }

    public int getTimeWait() {
        return timeWait;
    }

    public void setTimeWait(int timeWait) {
        this.timeWait = timeWait;
    }

    public int getSynSent() {
        return synSent;
    }

    public void setSynSent(int synSent) {
        this.synSent = synSent;
    }

    public int getSynRecv() {
        return synRecv;
    }

    public void setSynRecv(int synRecv) {
        this.synRecv = synRecv;
    }
}
