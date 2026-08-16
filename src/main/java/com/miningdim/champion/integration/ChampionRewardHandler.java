package com.miningdim.champion.integration;

import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.reward.ChampionReward;
import com.miningdim.champion.reward.ContributionPool;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.champion.reward.DamageContribution;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyServices;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
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
 * 两个事件:
 *  (1) {@link LivingHurtEvent} (EventPriority.LOWEST + receiveCanceled): 玩家对冠军造成有效伤害时, 经
 *      {@link ContributionTracker#record} 累加 (championUUID -> playerUUID -> 累计有效伤害)。召唤物伤害不计
 *      (攻击者非真玩家直接排除)。挂 LOWEST 读易伤放大后的最终 event.getAmount(); receiveCanceled=true 关键:
 *      6★+ 血池 handler 同 LOWEST 会 setCanceled 取消本次伤害 (影子血权威), 若本 handler 不收已取消事件则
 *      6★+ 冠军的贡献全漏记。贡献口径是"玩家有效输出统计"(非扣血量), 故即使血池取消 vanilla 扣血也要记。
 *  (2) {@link LivingDeathEvent}: 冠军死亡时结算 —— drain 贡献 -> {@link ContributionPool#distribute} 盖章双门槛
 *      + 按有效伤害加权瓜分固定池 -> 逐合格玩家经 {@code EconomyServices.economyService().grantDaily} 并入
 *      credit_faucet 信用点衰减主闸 (60000 档, 不自开印钞口); 6★+ 另按同一双门槛 + 同一加权瓜分口径 (F099 修复,
 *      复用 {@link ContributionPool#distribute} 把 {@link ChampionReward#azureDrop} 当作青辉石固定总池) 分青辉石,
 *      逐合格玩家经 {@code grantAzureDaily(player, share, AZURE_DAILY_FAUCET_CAP)} 并入每人每日青辉石产出硬上限。
 *
 * 经济闸铁律 (spec 第十一章 + 经济文档 8.5): 贡献池战斗 faucet 必须并入每人每日上限, 故信用点一律走
 * grantDaily(player, raw, GLOBAL_DAILY_CREDIT_FAUCET_KEY, GLOBAL_DAILY_CREDIT_FAUCET_TIER) (与矿工卖矿/农夫卖菜
 * 共享同一信用点衰减主闸天花板); 青辉石一律走 grantAzureDaily(player, share, AZURE_DAILY_FAUCET_CAP) 并入每人每日
 * 青辉石产出硬上限 (azure_faucet 键, UTC 翻日; economy-02 修复, 此前直发 grant(AZURE) 无任何日上限是印钞口)。
 * 本类不自建 addCredit / addAzure 印钞口。
 *
 * F099 修复 (青辉石按人头复制发放, 与信用点池反人头复制口径相反): 复核结论 (Full_Repo_Audit_2026-08.md F099) 已
 * 推翻"可跨账号洗额度"的原判 —— {@code Currency.AZURE} 硬绑定玩家不可转移不可交易 (见 economy 包 Currency 类注释),
 * 塔罗卡/开箱皮肤两个出口也都强制盖玩家 UUID 归属, 小号刷到的青辉石烂在小号手里, 无法归集。但复核仍指出"打 47
 * 点伤害的蹭枪者与打 26000 的主力拿一样多青辉石"是真实的公平性/口径不一致 —— 与信用点侧 :119 走加权瓜分相反,
 * 本类 :85 注释自称"整池不发, 防按人头复制"对青辉石分支并不成立。按审计员给的建议 A (让青辉石与信用点走同一分配
 * 语义: 整只怪固定产出 N 颗按合格者权重取整瓜分), 把 {@link ChampionReward#azureDrop} 的产出量重新定义为"本次击杀
 * 青辉石固定总池" (而非每人一份的常量), 复用同一份 {@link ContributionPool#distribute} 按有效伤害权重瓜分, 不新增
 * 分配算法。
 *
 * 冠军判定经自研 {@link MiningChampions} capability: 1-10★ 全星级冠军均发奖 (非仅 6★+ 血池冠军), 故读
 * {@link MiningChampionData#star()}/{@link MiningChampionData#effectiveHp()}。普通怪 capability star=0 直接放行。
 */
public final class ChampionRewardHandler {

    /** 诊断日志: 奖励结算真服首验用 (冠军死亡打一行 星级/固定池/合格人数/瓜分额; 死亡低频不门控)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/reward");

    /**
     * 玩家对冠军造成有效伤害: 累加贡献 (championUUID -> playerUUID)。召唤物/非玩家来源不计。
     * 读最终伤害 (LOWEST), 排除已被血池取消的伤害? —— 血池 handler 取消 vanilla 伤害但 event.getAmount() 仍是
     * 净伤前的最终入伤名义值; 贡献按"有效伤害"口径用 event.getAmount() (玩家实际打出的有效输出), 与血池扣血
     * 的净减伤分离 (贡献是输出统计, 不是扣血量)。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onChampionHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        ServerPlayer attacker = resolvePlayerAttacker(event);
        if (attacker == null) {
            return; // 非玩家来源 (含召唤物/环境): 不计贡献。
        }
        MiningChampionData champ = MiningChampions.get(victim).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 受击者非本工程盖章的冠军 (star=0): 不计 (其它来源冠军不归本奖励池)。
        }
        if (champ.isSummonedByAffix()) {
            return; // 支援召唤物 (spec 红线 8-a 经济闸): 不记贡献不参与奖池 (打召唤物白刷贡献)。
        }

        double effectiveDamage = event.getAmount();
        if (effectiveDamage <= 0.0D) {
            return;
        }
        long nowTick = victim.level().getGameTime();
        ContributionTracker.record(victim.getUUID(), attacker.getUUID(), effectiveDamage, nowTick);
    }

    /**
     * 冠军死亡结算: 盖章双门槛 + 加权瓜分固定池 -> grantDaily 并入主闸 + 6★+ 青辉石。
     * 非本工程冠军 / 无贡献 / 无合格者 直接跳过 (整池不发, 防按人头复制)。
     */
    @SubscribeEvent
    public void onChampionDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        UUID championId = victim.getUUID();
        if (!ContributionTracker.hasLedger(championId)) {
            return; // 无人对其造成有效伤害 (或非本工程冠军): 无可结算。
        }

        MiningChampionData champ = MiningChampions.get(victim).orElse(null);
        if (champ == null || !champ.isChampion()) {
            ContributionTracker.discard(championId); // 防泄漏。
            return;
        }
        if (champ.isSummonedByAffix()) {
            ContributionTracker.discard(championId); // 支援召唤物 (spec 红线 8-a 经济闸): 整池不发。
            return;
        }

        if (!(victim.level() instanceof ServerLevel serverLevel)) {
            ContributionTracker.discard(championId);
            return;
        }
        MinecraftServer server = serverLevel.getServer();

        int star = champ.star();
        double bossEffectiveHp = champ.effectiveHp();

        // drain 贡献: online 现查 (玩家可能中途登出 = 离线没收)。
        List<DamageContribution> contributions = ContributionTracker.drain(championId,
                playerId -> server.getPlayerList().getPlayer(playerId) != null);

        long fixedPoolRaw = ChampionReward.creditPoolRaw(star);
        Map<UUID, Long> payout = ContributionPool.distribute(contributions, bossEffectiveHp, fixedPoolRaw);

        // 诊断 (真服首验): 冠军死亡结算打一行 星级/有效血/固定池/贡献人数/合格瓜分额 (死亡低频不门控)。
        LOGGER.info("champion-death {} star{} effHp={} pool={} contributors={} payout={}",
                victim.getType().getDescriptionId(), star, bossEffectiveHp, fixedPoolRaw,
                contributions.size(), payout.values());

        if (payout.isEmpty()) {
            return; // 无合格者: 整池不发 (防蹭枪/按人头复制)。
        }

        // 经济门面未就绪则不发 (启动早期; isRegistered 判, 不抛打断死亡)。
        if (!EconomyServices.isRegistered()) {
            return;
        }

        // 青辉石固定总池 (F099 修复): azureDrop(star) 不再是"每合格者一份"的常量, 而是本次击杀的青辉石总池 raw,
        // 复用信用点同一份贡献记录 + 同一双门槛, 按有效伤害权重瓜分 (与信用点分配语义完全对齐, 严禁按人头复制)。
        boolean dropsAzure = ChampionReward.dropsAzure(star);
        long azurePoolRaw = dropsAzure ? ChampionReward.azureDrop(star) : 0L;
        Map<UUID, Long> azurePayout = azurePoolRaw > 0L
                ? ContributionPool.distribute(contributions, bossEffectiveHp, azurePoolRaw)
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
            // 6★+ 青辉石 PvE 掉落: 与信用点同一份 payout 键集 (同一双门槛过滤), 按权重取份额, 并入经济层每人每日
            // 产出硬上限 (grantAzureDaily, 经济文档 8.5 战斗 faucet 必须并入每人每日上限; economy-02 修复)。超当日
            // cap 部分被经济层截断丢弃, 返回值为实际入账量 (此处不二次用, 留作将来"撞上限提示"接线点)。
            long azureShare = azurePayout.getOrDefault(entry.getKey(), 0L);
            if (azureShare > 0L) {
                EconomyServices.economyService().grantAzureDaily(player, azureShare,
                        EconomyConstants.AZURE_DAILY_FAUCET_CAP);
            }
        }
    }

    /**
     * 受击事件归因到 ServerPlayer 攻击者 (直接伤害源 = 玩家; 召唤物/弹射物的 owner 归因在 b 阶段细化)。
     * package-private: 6★+ 血池致死分支 ({@link ChampionBloodPoolHandler}) 在 kill 前补记最后一击贡献时复用本归因,
     * 避免在血池 handler 另造一套归因逻辑 (单一归因来源)。
     */
    static ServerPlayer resolvePlayerAttacker(LivingHurtEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer player) {
            return player;
        }
        return null;
    }
}
