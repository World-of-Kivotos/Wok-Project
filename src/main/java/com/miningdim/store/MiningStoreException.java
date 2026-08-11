package com.miningdim.store;

/**
 * 统一存储层故障。
 *
 * 刻意是非受检异常并且不在存储层内部被吞: 经济数据写入失败必须让调用方一路冒泡到最外层网关, 而不是
 * 退化成"这次没写成功但业务继续"。静默降级会让钱与资产悄悄不一致, 那正是本存储层要消灭的问题。
 */
public class MiningStoreException extends RuntimeException {

    public MiningStoreException(String message) {
        super(message);
    }

    public MiningStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
