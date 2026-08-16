package com.miningdim.job.agent.integration;

import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.reward.ChampionReward;
import com.miningdim.champion.reward.ContributionPool;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.champion.reward.DamageContribution;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 特勤加强奖励 + 悬赏结算接线 (SpecialAgent_Job_DesignSpec 7.1 加强奖励 + 10.5 悬赏完成判定; Champions 集成层)。
 *
 * 结算职责拆分 (调研铁律: 复用精英怪贡献池按伤害分、不重造、加强奖励从池外给不挤占贡献占比):
 *  <b>贡献池主结算不归本 handler</b> —— 它归 {@code ChampionRewardHandler.onChampionDeath} (默认优先级), 那是账本
 *  的唯一所有者与唯一 {@link ContributionTracker#drain} 调用方。本 handler 挂 {@link EventPriority#HIGHEST} 只是为了
 *  在账本被清空之前读到它, 用的是非破坏性的 {@link ContributionTracker#peek}: 取同一份贡献 + 跑同一个
 *  {@code ContributionPool.distribute}, 只为得出"谁合格、各自占比多少", 据此叠加特勤专属的那两笔, 一分钱的池内
 *  奖励都不发。
 *
 *  这里曾经反过来 —— 本 handler 抢先 drain 并接管主结算, 让 ChampionRewardHandler 查 hasLedger=false 空转。那个
 *  形态要求两边的前置判据逐条同步, 实际做不到, 已连踩两次: F112 漏抄 {@code isSummonedByAffix} 让召唤物变成印钞口;
 *  F099 把青辉石从"每人一份"改成"按权重瓜分总池"后没抄过来, 于是修复在生产里从未执行, 青辉石一直按人头复制发。
 *  改成 peek + 单一所有者之后, 两边判据即便漂移, 最坏后果也只是特勤加成多发/少发一笔。
 *
 * 特勤专属叠加 (仅对合格 + 是特勤职业的玩家):
 *  (1) 加强奖励 (7.1): 按精英初始星级 × 等级倍率 {@link AgentEnhancedReward#extraCreditRaw} 得每击杀额外信用点
 *      raw, 经 {@code grantDaily} 并入【同一】credit_faucet 主闸 (不另开印钞口; 与池内信用点共享每人每日天花板)。
 *      不产青辉石 (7.1)。"从池外给"= 这是池瓜分之外的个人 faucet, 不参与池的加权占比 (不挤占他人), 但仍受统一
 *      衰减主闸约束。
 *  (2) 悬赏推进 (10.5): 该击杀的 qualifiedKill = 是否达贡献池入池门槛 (合格者集合 contains; 封印不计贡献 -> 封了
 *      没打则不在合格集 -> qualifiedKill=false 不计数)。悬赏进度/完成发奖的逐玩家多槽实例持久化属 b 阶段面板接线,
 *      本任务交付 qualifiedKill 口径 + 周青辉石软上限门控持久层 {@link AgentBountySavedData}, 具体悬赏实例推进留
 *      deferred (见交付报告)。
 *
 * 探测源已改自研 {@link MiningChampions#get}, 不再触任何 top.theillusivec4.champions.*, 由 {@link AgentIntegrationBootstrap}
 * 挂 forgeBus。
 *
 * 【醒目约束】本 handler 无条件生效 (探测源已自研, 不依赖 Champions 加载与否), 且<b>永远不得调用
 * {@link ContributionTracker#drain} 或 {@code discard}</b>。账本只有一个所有者 —— 贡献池主结算。多一个 drain 调用方,
 * 症状就是"某个奖励静默不发", 且取决于 Forge 同优先级下的注册先后, 没人能稳定推理。
 */
public final class AgentRewardHandler {

    /** 诊断日志: 只记特勤侧自己发出的那两笔 (经验 / 加强信用点); 贡献池主结算的诊断行归 ChampionRewardHandler。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/agent/reward");

    /**
     * 精英死亡时叠加特勤专属奖励 (HIGHEST: 早于主结算, 但<b>只读账本不清账</b>)。
     * 非本工程精英 / 支援召唤物 / 无贡献 / 无合格者 一律跳过。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onChampionDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        UUID championId = victim.getUUID();
        if (!ContributionTracker.hasLedger(championId)) {
            return; // 无人造成有效伤害 (或非本工程精英): 无可结算。
        }

        MiningChampionData champ = MiningChampions.get(victim).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return;
        }
        // 本 handler 的判据只管"这名玩家该不该拿特勤加成", 不再兼管整池发不发 —— 后者归主结算, 它有自己的同名
        // 判据。两边即便将来漂移, 最坏后果也只是特勤加成多发/少发一笔, 不会再像 F112/F099 那样把主结算整条带偏。
        if (champ.isSummonedByAffix()) {
            return;
        }
        if (!(victim.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        MinecraftServer server = serverLevel.getServer();

        int star = champ.star();
        double bossEffectiveHp = champ.effectiveHp();
        if (star < 1 || bossEffectiveHp <= 0.0D) {
            return; // 盖章数据缺失: 不发。账本留给主结算按它自己的判据处置。
        }

        // peek 而非 drain: 账本所有权归 ChampionRewardHandler 的贡献池主结算 (见 ContributionTracker.peek 注释)。
        // online 现查 (玩家可能中途登出 = 离线没收), 与主结算同口径。
        List<DamageContribution> contributions = ContributionTracker.peek(championId,
                playerId -> server.getPlayerList().getPlayer(playerId) != null);

        // 复算一次分配只为拿到"谁合格 + 各自占比", 不用于发钱 —— 发钱是主结算的事。同一份 contributions +
        // 同一个 distribute 保证两边的合格者集合逐字一致。
        long fixedPoolRaw = ChampionReward.creditPoolRaw(star);
        Map<UUID, Long> payout = ContributionPool.distribute(contributions, bossEffectiveHp, fixedPoolRaw);

        if (payout.isEmpty()) {
            return; // 无合格者 (防蹭枪)。
        }
        if (!EconomyServices.isRegistered()) {
            return; // 经济门面未就绪 (启动早期): 不发, 不抛打断死亡。
        }

        // 特勤专属叠加 (仅合格者; 池外个人 faucet)。合格者集合即对该精英造成有效伤害的玩家 (qualifiedKill 口径):
        // 封印不计贡献 -> 封了没打的怪不在合格集 -> 自然不享。
        // F016 服务端一半修法 (双重死锁): 经验入账原本被下方 isActiveAgent 门与加强信用点共用一道门, 而经验是唯一
        // 能让玩家升到 L3、进而封印、进而拿到入职标志的通路, 形成"没入职->没经验->升不了级->封不了->进不了职"死锁。
        // 拆开两道口: 经验对全体合格击杀者无条件照发 (与 MinerSystem.java:273 挖矿即给经验、FarmerSystem.java:188
        // 收菜即给经验同口径, 也正是设计文档 8.1 把"击杀精英"列为 XP 来源的原意); 加强信用点与伤害放大这两笔真福利
        // 继续只给做过特勤活计的人 (isActiveAgent)。经验不算福利泄漏: 它只是职业曲线, 不产货币, 且走职业框架经验
        // 软上限, 与"泄漏信用点/伤害放大"性质不同。
        // fixedPoolRaw (= 该星固定信用点总池) 是占比反推分母 (payout = pool × 占比), 传给经验入账复用同一口径。
        int xpGranted = 0;
        int bonusGranted = 0;
        for (Map.Entry<UUID, Long> entry : payout.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            grantAgentKillXp(player, star, entry.getValue(), fixedPoolRaw);
            xpGranted++;
            if (AgentBountySavedData.get(player.server.overworld()).isActiveAgent(player.getUUID())) {
                grantAgentKillBonus(player, star);
                bonusGranted++;
            }
        }

        // 诊断 (真服首验): 只记特勤侧自己做的那两笔。刻意不再照抄 ChampionRewardHandler 的同名 champion-death 行
        // —— 主结算已经归它, 两边打同样的字段只会让日志里出现两行看似矛盾的重复记录。
        LOGGER.info("agent-bonus champion={} star{} qualified={} xp={} bonus={}",
                victim.getType().getDescriptionId(), star, payout.size(), xpGranted, bonusGranted);
    }

    /**
     * 给一名合格的特勤玩家发加强奖励 (7.1): 按精英初始星级 × 该玩家干员等级倍率得额外信用点 raw, 经 grantDaily
     * 并入【同一】credit_faucet 主闸。
     *
     * 入职标志门 (修复福利泄漏 Major, F016 之后仍保留): 加强奖励是【特勤专属】额外货币奖励 (原有贡献池奖励已对
     * 所有合格击杀者照发, 见 (A) 主结算), 仅对【做过特勤工作】的玩家叠发。严禁用 AGENT 等级作门 —— 框架
     * IJobService.level 对任何玩家 (含从未玩过特勤者) 恒返 1 级默认, 用等级判会把额外货币奖励泄漏给全服每个打死
     * 精英的玩家。isActiveAgent 门已在调用方 (B) 循环前置 (只对加强奖励这一笔货币福利生效; F016 之后经验 faucet
     * 已拆到无条件照发, 不再共用此门), 本法不重复门控。
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
     * F016 服务端一半修法: 本法【不】查入职标志门, 对全体合格击杀者 (对该精英造成有效伤害者) 无条件照发 —— 经验
     * 是唯一能让玩家升到 L3 (SEAL_UNLOCK_LEVEL)、进而封印、进而拿到入职标志的通路, 若继续共用 isActiveAgent 门
     * 会形成"没入职->没经验->升不了级->封不了->进不了职"的死锁。经验不算福利泄漏: 它只是职业曲线产出, 不产货币,
     * 且走职业框架经验软上限约束, 与"泄漏信用点/伤害放大"两笔真货币/战力福利性质不同 (与 MinerSystem.java:273
     * 挖矿即给经验、FarmerSystem.java:188 收菜即给经验同口径)。
     *
     * @param player        合格的击杀者 (不要求入职标志)
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
     * 按 ISO 周戳门控本周已产量, 撞顶则只发剩余额度 (软上限语义); 周门控放行的 grantable 再经 {@code grantAzureDaily}
     * 并入【与精英怪掉落共享的】每人每日青辉石产出硬上限 (azure_faucet 键, 日+周双轴): 周 cap 防本悬赏路单独超发, 日
     * cap 防"周常悬赏 + 精英怪掉落"两路当日合计绕过日上限印钞。日 cap 截断后的实发量为最终入账量。b 阶段悬赏完成发奖
     * 调本法 (本任务交付门控接线 + 出口, 具体悬赏实例触发留 deferred)。
     *
     * @param player 完成周常悬赏的特勤玩家
     * @param amount 悬赏定义的青辉石奖励量 (BountyDefinition.azureReward)
     * @return 本次经周产软上限 + 每日产出硬上限双轴门控后实发的青辉石量 (0 = 本周或当日撞顶不发)
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
        return EconomyServices.economyService().grantAzureDaily(player, grantable,
                EconomyConstants.AZURE_DAILY_FAUCET_CAP);
    }
}
