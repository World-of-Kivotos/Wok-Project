package com.miningdim.core;

import net.minecraft.server.level.ServerPlayer;

/**
 * 网络门面 (设计文档第十五章, Forge SimpleChannel)。服务端权威 (C5/N1): 客户端只接收 S2C 展示包做渲染,
 * 不参与任何世界/危险计算。实现 (NetworkSystem) 在 FMLCommonSetupEvent 注册信道与包, 注入 MiningServices。
 *
 * 本 mod 自定义 S2C 包仅: DangerSyncS2C / TeleportResultS2C / InstanceStatusS2C (15.4)。
 * GUI 打开优先走原版 NetworkHooks.openScreen (15.5), 不自定义 OpenMiningGuiS2C 包。
 */
public interface IMiningNetwork {

    /** 传送结果码 (15.4.3 TeleportResultS2C.result)。 */
    enum TeleportResult {
        SUCCESS,
        QUEUED,
        REJECTED_FULL,
        REJECTED_GENERATING,
        ERROR
    }

    /** danger 视觉档 (15.4.2 tier:byte): 0 安全 / 1 警戒 / 2 高危。 */
    enum DangerTier {
        SAFE,
        ALERT,
        HIGH
    }

    /**
     * 下发 danger 同步包 (15.4.2 DangerSyncS2C)。客户端据此画 HUD 与屏幕压暗滤镜, 不自算。
     *
     * @param player          目标玩家
     * @param instanceId      所在实例 id
     * @param danger          当前危险值 (归一化, 量纲见 danger.max)
     * @param dangerMax       危险封顶
     * @param tier            视觉档
     * @param lightDimFactor  屏幕压暗系数 [0,1] (0 不压暗)
     */
    void sendDanger(ServerPlayer player, long instanceId, float danger, float dangerMax,
                    DangerTier tier, float lightDimFactor);

    /**
     * 便捷重载: 仅给 danger 值, dangerMax 取配置、tier/lightDimFactor 由实现按阈值推导。
     * 供压力系统主路径快速调用 (设计文档 3.3 sendDangerToClient 的归一化版本)。
     */
    void sendDanger(ServerPlayer player, float danger);

    /**
     * 下发传送结果包 (15.4.3 TeleportResultS2C)。
     *
     * @param player     目标玩家
     * @param result     结果码
     * @param instanceId 关联实例 id (无则传 -1)
     * @param queuePos   排队位次 (-1 = 不适用)
     * @param reasonKey  i18n key (用于客户端本地化提示)
     */
    void sendTeleportResult(ServerPlayer player, TeleportResult result, long instanceId,
                            int queuePos, String reasonKey);

    /**
     * 下发实例状态包 (15.4.4 InstanceStatusS2C): 生成进度/玩家数/region 盒, 供客户端列表/进度条。
     *
     * @param player     订阅该实例的玩家
     * @param instance   实例状态 (实现内部从中取 id/difficulty/genState/regionBox/playerCount)
     * @param genProgress 生成进度 [0,1]
     */
    void sendInstanceStatus(ServerPlayer player, InstanceState instance, float genProgress);

    /**
     * 为玩家打开矿山入口 GUI (15.5)。实现优先用 NetworkHooks.openScreen 走原版菜单同步,
     * 不自定义打开包。menuKind 区分要打开的界面种类 (如难度选择/实例列表), 由 NetworkSystem 映射 MenuProvider。
     */
    void openGui(ServerPlayer player, String menuKind);
}
