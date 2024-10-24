package uw.ops.agent.constant;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 任务类型
 */
@JsonFormat(shape = JsonFormat.Shape.OBJECT)
public enum TaskType {

    CMD(0, "指令任务"),

    START(1, "启动任务"),

    STOP(2, "关闭任务");

    /**
     * 参数值
     */
    private final int value;

    /**
     * 参数信息。
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

    public static TaskType findByValue(int value) {
        for (TaskType e : TaskType.values()) {
            if (value == e.value) {
                return e;
            }
        }
        return null;
    }

}
