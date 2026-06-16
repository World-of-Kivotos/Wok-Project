package com.miningdim.entry;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;

/**
 * 玩家级矿山数据门面 (设计文档 3.3 IMiningPlayerData / 12.5 第二层 Capability)。承载 "进入矿山前的
 * 回退状态 + 当前实例 + danger", 用于死亡 / 换维度 / 断线重连恢复 (14.6)。
 *
 * core 契约层未定义玩家 Capability 接口 (core 不可改); 本接口由 entry 子系统 (Capability 的拥有者) 提供,
 * entry 写回退态/currentInstanceId/spawnFreeze, 第十章 MobPressureSystem 经同一接口读写 danger 字段 ——
 * 双方只依赖本接口, 不 import 实现类 {@link MiningPlayerData}, 维持子系统解耦 (模块化铁律 2)。
 *
 * 字段语义与复制规则见 12.5 表; 持久化经 {@link net.minecraftforge.common.util.INBTSerializable}。
 */
public interface IMiningPlayerData {

    /** 不在任何矿山实例的哨兵值 (12.5 currentInstanceId)。 */
    long NO_INSTANCE = -1L;

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
}
