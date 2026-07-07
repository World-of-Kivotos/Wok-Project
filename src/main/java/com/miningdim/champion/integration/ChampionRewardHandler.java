package com.miningdim.champion.integration;

import com.miningdim.champion.reward.ChampionReward;
import com.miningdim.champion.reward.ContributionPool;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.champion.reward.DamageContribution;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyServices;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import top.theillusivec4.champions.api.IChampion;
import top.theillusivec4.champions.common.capability.ChampionCapability;

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
 *      credit_faucet 信用点衰减主闸 (60000 档, 不自开印钞口); 6★+ 另发青辉石经
 *      {@code grantAzureDaily(player, n, AZURE_DAILY_FAUCET_CAP)} 并入每人每日青辉石产出硬上限。
 *
 * 经济闸铁律 (spec 第十一章 + 经济文档 8.5): 贡献池战斗 faucet 必须并入每人每日上限, 故信用点一律走
 * grantDaily(player, raw, GLOBAL_DAILY_CREDIT_FAUCET_KEY, GLOBAL_DAILY_CREDIT_FAUCET_TIER) (与矿工卖矿/农夫卖菜
 * 共享同一信用点衰减主闸天花板); 青辉石一律走 grantAzureDaily(player, n, AZURE_DAILY_FAUCET_CAP) 并入每人每日青辉石
 * 产出硬上限 (azure_faucet 键, UTC 翻日; economy-02 修复, 此前直发 grant(AZURE) 无任何日上限是印钞口)。
 * 本类不自建 addCredit / addAzure 印钞口。
 *
 * 冠军判定经真 IChampion (任务要求): 1-10★ 全星级冠军均发奖 (非仅 6★+ 血池冠军), 故须 IChampion capability
 * 判定 + 读盖章 NBT (star/effectiveHp)。普通怪无 capability 直接放行。
 */
public final class ChampionRewardHandler {

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
        IChampion champion = ChampionCapability.getCapability(victim).orElse(null);
        if (champion == null || champion.getServer() == null) {
            return; // 受击者非冠军: 不计。
        }
        if (!isOurChampion(champion)) {
            return; // 非本工程盖章的冠军 (无 star NBT): 不计 (其它来源冠军不归本奖励池)。
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

        IChampion champion = ChampionCapability.getCapability(victim).orElse(null);
        if (champion == null || champion.getServer() == null || !isOurChampion(champion)) {
            ContributionTracker.discard(championId); // 防泄漏。
            return;
        }

        if (!(victim.level() instanceof ServerLevel serverLevel)) {
            ContributionTracker.discard(championId);
            return;
        }
        MinecraftServer server = serverLevel.getServer();

        CompoundTag data = champion.getServer().getData(ChampionPromoter.DATA_KEY);
        int star = data.getInt(ChampionPromoter.NBT_STAR);
        double bossEffectiveHp = data.getDouble(ChampionPromoter.NBT_EFFECTIVE_HP);
        if (star < 1 || bossEffectiveHp <= 0.0D) {
            ContributionTracker.discard(championId);
            return; // 盖章数据缺失 (异常): 不发, 不掩盖 (丢账本防脏发)。
        }

        // drain 贡献: online 现查 (玩家可能中途登出 = 离线没收)。
        List<DamageContribution> contributions = ContributionTracker.drain(championId,
                playerId -> server.getPlayerList().getPlayer(playerId) != null);

        long fixedPoolRaw = ChampionReward.creditPoolRaw(star);
        Map<UUID, Long> payout = ContributionPool.distribute(contributions, bossEffectiveHp, fixedPoolRaw);

        if (payout.isEmpty()) {
            return; // 无合格者: 整池不发 (防蹭枪/按人头复制)。
        }

        // 经济门面未就绪则不发 (启动早期; isRegistered 判, 不抛打断死亡)。
        if (!EconomyServices.isRegistered()) {
            return;
        }

        boolean dropsAzure = ChampionReward.dropsAzure(star);
        long azureAmount = ChampionReward.azureDrop(star);

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
            // 6★+ 青辉石 PvE 掉落: 并入经济层每人每日产出硬上限 (grantAzureDaily, 经济文档 8.5 战斗 faucet 必须并入
            // 每人每日上限; economy-02 修复)。超当日 cap 部分被经济层截断丢弃, 返回值为实际入账量 (此处不二次用, 留作
            // 将来"撞上限提示"接线点)。
            if (dropsAzure && azureAmount > 0L) {
                EconomyServices.economyService().grantAzureDaily(player, azureAmount,
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

    /** 是否本工程盖章的冠军 (有 miningdim_champion/star NBT)。 */
    private static boolean isOurChampion(IChampion champion) {
        CompoundTag data = champion.getServer().getData(ChampionPromoter.DATA_KEY);
        return data != null && data.contains(ChampionPromoter.NBT_STAR);
    }
}
