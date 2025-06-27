package uw.ops.agent.vo.sub;

/**
 * 负载信息。
 */
public class LoadStats {

    /**
     * 启动时间。
     */
    private long uptime;

    /**
     * 1分钟负载
     */
    private double load1m;

    /**
     * 5分钟负载
     */
    private double load5m;

    /**
     * 15分钟负载
     */
    private double load15m;

    /**
     * 系统功率。
     */
    private long power;

    /**
     * 当前进程数
     */
    private int processCount;

    /**
     * 当前线程数
     */
    private int threadCount;

    public LoadStats() {
    }

    public LoadStats(long uptime, double load1m, double load5m, double load15m, long power, int processCount, int threadCount) {
        this.uptime = uptime;
        this.load1m = load1m;
        this.load5m = load5m;
        this.load15m = load15m;
        this.power = power;
        this.processCount = processCount;
        this.threadCount = threadCount;
    }

    public long getUptime() {
        return uptime;
    }

    public void setUptime(long uptime) {
        this.uptime = uptime;
    }

    public long getPower() {
        return power;
    }

    public void setPower(long power) {
        this.power = power;
    }

    public double getLoad1m() {
        return load1m;
    }

    public void setLoad1m(double load1m) {
        this.load1m = load1m;
    }

    public double getLoad5m() {
        return load5m;
    }

    public void setLoad5m(double load5m) {
        this.load5m = load5m;
    }

    public double getLoad15m() {
        return load15m;
    }

    public void setLoad15m(double load15m) {
        this.load15m = load15m;
    }

    public int getProcessCount() {
        return processCount;
    }

    public void setProcessCount(int processCount) {
        this.processCount = processCount;
    }

    public int getThreadCount() {
        return threadCount;
    }

    public void setThreadCount(int threadCount) {
        this.threadCount = threadCount;
    }
}
