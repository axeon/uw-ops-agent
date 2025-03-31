package uw.ops.agent.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;


/**
 * 用于作为返回给前端的vo类。
 * 支持链式调用，同时支持泛型。
 *
 * @author axeon
 */
public final class ResponseData<T> {


    /**
     * 成功状态值。
     */
    public static final String SUCCESS = "success";

    /**
     * 报警状态值。
     */
    public static final String WARN = "warn";

    /**
     * 错误状态值。
     */
    public static final String ERROR = "error";

    /**
     * 未知状态值
     */
    public static final String UNKNOWN = "unknown";


    /**
     * 响应时间
     */
    private long time;

    /**
     * 状态
     */
    private String state = UNKNOWN;

    /**
     * 信息。
     */
    private String msg;

    /**
     * 代码，可能代表错误代码。
     */
    private String code;

    /**
     * 返回数据
     */
    private T data;

    private ResponseData(String state, String code, String msg, T data) {
        this.time = System.currentTimeMillis();
        this.state = state;
        this.msg = msg;
        this.code = code;
        this.data = data;
    }

    public ResponseData() {
        super();
    }

    /**
     * 成功返回值。
     *
     * @return
     */
    public static <T> ResponseData<T> success() {
        return new ResponseData<T>(SUCCESS, null, null, null);
    }

    /**
     * 成功返回值。
     *
     * @return
     */
    public static <T> ResponseData<T> success(T t) {
        return new ResponseData<T>(SUCCESS, null, null, t);
    }

    /**
     * 成功返回值。
     *
     * @return
     */
    public static <T> ResponseData<T> success(T t, String msg) {
        return new ResponseData<T>(SUCCESS, null, msg, t);
    }

    /**
     * 成功返回值。
     *
     * @return
     */
    public static <T> ResponseData<T> success(T t, String code, String msg) {
        return new ResponseData<T>(SUCCESS, code, msg, t);
    }


    /**
     * 警告返回值。
     *
     * @return
     */
    public static <T> ResponseData<T> warn() {
        return new ResponseData<T>(WARN, null, null, null);
    }

    /**
     * 警告返回值。
     *
     * @return
     */
    public static <T> ResponseData<T> warn(T t) {
        return new ResponseData<T>(WARN, null, null, t);
    }

    /**
     * 警告返回值。
     *
     * @return
     */
    public static <T> ResponseData<T> warn(T t, String msg) {
        return new ResponseData<T>(WARN, null, msg, t);
    }

    /**
     * 警告返回值。
     *
     * @return
     */
    public static <T> ResponseData<T> warn(T t, String code, String msg) {
        return new ResponseData<T>(WARN, code, msg, t);
    }

    /**
     * 附带消息的失败返回值。
     *
     * @param msg
     * @return
     */
    public static <T> ResponseData<T> error(String msg) {
        return new ResponseData<T>(ERROR, null, msg, null);
    }

    /**
     * 附带代码，消息的失败返回值。
     * 此方法是为了防止没有T参数，而调用code()时候出错误提示。
     *
     * @param msg
     * @return
     */
    public static <T> ResponseData<T> error(String code, String msg) {
        return new ResponseData<T>(ERROR, code, msg, null);
    }


    /**
     * 附带消息的失败返回值。
     *
     * @param msg
     * @return
     */
    public static <T> ResponseData<T> error(T t, String msg) {
        return new ResponseData<T>(ERROR, null, msg, t);
    }

    /**
     * 附带消息的失败返回值。
     *
     * @return
     */
    public static <T> ResponseData<T> error(T t, String code, String msg) {
        return new ResponseData<T>(ERROR, code, msg, t);
    }

    /**
     * 是否成功。
     *
     * @return
     */
    @JsonIgnore
    public boolean isSuccess() {
        return SUCCESS.equals(this.state);
    }

    /**
     * 是否不成功。
     *
     * @return
     */
    @JsonIgnore
    public boolean isNotSuccess() {
        return !SUCCESS.equals(this.state);
    }

    /**
     * 是否有警报
     *
     * @return
     */
    @JsonIgnore
    public boolean isWarn() {
        return WARN.equals(this.state);
    }

    /**
     * 是否有错误
     *
     * @return
     */
    @JsonIgnore
    public boolean isError() {
        return ERROR.equals(this.state);
    }


    /**
     * 是否没有错误
     *
     * @return
     */
    @JsonIgnore
    public boolean isNotError() {
        return !ERROR.equals(this.state);
    }


    @Override
    public String toString() {
        return "ResponseData{" +
                "time=" + time +
                ", state='" + state + '\'' +
                ", msg='" + msg + '\'' +
                ", code=" + code +
                ", data=" + data +
                '}';
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }
}
