package com.miningdim.achievement;

/** 成就存储边界的非受检异常; 由调用方 (钩子、领取、命令) 负责最终报告。 */
public final class AchievementStoreException extends RuntimeException {
    public AchievementStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
