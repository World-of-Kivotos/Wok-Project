package com.miningdim.district.store;

/** 自管区存储边界的非受检异常 (包 SQLException), 由平板层与命令层统一转成"数据库读写失败"。 */
public final class DistrictStoreException extends RuntimeException {

    public DistrictStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public DistrictStoreException(String message) {
        super(message);
    }
}
