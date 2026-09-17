package uw.ops.agent.vo.sub;

/**
 * 网卡窗口统计（每分钟 HostStats 上报，窗口平均速率）。
 * 静态属性（mac/ip/speed/index）见 {@link NetworkInfo}，不随本类传输；
 * 列表顺序按 index 升序（Center 取前两块作 net1/net2）。
 */
public class NetworkStats {

    /**
     * 网卡设备
     */
    private String name;

    /**
     * 发送速率
     */
    private long txRate;

    /**
     * 接收速率
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
