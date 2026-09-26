package com.miningdim.title.store;

/** 称号存储边界的非受检异常; 由命令、登录钩子或服务端启动负责最终报告。 */
public final class TitleStoreException extends RuntimeException {
    public TitleStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
