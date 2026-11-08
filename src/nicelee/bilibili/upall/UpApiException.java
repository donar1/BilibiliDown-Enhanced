package nicelee.bilibili.upall;

/** 接口返回业务错误（code != 0），风控/412 单独标记便于上层退避重试。 */
public class UpApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public UpApiException(int code, String message) {
        super("bilibili api error, code=" + code + ", message=" + message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /** -352 风控校验失败、-412 请求被拦截、-799 请求过于频繁 */
    public boolean isRiskControl() {
        return code == -352 || code == -412 || code == -799 || code == -509;
    }
}
