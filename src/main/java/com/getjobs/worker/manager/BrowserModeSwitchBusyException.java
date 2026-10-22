package com.getjobs.worker.manager;

/** A mode change or task admission cannot interrupt another browser operation. */
public final class BrowserModeSwitchBusyException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public BrowserModeSwitchBusyException() {
        super("浏览器正在处理任务或切换模式，请停止投递并等待任务结束后再操作");
    }
}
