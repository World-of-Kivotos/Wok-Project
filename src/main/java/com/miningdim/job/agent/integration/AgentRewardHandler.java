package com.miningdim.job.agent.integration;

import com.miningdim.champion.reward.ChampionReward;
import com.miningdim.champion.reward.ContributionPool;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.champion.reward.DamageContribution;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyServices;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.agent.AgentClock;
import com.miningdim.job.agent.AgentEnhancedReward;
import com.miningdim.job.agent.AgentBountySavedData;
import com.miningdim.job.agent.AgentKillXp;
import com.miningdim.job.agent.AgentLevels;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import top.theillusivec4.champions.api.IChampion;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 特勤加强奖励 + 悬赏结算接线 (SpecialAgent_Job_DesignSpec 7.1 加强奖励 + 10.5 悬赏完成判定; Champions 集成层)。
 *
 * 结算职责拆分 (调研铁律: 复用精英怪贡献池按伤害分、不重造、加强奖励从池外给不挤占贡献占比):
 *  本 handler 挂 {@link EventPriority#HIGHEST}, 在已落地 {@code ChampionRewardHandler.onChampionDeath} (默认优先级)
 *  之前先 {@link ContributionTracker#drain} 取贡献并完成"贡献池瓜分主结算"(与 ChampionRewardHandler 同口径:
 *  盖章双门槛 + 加权瓜分固定池 -> grantDaily 并入 credit_faucet 主闸 + 6★+ 青辉石)。drain 后账本已空, 默认优先级
 *  的 ChampionRewardHandler.onChampionDeath 查 hasLedger=false 直接 no-op (不双发)。本 handler 由此成为"特勤在场时
 *  的精英死亡权威结算点", 既复用同一贡献池逻辑 (不重造) 又在同一结算里叠加特勤专属的加强奖励 + 悬赏推进, 无需
 *  对 ContributionTracker 加非破坏性 peek (那要改 champion 包, 违硬约束)。
 *
 * 特勤专属叠加 (在贡献池主结算之后, 仅对合格 + 是特勤职业的玩家):
 *  (1) 加强奖励 (7.1): 按精英初始星级 × 等级倍率 {@link AgentEnhancedReward#extraCreditRaw} 得每击杀额外信用点
 *      raw, 经 {@code grantDaily} 并入【同一】credit_faucet 主闸 (不另开印钞口; 与池内信用点共享每人每日天花板)。
 *      不产青辉石 (7.1)。"从池外给"= 这是池瓜分之外的个人 faucet, 不参与池的加权占比 (不挤占他人), 但仍受统一
 *      衰减主闸约束。
 *  (2) 悬赏推进 (10.5): 该击杀的 qualifiedKill = 是否达贡献池入池门槛 (合格者集合 contains; 封印不计贡献 -> 封了
 *      没打则不在合格集 -> qualifiedKill=false 不计数)。悬赏进度/完成发奖的逐玩家多槽实例持久化属 b 阶段面板接线,
 *      本任务交付 qualifiedKill 口径 + 周青辉石软上限门控持久层 {@link AgentBountySavedData}, 具体悬赏实例推进留
 *      deferred (见交付报告)。
 *
 * compileOnly 隔离: 本类 import top.theillusivec4.champions.* —— 仅 ModList 守卫下经 {@link AgentIntegrationBootstrap}
 * 挂 forgeBus。dev 不加载, 真死亡结算须正式服验。
 */
public final class AgentRewardHandler {

    /**
     * 精英死亡结算 (HIGHEST: 抢在 ChampionRewardHandler 默认优先级 drain 前接管): 贡献池瓜分主结算 + 特勤加强奖励
     * 叠加。非本工程精英 / 无贡献 / 无合格者 跳过 (整池不发)。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onChampionDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        UUID championId = victim.getUUID();
        if (!ContributionTracker.hasLedger(championId)) {
            return; // 无人造成有效伤害 (或非本工程精英): 无可结算。
        }

        IChampion champion = AgentChampionData.championOf(victim);
        if (champion == null || !AgentChampionData.isOurChampion(champion)) {
            ContributionTracker.discard(championId); // 防泄漏。
            return;
        }
        if (!(victim.level() instanceof ServerLevel serverLevel)) {
            ContributionTracker.discard(championId);
            return;
        }
        MinecraftServer server = serverLevel.getServer();

        int star = AgentChampionData.starOf(champion);
        double bossEffectiveHp = AgentChampionData.effectiveHpOf(champion);
        if (star < 1 || bossEffectiveHp <= 0.0D) {
            ContributionTracker.discard(championId);
            return; // 盖章数据缺失: 不发, 丢账本防脏发。
        }

        // drain 贡献 (接管主结算): online 现查 (玩家可能中途登出 = 离线没收)。
        List<DamageContribution> contributions = ContributionTracker.drain(championId,
                playerId -> server.getPlayerList().getPlayer(playerId) != null);

        long fixedPoolRaw = ChampionReward.creditPoolRaw(star);
        Map<UUID, Long> payout = ContributionPool.distribute(contributions, bossEffectiveHp, fixedPoolRaw);
        if (payout.isEmpty()) {
            return; // 无合格者: 整池不发 (防蹭枪/按人头复制)。
        }
        if (!EconomyServices.isRegistered()) {
            return; // 经济门面未就绪 (启动早期): 不发, 不抛打断死亡。
        }

        boolean dropsAzure = ChampionReward.dropsAzure(star);
        long azurePoolDrop = ChampionReward.azureDrop(star);

        // (A) 贡献池主结算 (与 ChampionRewardHandler 同口径: 信用点并入主闸 + 6★+ 青辉石; drain 已接管故由本 handler 发)。
        for (Map.Entry<UUID, Long> entry : payout.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue; // 结算瞬间登出: 没收。
            }
            long raw = entry.getValue();
            if (raw > 0L) {
                EconomyServices.economyService().grantDaily(player, raw,
                        EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY,
                        EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);
            }
            if (dropsAzure && azurePoolDrop > 0L) {
                EconomyServices.economyService().grant(player, Currency.AZURE, azurePoolDrop);
            }
        }

        // (B) 特勤专属叠加 (仅合格者; 池外个人 faucet)。合格者集合即对该精英造成有效伤害的玩家 (qualifiedKill 口径):
        // 封印不计贡献 -> 封了没打的怪不在合格集 -> 自然不享。两笔特勤福利:
        //  - 加强奖励 (7.1): 按初始星级×等级倍率额外信用点, 并入信用点衰减主闸 (不产青辉石);
        //  - 经验 faucet (8.1): 按 星级×60×贡献占比 给原始经验, 走经验软上限 (与信用点同口径反推占比, 不碰信用点闸)。
        // fixedPoolRaw (= 该星固定信用点总池) 即占比反推分母 (payout = pool × 占比), 传给经验入账复用同一口径。
        for (Map.Entry<UUID, Long> entry : payout.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            // 入职标志门只查一次 SavedData: 两笔特勤福利同一资格门 (做过特勤工作才享)。
            if (!AgentBountySavedData.get(player.server.overworld()).isActiveAgent(player.getUUID())) {
                continue;
            }
            grantAgentKillBonus(player, star);
            grantAgentKillXp(player, star, entry.getValue(), fixedPoolRaw);
        }
    }

    /**
     * 给一名合格的特勤玩家发加强奖励 (7.1): 按精英初始星级 × 该玩家干员等级倍率得额外信用点 raw, 经 grantDaily
     * 并入【同一】credit_faucet 主闸。
     *
     * 入职标志门 (修复福利泄漏 Major): 加强奖励是【特勤专属】额外奖励 (原有贡献池奖励已对所有合格击杀者照发, 见
     * (A) 主结算), 仅对【做过特勤工作】的玩家叠发。严禁用 AGENT 等级作门 —— 框架 IJobService.level 对任何玩家
     * (含从未玩过特勤者) 恒返 1 级默认, 用等级判会把额外奖励泄漏给全服每个打死精英的玩家。isActiveAgent 入职标志门
     * 已在调用方 (B) 循环统一前置 (两笔特勤福利同一资格门, 只查一次 SavedData), 本法不重复门控。
     *
     * 设计哲学符合: 加强奖励是 PVE 经济 faucet, 走主闸衰减不破每日天花板; 只 CREDIT 不含青辉石 (青辉石仅周常悬赏出)。
     */
    private void grantAgentKillBonus(ServerPlayer player, int star) {
        int level = JobServices.jobService().level(player, JobId.AGENT);
        long bonusRaw = AgentEnhancedReward.extraCreditRaw(level, star);
        if (bonusRaw <= 0L) {
            return; // 倍率/星级折算后不足 1: 不发 (grantDaily 对 <=0 会抛, 故此处短路)。
        }
        EconomyServices.economyService().grantDaily(player, bonusRaw,
                EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY,
                EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);
    }

    /**
     * 给一名合格的特勤玩家发击杀经验 (8.1 经验 faucet): 按 {@code 初始星级 × 60 × 贡献占比} 得原始经验 raw, 经
     * {@link AgentLevels#grantRawXp} -> IJobService.grantXp 并入职业框架经验软上限 (与信用点同口径反推占比, 但走
     * 经验软上限而非信用点衰减主闸)。
     *
     * 贡献占比反推 (不重算): 信用点 payout = 该星固定信用点总池 (creditPoolRaw) × 该玩家有效伤害占比, 故占比 =
     * payoutRaw / creditPoolRaw (与 (A) 主结算瓜分同一口径; 末名 round 余数吸收使其占比含整池兜底, 量级误差 &lt; 1
     * 信用点, 对经验影响可忽略)。占比夹 [0,1] 防末名兜底浮点越界。
     *
     * 入职标志门已在调用方 (B) 循环统一前置, 本法不重复门控。
     *
     * @param player        合格的特勤玩家
     * @param star          精英初始星级 (1-10)
     * @param payoutRaw     该玩家本次信用点瓜分所得 raw (占比反推分子)
     * @param creditPoolRaw 该星固定信用点总池 raw (占比反推分母; &gt;0)
     */
    private void grantAgentKillXp(ServerPlayer player, int star, long payoutRaw, long creditPoolRaw) {
        double share = (double) payoutRaw / (double) creditPoolRaw;
        if (share > 1.0D) {
            share = 1.0D; // 末名吸收 round 余数可能令占比微越 1: 夹住 (killXpRaw 对 >1 会抛)。
        }
        long xpRaw = AgentKillXp.killXpRaw(star, share);
        if (xpRaw <= 0L) {
            return; // 占比折算后不足 1 经验: 不发 (低星 + 极小占比)。
        }
        AgentLevels.grantRawXp(player, xpRaw);
    }

    /**
     * 周常悬赏发青辉石的统一出口 (10.5 + 7.2 + 缺口 A 周产软上限门控): 先经 {@link AgentBountySavedData#tryGrantWeeklyAzure}
     * 按 ISO 周戳门控本周已产量, 撞顶则只发剩余额度 (软上限语义); 实发量 &gt;0 才 grant(AZURE)。b 阶段悬赏完成发奖
     * 调本法 (本任务交付门控接线 + 出口, 具体悬赏实例触发留 deferred)。
     *
     * @param player 完成周常悬赏的特勤玩家
     * @param amount 悬赏定义的青辉石奖励量 (BountyDefinition.azureReward)
     * @return 本次经周产软上限门控后实发的青辉石量 (0 = 本周撞顶不发)
     */
    public static long grantWeeklyBountyAzure(ServerPlayer player, long amount) {
        if (amount <= 0L) {
            return 0L;
        }
        ServerLevel overworld = player.server.overworld();
        AgentBountySavedData data = AgentBountySavedData.get(overworld);
        long weekStamp = AgentClock.currentUtcWeekStamp();
        long grantable = data.tryGrantWeeklyAzure(player.getUUID(), amount, weekStamp);
        if (grantable <= 0L) {
            return 0L; // 本周青辉石已撞顶。
        }
        EconomyServices.economyService().grant(player, Currency.AZURE, grantable);
        return grantable;
    }
}
