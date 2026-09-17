package uw.ops.agent.helper.vo;

/**
 * 网卡边界计数快照（差分输入，非上报 VO）：累计收发字节（两点差 ÷ 间隔秒 = 窗口平均速率），
 * 由 SystemInfoHelper.buildNetworkCounters() 同刻一次采集。
 */
public class NetIfCounters {

    /**
     * 累计接收字节数。
     */
    public final long rxBytes;

    /**
     * 累计发送字节数。
     */
    public final long txBytes;

    /**
     * @param rxBytes 累计接收字节数
     * @param txBytes 累计发送字节数
     */
    public NetIfCounters(long rxBytes, long txBytes) {
        this.rxBytes = rxBytes;
        this.txBytes = txBytes;
    }
}
