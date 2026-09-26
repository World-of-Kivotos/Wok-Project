package com.miningdim.achievement.trigger;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.reward.ContributionPool;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.champion.reward.DamageContribution;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MobInstanceTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 精英怪击杀的事件钩子 (Achievement_System_DesignSpec 6.1 champion_kills、6.2 champion_kill、6.4 击杀过滤)。
 *
 * <p><b>读贡献账本的时机</b>: 账本归精英怪模块的贡献池主结算 {@code ChampionRewardHandler.onChampionDeath} 所有, 它在默认
 * 优先级 (NORMAL) 上 {@link ContributionTracker#drain} 清账; 特勤奖励 {@code AgentRewardHandler} 在 HIGHEST 上只 peek。
 * 本类挂 {@link EventPriority#HIGH}: 晚于 HIGHEST 上可能取消死亡的处理, 早于 NORMAL 的清账, 而且只用
 * {@link ContributionTracker#peek}, 永远不 drain、不 discard —— 多一个 drain 调用方, 症状就是某份奖励静默不发。
 *
 * <p><b>过滤</b> (6.4): 死在矿区维度、带实例标记、是精英、不是词条召唤物; 统计只给在线的有效贡献者
 * ({@link ContributionPool#isQualified}, 离线即不合格), 挂机冻结的贡献者不加计数类统计, 但一次性成就照常判定。
 *
 * <p><b>独自击杀</b> (6.4 solo): 伤害账本里只有这一名玩家、他的记录伤害不少于精英的有效血量, 且这只精英攻击过的玩家里
 * 没有别人。"攻击过"由本类在 {@link LivingHurtEvent} 上记录 (HIGHEST、收已取消的事件: 被减伤或免疫取消的一下也是攻击过)。
 * 精英一次没出手就被打死也算独自击杀 —— 这条判据要排除的是"有人帮忙拉仇恨"。
 */
public final class ChampionKillHooks {

    /** 攻击记录表的上限。精英死亡时摘掉自己那一条; 没死就消失的 (自然消失、区块卸载) 靠上限按最久未更新淘汰。 */
    private static final int ATTACK_LOG_CAPACITY = 1024;

    /** 精英 UUID -> 它在矿区攻击过的玩家。访问序, 只在主线程读写。 */
    private static final Map<UUID, Set<UUID>> ATTACKED_PLAYERS = new LinkedHashMap<UUID, Set<UUID>>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Set<UUID>> eldest) {
            return size() > ATTACK_LOG_CAPACITY;
        }
    };

    /** 精英在矿区里打了玩家: 记进攻击记录。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onChampionAttack(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)
                || !victim.level().dimension().equals(MiningConstants.MINING_LEVEL)) {
            return;
        }
        if (event.getSource().getEntity() instanceof Mob attacker && MobInstanceTag.isTagged(attacker)
                && MiningChampions.isChampion(attacker)) {
            ATTACKED_PLAYERS.computeIfAbsent(attacker.getUUID(), id -> new HashSet<>()).add(victim.getUUID());
        }
    }

    /** 精英死亡: 读账本 (peek), 给在线的有效贡献者加统计、触发 champion_kill。优先级见类注释。 */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onChampionDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob victim) || !(victim.level() instanceof ServerLevel level)
                || !level.dimension().equals(MiningConstants.MINING_LEVEL)) {
            return;
        }
        Set<UUID> attacked = ATTACKED_PLAYERS.remove(victim.getUUID());
        if (!KillFilter.isInstanceMob(victim)) {
            return;
        }
        MiningChampionData champion = MiningChampions.get(victim).orElse(null);
        if (champion == null || !champion.isChampion() || champion.isSummonedByAffix()) {
            return;
        }
        settle(level.getServer(), victim, champion, attacked == null ? Set.of() : attacked, KillFilter::isAfkFrozen);
    }

    /**
     * 结算一只已通过过滤的精英的击杀。挂机判据由调用方给出 (事件路径传 {@link KillFilter#isAfkFrozen}), GameTest 可以换成
     * 固定答案, 不必替换全服的经济门面。
     */
    static void settle(MinecraftServer server, LivingEntity victim, MiningChampionData champion,
                       Set<UUID> attackedPlayers, Predicate<ServerPlayer> afkFrozen) {
        double effectiveHp = champion.effectiveHp();
        if (!(effectiveHp > 0.0D)) {
            return; // 盖章数据缺失: 合格门槛无从算起, 宁可不发。
        }
        List<DamageContribution> ledger = ContributionTracker.peek(victim.getUUID(),
                playerId -> server.getPlayerList().getPlayer(playerId) != null);
        if (ledger.isEmpty()) {
            return;
        }
        double teamAverage = ContributionPool.teamAverageEffectiveDamage(ledger);
        long deathTick = victim.level().getGameTime();
        Set<AffixDef> affixes = champion.affixes().keySet();
        for (DamageContribution contribution : ledger) {
            if (!ContributionPool.isQualified(contribution, effectiveHp, teamAverage)) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(contribution.playerId());
            if (player == null) {
                continue;
            }
            if (!afkFrozen.test(player)) {
                AchievementStats.award(player, AchievementStats.CHAMPION_KILLS, 1);
            }
            AchievementTriggers.CHAMPION_KILL.trigger(player, killView(contribution, ledger, champion.star(), affixes,
                    effectiveHp, attackedPlayers, deathTick));
        }
    }

    /**
     * 某名贡献者眼中的这次击杀 (供 champion_kill 判定)。
     *
     * @param self            该贡献者在账本里的记录
     * @param ledger          精英的全部贡献记录 (含不合格者与离线者)
     * @param effectiveHp     精英的有效血量 (独自击杀的伤害门槛)
     * @param attackedPlayers 这只精英攻击过的玩家
     * @param deathTick       死亡时刻, 与账本的首伤 tick 同一个时钟 (精英所在维度的 gameTime)
     */
    static ChampionKill killView(DamageContribution self, List<DamageContribution> ledger, int star,
                                 Set<AffixDef> affixes, double effectiveHp, Set<UUID> attackedPlayers,
                                 long deathTick) {
        double total = 0.0D;
        long firstHit = Long.MAX_VALUE;
        for (DamageContribution contribution : ledger) {
            total += contribution.effectiveDamage();
            firstHit = Math.min(firstHit, contribution.firstHitTick());
        }
        double share = total > 0.0D ? self.effectiveDamage() / total : 0.0D;
        boolean solo = ledger.size() == 1 && self.effectiveDamage() >= effectiveHp
                && attackedPlayers.stream().allMatch(self.playerId()::equals);
        long fightTicks = deathTick >= firstHit ? deathTick - firstHit : -1L;
        return new ChampionKill(star, affixes, share, solo, fightTicks);
    }

    /** 停服时清空攻击记录。 */
    public static void reset() {
        ATTACKED_PLAYERS.clear();
    }
}
