package com.miningdim.reset;

/**
 * {@link ResetJob} 与真实世界 I/O 之间的接缝 (seam, 设计文档第十三章物理重生成)。ResetJob 只做纯状态机推进 +
 * 实例状态翻转; 一切"释放强加载票 / 清陷阱注册表 / 判区块是否卸载完 / flush 异步写 / 删区块存档"的原版侧动作
 * 全部经本接口下沉。
 *
 * 之所以抽接缝: 这些动作直达原版 ChunkMap / RegionFileStorage / PersistentEntitySectionManager 做文件级删除,
 * 只能在真服验证 (GameTest 进程无法可靠复现区块卸载/存档删除的时序)。把它们隔到接口后, 状态机本体 (阶段推进
 * 顺序 / AWAIT_UNLOAD 超时转 FAILED / PURGE 逐 chunk 删除 / 陷阱清理仍发生 / 完成后 READY) 可用记录型替身在
 * GameTest 里精确锁死, 生产实现 ({@link LiveResetChunkOps}) 走真 ChunkMap。
 *
 * 全部方法主线程调用 (D8)。
 */
interface ResetChunkOps {

    /**
     * UNLOAD: 释放该实例 region 的全部强加载票 —— 既包括 entry 的滑动窗口 ticket, 也包括生成期
     * {@code ForgeChunkManager} 的 region 强制块; 释放后区块进入原版卸载流程。幂等 (对未强制的块无副作用)。
     */
    void releaseTickets();

    /**
     * UNLOAD: 清 region 覆盖 chunk 的伪装陷阱注册表条目 (防旧陷阱身份在新地形上变幽灵), 返回清除条数。
     * region 即将换新布局, 重生的区块加载时由转换器重新登记新陷阱。
     */
    int clearTrapRegistry();

    /**
     * AWAIT_UNLOAD: region 的 16x16 个 chunk 是否已全部无任何 ChunkHolder (卸载完成)。
     * "无 holder"判据取 ChunkMap.updatingChunkMap (当前全部 holder 的权威超集), 而非 hasChunk/getChunkNow
     * (仅覆盖 FULL 级)。全部无 holder 才可安全删存档 —— 否则残留 holder 卸载时会把区块重新写回, 覆盖删除。
     */
    boolean allChunksUnloaded();

    /**
     * PURGE: flush 挂起的异步区块写 (地形 IOWorker + 实体存档), 令其后的删除是该 chunk 的最后一次写。
     * 删除前调用一次 (排干卸载时触发的存档写), 删除后再调用一次 (把删除落盘)。
     */
    void flushPendingWrites();

    /**
     * PURGE: 删除单个 chunk 的存档数据 —— 地形 (ChunkMap 写 null -> RegionFile.clear 释放扇区) 与实体
     * (EntityStorage 写空 ChunkEntities -> entities region 清空)。下次该 chunk 被加载时无存档 -> 经 minecraft:noise
     * 从头重生成。POI 存档不在此清 (见 LiveResetChunkOps 说明: 矿洞噪声地形无 worldgen POI, 且 POI 查询按真实
     * 方块自校验, 陈旧条目无害)。
     */
    void deleteChunk(int chunkX, int chunkZ);
}
