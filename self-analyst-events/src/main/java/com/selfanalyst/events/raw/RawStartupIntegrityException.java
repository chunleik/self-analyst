package com.selfanalyst.events.raw;

/** 不携带底层异常、路径或事件内容的启动完整性错误。 */
public final class RawStartupIntegrityException extends IllegalStateException {
    public RawStartupIntegrityException(String month) {
        super(month == null ? "原始分区 catalog 无法完成启动完整性检查，已阻止启动"
                : "原始分区 " + month + " 启动完整性检查失败，请检查隔离状态；原始文件保持不变");
    }
}
