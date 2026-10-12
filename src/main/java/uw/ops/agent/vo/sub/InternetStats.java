package uw.ops.agent.vo.sub;

/**
 * TCP 连接状态统计（窗口结束时快照：直读 /proc/net/tcp(/tcp6) 按状态位计数，
 * LISTEN 不计，对齐 ss -t 口径）。
 */
public class InternetStats {

    /**
     * ESTABLISHED 连接数。
     */
    private int established;

    /**
     * CLOSE_WAIT 连接数（对方已关闭，本地应用未关）。
     */
    private int closeWait;

    /**
     * TIME_WAIT 连接数（我方关闭后的等待期）。
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
