package com.miningdim.social;

/**
 * 社交业务拒绝（玩家可预期，不是系统故障）。错误码在 Web UI 回执中原样透出，前端按码展示提示。
 * 不分配堆栈，避免高频请求 / 重放把日志打爆。
 */
public final class SocialException extends RuntimeException {

    private final String errorCode;

    public SocialException(String errorCode, String message) {
        super(message, null, false, false);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
