package uw.ops.agent.constant;

/**
 * 任务类型（执行器分发不依赖本枚举，以 taskData.execType 为唯一依据）。
 */
public enum TaskType {

    CMD(0, "指令任务"),

    START(1, "启动任务"),

    STOP(2, "关闭任务");

    /**
     * 类型码。
     */
    private final int value;

    /**
     * 中文名（展示用）。
     */
    private final String label;

    TaskType(int value, String label) {
        this.value = value;
        this.label = label;
    }

    public int getValue() {
        return value;
    }

    public String getLabel() {
        return label;
    }

    /**
     * @return 匹配的TaskType，未找到时返回null
     */
    public static TaskType findByValue(int value) {
        for (TaskType e : TaskType.values()) {
            if (value == e.value) {
                return e;
            }
        }
        return null;
    }

}
