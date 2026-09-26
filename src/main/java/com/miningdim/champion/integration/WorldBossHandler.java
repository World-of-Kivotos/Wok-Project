package com.miningdim.champion.integration;

import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.WorldBoss;
import com.miningdim.champion.WorldBossBroadcast;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.champion.reward.DamageContribution;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 世界 BOSS 的离场接线 (ChampionStarAffix spec 第十章): 被玩家击倒时全服公告输出前三名, 其余离场只写日志。
 *
 * <p><b>读账本的时机</b>: 贡献账本归 {@link ChampionRewardHandler#onChampionDeath} 所有, 它在默认优先级 (NORMAL) 上
 * {@link ContributionTracker#drain} 清账。本类挂 {@link EventPriority#HIGH}, 早于清账, 而且只
 * {@link ContributionTracker#peek}, 从不 drain —— 多一个 drain 调用方, 就会有一份奖励静默不发 (见 peek 的注释)。
 * 不收已取消的死亡事件: 被更高优先级救下的 BOSS 没有死。
 *
 * <p><b>什么算"被玩家击倒"</b>: 死亡时账本里有玩家的伤害记录, 并且致死伤害不是无视无敌的那一类 ({@code /kill}、虚空;
 * 判据 {@link WorldBoss#isPlayerDefeat}, 与成就的击杀结算共用)。
 * 最后一下是燃烧、中毒这类没有攻击者的伤害也算 —— 输出是玩家打出来的。账本为空 (没有玩家打过它)、管理员 {@code /kill}
 * 或被直接移除 (discard, 不发死亡事件) 都不公告, 只写日志。
 */
public final class WorldBossHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/worldboss");

    /** 世界 BOSS 死亡: 被玩家击倒则全服公告输出排行, 否则只写日志。 */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onWorldBossDeath(LivingDeathEvent event) {
        LivingEntity boss = event.getEntity();
        if (!(boss.level() instanceof ServerLevel level)) {
            return;
        }
        MiningChampionData data = MiningChampions.get(boss).orElse(null);
        if (data == null || !data.isWorldBoss()) {
            return;
        }
        MinecraftServer server = level.getServer();
        List<DamageContribution> ledger = ContributionTracker.peek(boss.getUUID(),
                playerId -> server.getPlayerList().getPlayer(playerId) != null);
        DamageSource source = event.getSource();
        if (ledger.isEmpty() || !WorldBoss.isPlayerDefeat(source)) {
            LOGGER.info("world boss {} star{} died in {} at {} without a player defeat (damage={}, contributors={}),"
                            + " not announced", boss.getType().getDescriptionId(), data.star(),
                    level.dimension().location(), boss.blockPosition(), source.getMsgId(), ledger.size());
            return;
        }
        LOGGER.info("world boss {} star{} defeated in {} at {} by {} contributors",
                boss.getType().getDescriptionId(), data.star(), level.dimension().location(), boss.blockPosition(),
                ledger.size());
        WorldBossBroadcast.announceDefeat(server, boss, data, ledger);
    }

    /**
     * 世界 BOSS 被直接移除 (discard: 管理员或别的模组清实体, 不经死亡): 不公告, 只写日志。死亡 (KILLED) 已由
     * {@link #onWorldBossDeath} 处理; 区块卸载、换维度不是离场。
     */
    @SubscribeEvent
    public void onWorldBossRemoved(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof LivingEntity boss)
                || boss.getRemovalReason() != Entity.RemovalReason.DISCARDED || !WorldBoss.isWorldBoss(boss)) {
            return;
        }
        LOGGER.info("world boss {} discarded in {} at {} without dying, not announced",
                boss.getType().getDescriptionId(), boss.level().dimension().location(), boss.blockPosition());
    }
}
