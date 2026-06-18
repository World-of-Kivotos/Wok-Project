package com.miningdim.entry;

import com.miningdim.job.JobData;
import com.miningdim.job.JobId;
import com.miningdim.job.JobProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.INBTSerializable;

/**
 * {@link IMiningPlayerData} 的 Capability 数据实现 (设计文档 12.5)。纯数据对象, 无世界引用,
 * 序列化经 {@link INBTSerializable}。复制规则 (PlayerEvent.Clone) 由 {@link MiningCapabilities} 处理。
 *
 * 全职业进度 (第 2.3 节): 内持一个 {@link JobData} (EnumMap 持有者) 作为唯一权威职业存储, 序列化挂子标签
 * {@value #K_JOBS}, copyFrom 全量复制 (死亡/换维度保留全职业进度)。JobData/JobProgress 是纯数据类, 不带任何
 * 第二套 capability/事件 (已删 job.JobCapability)。
 *
 * 线程: Capability 数据只在主线程读写 (玩家事件 / tick 均主线程), 无需并发保护。
 */
public final class MiningPlayerData implements IMiningPlayerData, INBTSerializable<CompoundTag> {

    // 回退现场: 默认 overworld + 占位坐标, hasFallback 据 fallbackSet 判定真伪。
    private ResourceKey<Level> prevDimension = Level.OVERWORLD;
    private BlockPos prevPos = BlockPos.ZERO;
    private GameType prevGameMode = GameType.SURVIVAL;
    private boolean fallbackSet = false;

    private long currentInstanceId = NO_INSTANCE;
    private float danger = 0.0f;
    private long spawnFreezeUntil = 0L;

    /** 全职业进度 (第 2.3 节并入): EnumMap<JobId,JobProgress> 持有者, 按需懒建默认。 */
    private final JobData jobData = new JobData();

    @Override
    public ResourceKey<Level> prevDimension() {
        return prevDimension;
    }

    @Override
    public BlockPos prevPos() {
        return prevPos;
    }

    @Override
    public GameType prevGameMode() {
        return prevGameMode;
    }

    @Override
    public void snapshotFallback(ResourceKey<Level> dimension, BlockPos pos, GameType gameMode) {
        this.prevDimension = dimension;
        this.prevPos = pos.immutable();
        this.prevGameMode = gameMode;
        this.fallbackSet = true;
    }

    @Override
    public boolean hasFallback() {
        return fallbackSet;
    }

    @Override
    public long currentInstanceId() {
        return currentInstanceId;
    }

    @Override
    public void setCurrentInstanceId(long instanceId) {
        this.currentInstanceId = instanceId;
    }

    @Override
    public float danger() {
        return danger;
    }

    @Override
    public void setDanger(float danger) {
        this.danger = danger;
    }

    @Override
    public long spawnFreezeUntil() {
        return spawnFreezeUntil;
    }

    @Override
    public void setSpawnFreezeUntil(long gameTime) {
        this.spawnFreezeUntil = gameTime;
    }

    @Override
    public void clearMiningState() {
        this.currentInstanceId = NO_INSTANCE;
        this.danger = 0.0f;
        this.spawnFreezeUntil = 0L;
    }

    @Override
    public JobProgress jobProgress(JobId job) {
        return jobData.jobProgress(job);
    }

    /** 把另一份数据全字段拷入本对象 (PlayerEvent.Clone 复制: 死亡重生/换维度均保留回退态 + 全职业进度)。 */
    public void copyFrom(MiningPlayerData other) {
        this.prevDimension = other.prevDimension;
        this.prevPos = other.prevPos;
        this.prevGameMode = other.prevGameMode;
        this.fallbackSet = other.fallbackSet;
        this.currentInstanceId = other.currentInstanceId;
        this.danger = other.danger;
        this.spawnFreezeUntil = other.spawnFreezeUntil;
        this.jobData.copyFrom(other.jobData);
    }

    // ---- 持久化 (12.5) ----

    private static final String K_PREV_DIM = "prevDim";
    private static final String K_PREV_POS = "prevPos";
    private static final String K_PREV_GAMEMODE = "prevGameMode";
    private static final String K_FALLBACK_SET = "fallbackSet";
    private static final String K_CURRENT_INSTANCE = "currentInstanceId";
    private static final String K_DANGER = "danger";
    private static final String K_SPAWN_FREEZE = "spawnFreezeUntil";
    /** 全职业进度子标签 (第 2.3 节并入): JobData 自身遍历 EnumMap 的 CompoundTag 挂此键。 */
    private static final String K_JOBS = "jobs";

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putString(K_PREV_DIM, prevDimension.location().toString());
        tag.putLong(K_PREV_POS, prevPos.asLong());
        tag.putInt(K_PREV_GAMEMODE, prevGameMode.getId());
        tag.putBoolean(K_FALLBACK_SET, fallbackSet);
        tag.putLong(K_CURRENT_INSTANCE, currentInstanceId);
        tag.putFloat(K_DANGER, danger);
        tag.putLong(K_SPAWN_FREEZE, spawnFreezeUntil);
        tag.put(K_JOBS, jobData.serializeNBT());
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        if (tag.contains(K_PREV_DIM)) {
            ResourceLocation dimLoc = new ResourceLocation(tag.getString(K_PREV_DIM));
            this.prevDimension = ResourceKey.create(Registries.DIMENSION, dimLoc);
        }
        this.prevPos = BlockPos.of(tag.getLong(K_PREV_POS));
        this.prevGameMode = GameType.byId(tag.getInt(K_PREV_GAMEMODE));
        this.fallbackSet = tag.getBoolean(K_FALLBACK_SET);
        this.currentInstanceId = tag.contains(K_CURRENT_INSTANCE) ? tag.getLong(K_CURRENT_INSTANCE) : NO_INSTANCE;
        this.danger = tag.getFloat(K_DANGER);
        this.spawnFreezeUntil = tag.getLong(K_SPAWN_FREEZE);
        // 缺键 (旧存档无职业进度) 时 JobData.deserializeNBT 收空 tag, 各职业取用时懒建默认 (向后兼容)。
        this.jobData.deserializeNBT(tag.getCompound(K_JOBS));
    }
}
