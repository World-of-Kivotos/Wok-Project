package com.miningdim.core;

/**
 * 实例分配超出全局上限 (instance.globalCap) 时抛出 (设计文档 12.2/12.3 背压)。
 * 非受检 (RuntimeException), 遵循 C9 自然冒泡: 业务层不吞, 仅入口层 (命令/网络 handler) 捕获转玩家提示。
 * reason 区分背压原因, 供入口层选择 i18n 文案。
 */
public final class InstanceLimitException extends RuntimeException {

    /** 背压原因 (12.3)。 */
    public enum Reason {
        /** 达到全局并发实例上限且 overflowPolicy=REJECT。 */
        GLOBAL_CAP,
        /** 排队超时 (queueTtlTicks)。 */
        QUEUE_TIMEOUT
    }

    private final Reason reason;

    public InstanceLimitException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
