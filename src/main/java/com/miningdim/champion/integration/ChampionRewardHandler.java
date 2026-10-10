package com.miningdim.champion.integration;

import com.miningdim.champion.ChampionConfig;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.champion.reward.ChampionReward;
import com.miningdim.champion.reward.ContributionPool;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.champion.reward.DamageContribution;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyServices;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 精英怪贡献池奖励接线 (Champions 集成层; ChampionStarAffix spec 第十一章奖励与经济闸 + 第十四章实现拆分 8)。
 *
 * 记账 (方案 D1: 统一记"本次实际扣掉的血", 毛伤另存只供诊断):
 *  (1) 6★+ 血池冠军: {@link ChampionBloodPoolHandler} 算出净伤后调 {@link #recordBloodPoolHit}, 记
 *      min(净伤, 扣血前影子血), 致死分支也记。血池 handler 会取消原版伤害, 本类不监听 LivingHurtEvent。
 *      记账统一记本次实际扣掉的血 (净伤): 整次免疫、封顶削掉的部分与溢出伤害不计入门槛和份额; 毛伤另存只供诊断。
 *  (2) 其余冠军 (1-5★ 与未建池者): {@link #onChampionDamage} 在 {@link LivingDamageEvent} (LOWEST, 原版护甲与
 *      吸收之后、扣血之前) 记 min(amount, 扣血前血量)。
 * 两条路径互斥 (血池在册的冠军原版伤害已被取消, 不会走到 LivingDamageEvent; 本类另以血池在册做显式短路)。
 *
 * 结算 {@link LivingDeathEvent}: 读冠军 capability 上的账本 (随 NBT 持久, 重启不清零, 方案 D2) ->
 * {@link ContributionPool#distribute} (在线 + 近期命中 + 盖章双门槛, 分母为全部净伤, 作废份额不回池, 最大余数法)
 * -> 逐合格玩家经 {@code EconomyServices.economyService().grantDaily} 并入 credit_faucet 信用点衰减主闸 (60000 档,
 * 不自开印钞口); 6★+ 另按同一口径把 {@link ChampionReward#azureDrop} 当作青辉石固定总池瓜分, 逐合格玩家经
 * {@code grantAzureDaily(player, share, AZURE_DAILY_FAUCET_CAP)} 并入每人每日青辉石产出硬上限 (F099)。
 * 结算后清空该冠军账本, 防同一实体被重复派发死亡事件时二次发奖; 特勤侧 {@code AgentRewardHandler} 挂 HIGHEST
 * 先于本 handler 只读同一份账本, 先后由优先级决定。
 *
 * 冠军判定经自研 {@link MiningChampions} capability: 1-10★ 全星级冠军均发奖, 普通怪 capability star=0 直接放行。
 * 支援召唤物 (spec 红线 8-a) 既不记账也不结算。
 */
public final class ChampionRewardHandler {

    /** 诊断日志: 奖励结算真服首验用 (冠军死亡打一行 星级/固定池/净伤与毛伤合计/瓜分额; 死亡低频不门控)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/reward");

    /**
     * 无血池冠军受击记账 (1-5★ 与未建池者): 原版护甲/吸收已结算, amount 即将从血量里扣, 截断到扣血前血量
     * 防溢出伤害计入份额。被更高优先级取消的伤害不扣血, 故不收已取消事件。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChampionDamage(LivingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) {
            return;
        }
        if (BloodPoolRegistry.has(victim.getUUID())) {
            return; // 6★+ 血池冠军由 ChampionBloodPoolHandler 记账 (单一记账点)。
        }
        float amount = event.getAmount();
        if (!(amount > 0.0F)) {
            return;
        }
        double netDamage = Math.min(amount, Math.max(0.0F, victim.getHealth()));
        recordHit(victim, event.getSource(), amount, netDamage);
    }

    /**
     * 6★+ 血池冠军记账 (由 {@link ChampionBloodPoolHandler} 在净伤算出、扣影子血之前调用)。
     *
     * @param victim      冠军实体
     * @param source      伤害来源 (归因到玩家)
     * @param grossDamage 本次入伤名义值 (词条减伤前, 仅诊断)
     * @param netDamage   词条净减伤与 FLAT 削顶之后的净伤
     * @param hpBefore    扣血前影子血 (致死击按此截断, 溢出不计)
     */
    static void recordBloodPoolHit(LivingEntity victim, DamageSource source,
                                   double grossDamage, double netDamage, double hpBefore) {
        recordHit(victim, source, grossDamage, Math.min(netDamage, Math.max(0.0D, hpBefore)));
    }

    /** 归因到玩家 + 排除非本工程冠军/支援召唤物后写入 capability 账本。召唤物/非玩家来源不计。 */
    private static void recordHit(LivingEntity victim, DamageSource source, double grossDamage, double netDamage) {
        ServerPlayer attacker = resolvePlayerAttacker(source);
        if (attacker == null) {
            return;
        }
        MiningChampionData champ = MiningChampions.get(victim).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 受击者非本工程盖章的冠军 (star=0): 不计 (其它来源冠军不归本奖励池)。
        }
        if (champ.isSummonedByAffix()) {
            return; // 支援召唤物 (spec 红线 8-a 经济闸): 不记贡献不参与奖池 (打召唤物白刷贡献)。
        }
        ContributionTracker.record(champ, attacker.getUUID(), grossDamage, netDamage, victim.level().getGameTime());
    }

    /**
     * 冠军死亡结算: 在线 + 近期命中 + 盖章双门槛 -> 按净伤占比瓜分固定池 -> grantDaily 并入主闸 + 6★+ 青辉石。
     * 非本工程冠军 / 召唤物 / 无贡献 / 无合格者 直接跳过 (整池不发, 防按人头复制)。
     */
    @SubscribeEvent
    public void onChampionDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        MiningChampionData champ = MiningChampions.get(victim).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return;
        }
        if (champ.isSummonedByAffix()) {
            ContributionTracker.clear(champ); // 支援召唤物 (spec 红线 8-a 经济闸): 整池不发。
            return;
        }
        if (!ContributionTracker.hasLedger(champ)) {
            return; // 无人对其造成净伤: 无可结算。
        }
        if (!(victim.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        MinecraftServer server = serverLevel.getServer();

        int star = champ.star();
        double bossEffectiveHp = champ.effectiveHp();
        long nowTick = serverLevel.getGameTime();
        long recencyTicks = ChampionConfig.contributionRecencyTicks();

        // 取快照后立即清账 (online 现查: 玩家可能中途登出 = 离线没收)。特勤侧已在 HIGHEST 读过同一份。
        List<DamageContribution> contributions = ContributionTracker.snapshot(champ,
                playerId -> server.getPlayerList().getPlayer(playerId) != null);
        ContributionTracker.clear(champ);

        long fixedPoolRaw = ChampionReward.creditPoolRaw(star);
        Map<UUID, Long> payout = ContributionPool.distribute(contributions, bossEffectiveHp, fixedPoolRaw,
                nowTick, recencyTicks);

        // 诊断 (真服首验): 冠军死亡结算打一行 星级/有效血/固定池/贡献人数/净伤与毛伤合计/合格瓜分额。
        // 净伤合计 ≈ 有效血 (溢出已截断), 毛伤/净伤之比即这只怪的实际减伤强度。
        double grossSum = 0.0D;
        for (DamageContribution c : contributions) {
            grossSum += c.grossDamage();
        }
        LOGGER.info("champion-death {} star{} effHp={} pool={} contributors={} net={} gross={} payout={}",
                victim.getType().getDescriptionId(), star, bossEffectiveHp, fixedPoolRaw, contributions.size(),
                String.format("%.1f", ContributionPool.totalEffectiveDamage(contributions)),
                String.format("%.1f", grossSum), payout.values());

        if (payout.isEmpty()) {
            return; // 无合格者: 整池不发 (防蹭枪/按人头复制)。
        }

        // 经济门面未就绪则不发 (启动早期; isRegistered 判, 不抛打断死亡)。
        if (!EconomyServices.isRegistered()) {
            return;
        }

        // 青辉石固定总池 (F099): azureDrop(star) 是本次击杀的青辉石总池 raw, 与信用点同一份贡献记录 + 同一资格
        // 判定 + 同一最大余数法瓜分 (严禁按人头复制)。
        boolean dropsAzure = ChampionReward.dropsAzure(star);
        long azurePoolRaw = dropsAzure ? ChampionReward.azureDrop(star) : 0L;
        Map<UUID, Long> azurePayout = azurePoolRaw > 0L
                ? ContributionPool.distribute(contributions, bossEffectiveHp, azurePoolRaw, nowTick, recencyTicks)
                : Map.of();

        for (Map.Entry<UUID, Long> entry : payout.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue; // 结算瞬间登出: 没收 (与 online 门槛一致)。
            }
            long raw = entry.getValue();
            if (raw > 0L) {
                // 信用点并入衰减主闸 (credit_faucet / 60000 档): 与矿工卖矿/农夫卖菜共享每人每日天花板。
                EconomyServices.economyService().grantDaily(player, raw,
                        EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY,
                        EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);
            }
            // 6★+ 青辉石 PvE 掉落: 与信用点同一份 payout 键集 (同一资格判定), 按权重取份额, 并入经济层每人每日
            // 产出硬上限 (grantAzureDaily, 经济文档 8.5 战斗 faucet 必须并入每人每日上限; economy-02 修复)。超当日
            // cap 部分被经济层截断丢弃, 返回值为实际入账量 (此处不二次用, 留作将来"撞上限提示"接线点)。
            long azureShare = azurePayout.getOrDefault(entry.getKey(), 0L);
            if (azureShare > 0L) {
                EconomyServices.economyService().grantAzureDaily(player, azureShare,
                        EconomyConstants.AZURE_DAILY_FAUCET_CAP);
            }
        }
    }

    /** 伤害来源归因到 ServerPlayer 攻击者 (直接伤害源 = 玩家; 召唤物/环境/非玩家弹射物 owner 不归因)。 */
    private static ServerPlayer resolvePlayerAttacker(DamageSource source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return player;
        }
        return null;
    }
}
