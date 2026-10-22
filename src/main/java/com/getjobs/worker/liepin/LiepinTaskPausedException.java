package com.getjobs.worker.liepin;

/** A protective pause ends the run and must not be handled as a filtered job. */
public final class LiepinTaskPausedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        SECURITY_VERIFICATION, PAGE_UNAVAILABLE, READ_FAILED,
        COMMUNICATION_UNCONFIRMED, RECORD_STATE_UNAVAILABLE
    }

    private final Reason reason;

    private LiepinTaskPausedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }

    public static LiepinTaskPausedException verificationRequired() {
        return new LiepinTaskPausedException(Reason.SECURITY_VERIFICATION,
                "已暂停：请在猎聘浏览器手动完成安全验证，再重新开始；程序不会代办验证");
    }

    public static LiepinTaskPausedException pageUnavailable() {
        return new LiepinTaskPausedException(Reason.PAGE_UNAVAILABLE,
                "已暂停：猎聘浏览器页面已关闭或不可用；请检查页面后手动重新开始。尚未确认是否触发安全验证");
    }

    public static LiepinTaskPausedException readFailed() {
        return readFailed(null);
    }

    public static LiepinTaskPausedException readFailed(RuntimeException failure) {
        String type = failure == null ? "" : "（异常类型：" + exceptionType(failure) + "）";
        // Never retain the original exception: Playwright messages may contain a URL or token.
        return new LiepinTaskPausedException(Reason.READ_FAILED,
                "已暂停：猎聘页面读取或等待失败" + type
                        + "；请检查页面后手动重新开始。尚未确认是否触发安全验证");
    }

    public static LiepinTaskPausedException communicationUnconfirmed(
            Long jobId, String stage, RuntimeException failure) {
        String type = failure == null ? "" : "（异常类型：" + exceptionType(failure) + "）";
        return new LiepinTaskPausedException(Reason.COMMUNICATION_UNCONFIRMED,
                "已暂停：猎聘岗位 " + jobId + " 在" + safeStage(stage)
                        + "阶段结果未确认" + type
                        + "；保留待确认记录，不计为成功、不自动重发。请手动核对该岗位的聊天记录");
    }

    public static LiepinTaskPausedException recordStateUnavailable(
            Long jobId, String stage, RuntimeException failure) {
        String type = failure == null ? "" : "（异常类型：" + exceptionType(failure) + "）";
        return new LiepinTaskPausedException(Reason.RECORD_STATE_UNAVAILABLE,
                "已暂停：猎聘岗位 " + jobId + " 的" + safeStage(stage)
                        + "未能完成" + type
                        + "；不再点击或重复发送，请核对本地记录与平台聊天状态");
    }

    private static String safeStage(String stage) {
        return switch (stage == null ? "" : stage) {
            case "点击沟通", "确认沟通", "预留记录", "确认记录" -> stage;
            default -> "沟通处理";
        };
    }

    static String exceptionType(RuntimeException failure) {
        String name = failure.getClass().getSimpleName();
        return name.isEmpty() ? failure.getClass().getName() : name;
    }
}
