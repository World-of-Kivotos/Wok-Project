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

    // 婚姻指针 (结婚系统 spec 第九章): 未婚 marriageId=NO_MARRIAGE / spouseUUID=null。权威关系数据在
    // marriage.MarriageRegistry; 本两字段是玩家侧锚点 + 显名缓存, copyFrom 跨死亡/换维度保留 (婚姻不因死亡解除)。
    private long marriageId = NO_MARRIAGE;
    private java.util.UUID spouseUUID = null;

    /** 全职业进度 (第 2.3 节并入): EnumMap<JobId,JobProgress> 持有者, 按需懒建默认。 */
    private final JobData jobData = new JobData();

    /** WebUI 界面偏好 (W1 决策 D1): 不可变整份替换, 从未设置过即 DEFAULT。 */
    private UiPrefs uiPrefs = UiPrefs.DEFAULT;

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
    public long marriageId() {
        return marriageId;
    }

    @Override
    public void setMarriageId(long marriageId) {
        this.marriageId = marriageId;
    }

    @Override
    public java.util.UUID spouseUUID() {
        return spouseUUID;
    }

    @Override
    public void setSpouseUUID(java.util.UUID spouse) {
        this.spouseUUID = spouse;
    }

    @Override
    public void clearMiningState() {
        this.currentInstanceId = NO_INSTANCE;
        this.danger = 0.0f;
        this.spawnFreezeUntil = 0L;
    }

    @Override
    public UiPrefs uiPrefs() {
        return uiPrefs;
    }

    @Override
    public void setUiPrefs(UiPrefs prefs) {
        // null 会一路潜伏到 serializeNBT 才炸在存档保存时 (现场早已丢失), 在入口就断。
        this.uiPrefs = java.util.Objects.requireNonNull(prefs, "prefs");
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
        // 婚姻指针跨死亡/换维度保留 (spec 第九章: 婚姻不因死亡解除)。
        this.marriageId = other.marriageId;
        this.spouseUUID = other.spouseUUID;
        // 界面偏好跨死亡/换维度保留 (漏这行的症状是玩家一死主题和强调色就静默复位)。record 不可变, 直接共享引用。
        this.uiPrefs = other.uiPrefs;
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
    /** 婚姻指针 (spec 第九章): marriageId 哨兵 NO_MARRIAGE / spouseUUID 仅在有配偶时写。 */
    private static final String K_MARRIAGE_ID = "marriageId";
    private static final String K_SPOUSE_UUID = "spouseUUID";
    /** 全职业进度子标签 (第 2.3 节并入): JobData 自身遍历 EnumMap 的 CompoundTag 挂此键。 */
    private static final String K_JOBS = "jobs";
    /** WebUI 界面偏好子标签 (W1 决策 D1): 与 K_JOBS 同层, 内部四个键名见 UiPrefs。 */
    private static final String K_UI_PREFS = "uiPrefs";

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
        tag.putLong(K_MARRIAGE_ID, marriageId);
        // spouseUUID 仅在有配偶时写键 (未婚不落键, 加载缺键即 null, 与 NO_MARRIAGE 一致)。
        if (spouseUUID != null) {
            tag.putUUID(K_SPOUSE_UUID, spouseUUID);
        }
        tag.put(K_JOBS, jobData.serializeNBT());
        tag.put(K_UI_PREFS, uiPrefs.toNbt());
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
        // 缺键 (旧存档无婚姻指针) 回退未婚: marriageId=NO_MARRIAGE / spouseUUID=null (向后兼容)。
        this.marriageId = tag.contains(K_MARRIAGE_ID) ? tag.getLong(K_MARRIAGE_ID) : NO_MARRIAGE;
        this.spouseUUID = tag.hasUUID(K_SPOUSE_UUID) ? tag.getUUID(K_SPOUSE_UUID) : null;
        // 缺键 (旧存档无职业进度) 时 JobData.deserializeNBT 收空 tag, 各职业取用时懒建默认 (向后兼容)。
        this.jobData.deserializeNBT(tag.getCompound(K_JOBS));
        // 与上一行同一范式: getCompound 对"缺键"和"键在但类型不是 Compound"都返回空 tag, 空 tag 经 sanitized
        // 得到全默认偏好。子标签内逐字段的缺键/类型错/取值域外回退由 UiPrefs.sanitized 负责, 全程不抛 ——
        // 这条路径没有 Gateway 兜底, 抛出去就是玩家进不来。
        this.uiPrefs = UiPrefs.sanitized(tag.getCompound(K_UI_PREFS));
    }
}
