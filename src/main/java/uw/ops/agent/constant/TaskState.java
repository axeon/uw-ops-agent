package uw.ops.agent.constant;

import com.fasterxml.jackson.annotation.JsonFormat;


/**
 * 容器状态
 */
@JsonFormat(shape = JsonFormat.Shape.OBJECT)
public enum TaskState {

    FAIlED(-1, "已失败"),

    INIT(1,"初始化"),

    STARTING(2, "执行中"),

    SUCCEED(3, "已完成");


    /**
     * 参数值
     */
    private final int value;

    /**
     * 参数信息。
     */
    private final String label;

    TaskState(int value, String label) {
        this.value = value;
        this.label = label;
    }

    public int getValue() {
        return value;
    }

    public String getLabel() {
        return label;
    }
}
