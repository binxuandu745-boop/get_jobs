package com.getjobs.worker.boss;

/** A protective stop ends the entire run; it must not be treated as a filtered job. */
public final class BossTaskPausedException extends IllegalStateException {
    public enum Reason { SECURITY_VERIFICATION, JOB_DETAILS_UNAVAILABLE }

    private final Reason reason;

    private BossTaskPausedException(Reason reason, String message) {
        this(reason, message, null);
    }

    private BossTaskPausedException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }

    public static BossTaskPausedException verificationRequired() {
        return new BossTaskPausedException(Reason.SECURITY_VERIFICATION,
                "已暂停：请在 Boss 浏览器手动完成安全验证，再重新开始；程序不会代办验证");
    }

    public static BossTaskPausedException pageAccessRestricted() {
        return new BossTaskPausedException(Reason.SECURITY_VERIFICATION,
                "已暂停：页面截图识别到安全验证或访问限制提示，请手动检查 Boss 浏览器页面后再开始");
    }

    public static BossTaskPausedException detailsUnavailable(String detail) {
        return detailsUnavailable(detail, null);
    }

    public static BossTaskPausedException detailsUnavailable(String detail, Throwable cause) {
        return new BossTaskPausedException(Reason.JOB_DETAILS_UNAVAILABLE,
                "已暂停：" + detail + "；请检查浏览器页面，确认后再手动开始。尚未确认是否触发安全验证", cause);
    }

    public static BossTaskPausedException searchFiltersChanged() {
        return new BossTaskPausedException(Reason.JOB_DETAILS_UNAVAILABLE,
                "已暂停：本轮搜索筛选条件已改变，请重新开始；已停止继续处理旧列表，请先确认管理页配置");
    }

    public static BossTaskPausedException captureUnavailable(String detail) {
        return new BossTaskPausedException(Reason.JOB_DETAILS_UNAVAILABLE,
                "已暂停：自动截图未完成：" + detail + "；本轮未继续沟通，请检查页面后重新开始");
    }
}
