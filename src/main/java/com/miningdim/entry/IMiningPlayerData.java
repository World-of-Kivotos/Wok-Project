package com.miningdim.entry;

import com.miningdim.job.JobId;
import com.miningdim.job.JobProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;

/**
 * 玩家级矿山数据门面 (设计文档 3.3 IMiningPlayerData / 12.5 第二层 Capability)。承载 "进入矿山前的
 * 回退状态 + 当前实例 + danger + 全职业进度", 用于死亡 / 换维度 / 断线重连恢复 (14.6)。
 *
 * core 契约层未定义玩家 Capability 接口 (core 不可改); 本接口由 entry 子系统 (Capability 的拥有者) 提供,
 * entry 写回退态/currentInstanceId/spawnFreeze, 第十章 MobPressureSystem 经同一接口读写 danger 字段 ——
 * 双方只依赖本接口, 不 import 实现类 {@link MiningPlayerData}, 维持子系统解耦 (模块化铁律 2)。
 *
 * 职业进度并入 (JobFramework_Shared_Foundation_DesignSpec 第 2.3 节 Critical 裁决): 全职业进度
 * {@code EnumMap<JobId,JobProgress>} 收敛进本唯一权威 capability, 不再另挂第二套玩家 capability。job
 * 子系统经 {@link MiningCapabilities#get(net.minecraft.world.entity.player.Player)} 取本接口后调
 * {@link #jobProgress(JobId)} 读写。{@link JobProgress}/{@code JobData}/{@code JobXpCurve} 仍是纯数据类,
 * 仅作 entry 的内部委派, 不引入对方 capability/事件实现。
 *
 * 字段语义与复制规则见 12.5 表; 持久化经 {@link net.minecraftforge.common.util.INBTSerializable}。
 */
public interface IMiningPlayerData {

    /** 不在任何矿山实例的哨兵值 (12.5 currentInstanceId)。 */
    long NO_INSTANCE = -1L;

    /** 未婚哨兵值 (结婚系统 spec 第九章 marriageId 指针; 同 NO_INSTANCE 范式)。 */
    long NO_MARRIAGE = -1L;

    // ---- 进入前回退现场 (14.2 步骤 2 snapshotFallback; 撤离/重连按此送回) ----

    /** 进入矿山前所在维度 (12.5 prevDimension); 未设过为 overworld。 */
    ResourceKey<Level> prevDimension();

    /** 进入矿山前坐标 (12.5 prevPos)。 */
    BlockPos prevPos();

    /** 进入矿山前游戏模式 (12.5 prevGameMode)。 */
    GameType prevGameMode();

    /** 记录进入前现场 (14.2 snapshotFallback): 维度 + 坐标 + 游戏模式三者同时写。 */
    void snapshotFallback(ResourceKey<Level> dimension, BlockPos pos, GameType gameMode);

    /** 回退现场是否有效 (曾 snapshot 过且坐标非占位)。无效时撤离降级到主世界 spawn (14.6)。 */
    boolean hasFallback();

    // ---- 当前实例 ----

    /** 当前所在实例 id; 不在矿山为 {@link #NO_INSTANCE} (12.5 currentInstanceId)。 */
    long currentInstanceId();

    /** 设置当前实例 id (enter 置实例 id; leave/撤离置 NO_INSTANCE)。 */
    void setCurrentInstanceId(long instanceId);

    // ---- danger (第十章 D7; entry 仅初始化, MobPressureSystem 评估) ----

    /** 当前危险值 (归一化 [0,1], 见第十章)。 */
    float danger();

    void setDanger(float danger);

    /**
     * 出生冻结截止 tick (设计文档 11.x / 1670 行): 进入后一段时间内 danger 钳制在低位, 避免落地即高压。
     * 第十章评估时 {@code if (gameTime < spawnFreezeUntil) danger = min(danger, 0.15)}。
     */
    long spawnFreezeUntil();

    void setSpawnFreezeUntil(long gameTime);

    /** /mining leave 或撤离时清空矿山相关运行态 (currentInstanceId=NO_INSTANCE, danger=0, spawnFreeze=0)。 */
    void clearMiningState();

    // ---- 婚姻指针 (结婚系统 spec 第九章: 并入唯一权威 capability, 不新挂 capability; Clone 复制保留) ----

    /**
     * 当前婚姻关系 id 指针; 未婚为 {@link #NO_MARRIAGE} (spec 第九章)。权威关系数据落
     * {@code com.miningdim.marriage.MarriageRegistry} (SavedData), 本指针只作玩家侧快速反查锚点。
     * 典礼写入 / 离婚清 {@link #NO_MARRIAGE}; {@link com.miningdim.entry.MiningCapabilities#onPlayerClone}
     * 跨死亡/换维度经 copyFrom 保留 (婚姻不因死亡解除)。
     */
    long marriageId();

    void setMarriageId(long marriageId);

    /**
     * 配偶 UUID; 未婚为 null (spec 第九章)。与 {@link #marriageId()} 同步写: 典礼写双方互指, 离婚清 null。
     * 仅作便利缓存 (戒指/HUD 显名免每次查 Registry); 权威成员仍以 MarriageRegistry 的 partnerA/B 为准。
     */
    java.util.UUID spouseUUID();

    void setSpouseUUID(java.util.UUID spouse);

    // ---- WebUI 界面偏好 (W1 决策 D1: 账号级偏好落 capability, 换机器/清浏览器缓存不丢) ----

    /**
     * 当前界面偏好 (永不返回 null; 从未设置过为 {@link UiPrefs#DEFAULT})。player.prefs.get 直接下发本值。
     * {@link com.miningdim.entry.MiningCapabilities#onPlayerClone} 跨死亡/换维度经 copyFrom 保留。
     */
    UiPrefs uiPrefs();

    /**
     * 整份覆盖界面偏好 (player.prefs.set 刻意不做部分更新: "给了 null" 与 "没给" 的三态语义会把
     * 清空某项与不动某项混在一起, 而前端本来就持有完整偏好状态, 整份提交是零成本)。
     * 传入实例的取值域已由 {@link UiPrefs} 规范构造器保证。
     */
    void setUiPrefs(UiPrefs prefs);

    // ---- 全职业进度 (第 2.3 节: 并入唯一权威 capability; 取代已删的 job.JobCapability) ----

    /**
     * 取某职业进度 (永不返回 null, 缺则懒建默认 level=1/xp=0)。grant/读级/读进度统一入口。
     * 职业子系统经 {@code MiningCapabilities.get(player).map(d -> d.jobProgress(job))} 读写, 不再走第二套
     * 玩家 capability (第 2.3 节)。
     */
    JobProgress jobProgress(JobId job);
}
