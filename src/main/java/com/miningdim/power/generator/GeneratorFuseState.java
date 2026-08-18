package com.miningdim.power.generator;

/** 保险丝已安装、尚未安装或已因 SCRAM 消耗的持久状态。 */
public enum GeneratorFuseState {
    ABSENT,
    INSTALLED,
    TRIPPED
}
