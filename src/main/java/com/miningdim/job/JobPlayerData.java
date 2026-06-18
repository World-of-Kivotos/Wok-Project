package com.miningdim.job;

import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.util.INBTSerializable;

/**
 * {@link IJobPlayerData} 的 capability 数据实现 (实现手册 "新 Capability" 范式)。纯数据对象, 无世界引用,
 * 序列化经 {@link INBTSerializable}。复制规则 (PlayerEvent.Clone) 由 {@link JobCapability} 处理。
 *
 * 内部委派 {@link JobData} 持有 EnumMap; 本类只是 capability 适配层 (serialize/deserialize/clone 转调)。
 *
 * 线程: capability 数据只在主线程读写 (玩家事件/tick/命令均主线程), 无需并发保护。
 */
public final class JobPlayerData implements IJobPlayerData, INBTSerializable<CompoundTag> {

    private final JobData data = new JobData();

    @Override
    public JobProgress jobProgress(JobId job) {
        return data.jobProgress(job);
    }

    @Override
    public JobData jobData() {
        return data;
    }

    /** 把另一份数据全字段拷入本对象 (PlayerEvent.Clone 复制)。 */
    public void copyFrom(JobPlayerData other) {
        this.data.copyFrom(other.data);
    }

    @Override
    public CompoundTag serializeNBT() {
        return data.serializeNBT();
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        data.deserializeNBT(tag);
    }
}
