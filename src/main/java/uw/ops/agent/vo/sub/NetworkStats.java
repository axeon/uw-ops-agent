package uw.ops.agent.vo.sub;

/**
 * 主机网卡窗口统计（60 秒两点差分的窗口平均速率）。
 * 静态属性（mac/ip/speed/index）见 {@link NetworkInfo}，不随本类传输；
 * 列表顺序按 index 升序（Center 取前两块作 net1/net2）。
 */
public class NetworkStats {

    /**
     * 网卡设备名。
     */
    private String name;

    /**
     * 发送速率(字节/秒)。
     */
    private long txRate;

    /**
     * 接收速率(字节/秒)。
     */
    private long rxRate;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getTxRate() {
        return txRate;
    }

    public void setTxRate(long txRate) {
        this.txRate = txRate;
    }

    public long getRxRate() {
        return rxRate;
    }

    public void setRxRate(long rxRate) {
        this.rxRate = rxRate;
    }

}
