package com.miningdim.achievement.trigger;

import com.miningdim.champion.WorldBoss;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MobInstanceTag;
import com.miningdim.economy.EconomyServices;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * 击杀过滤的公共部分 (Achievement_System_DesignSpec 6.4)。精英怪击杀与枪械击杀的统计项、触发器都先过这一组判据,
 * 放在一处, 不各写一份。精英类另外的 isChampion / isSummonedByAffix 判据在 {@link ChampionKillHooks} 里。
 */
final class KillFilter {

    private KillFilter() {
    }

    /**
     * 被击杀者是否矿区实例的怪: 在矿区维度内, 且带 {@link MobInstanceTag}。后一条同时排除了 /mchampion 召唤的、
     * 刷怪笼刷出的怪 —— 它们都不经矿区实例的刷怪路径, 不会被打上标记。枪械击杀只认这一条 (计数口径是"矿区怪物")。
     */
    static boolean isInstanceMob(LivingEntity victim) {
        return victim instanceof Mob mob
                && victim.level().dimension().equals(MiningConstants.MINING_LEVEL)
                && MobInstanceTag.isTagged(mob);
    }

    /**
     * 这只怪是否计入精英击杀 (6.4): 矿区实例的怪 ({@link #isInstanceMob}), 或任意维度的世界 BOSS
     * ({@link WorldBoss#isWorldBoss})。世界 BOSS 由管理员命令 {@code /mchampion worldboss} 召唤, 不经矿区实例的刷怪路径、
     * 不带实例标记, 也可能落在任何维度; 普通 {@code /mchampion summon} 召唤的精英两条都不满足, 仍被排除。先查实例标记
     * (含一次维度比较), 再查世界 BOSS 标记 (一次 capability 读取)。
     */
    static boolean countsForChampionKills(LivingEntity mob) {
        return isInstanceMob(mob) || WorldBoss.isWorldBoss(mob);
    }

    /**
     * 玩家是否处于挂机冻结。只用来过滤计数类统计, 不过滤一次性成就 (6.4)。经济门面还没注入时 (开服早期) 按未冻结处理,
     * 与任务模块的同名判据一致。
     */
    static boolean isAfkFrozen(ServerPlayer player) {
        return EconomyServices.isRegistered() && EconomyServices.economyService().isAfkFrozen(player);
    }
}
