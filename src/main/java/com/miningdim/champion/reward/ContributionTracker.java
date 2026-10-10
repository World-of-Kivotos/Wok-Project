package com.miningdim.champion.reward;

import com.miningdim.champion.MiningChampionData;

import java.util.List;
import java.util.UUID;

/**
 * 贡献账本门面 (ChampionStarAffix spec 第十一章奖励与经济闸 + 方案 D2): 读写冠军 capability 上的
 * {@link ContributionLedger}, 不再持有任何静态表。
 *
 * 记账点 (方案 D1, 统一记"本次实际扣掉的血"):
 *  - 6★+ 血池冠军: {@code ChampionBloodPoolHandler} 算出净伤后, 在致死与非致死两个分支里各记一次
 *    min(净伤, 扣血前影子血);
 *  - 其余冠军 (1-5★ 及未建池者): {@code ChampionRewardHandler} 在 LivingDamageEvent (LOWEST) 记
 *    min(amount, 扣血前血量)。
 * 召唤物来源与支援召唤物本身都在记账点前排除, 不进本账。
 *
 * 结算 (死亡时) 读 {@link #snapshot}: 特勤侧 ({@code AgentRewardHandler}, HIGHEST) 与贡献池主结算
 * ({@code ChampionRewardHandler}, 默认优先级) 读同一份 capability。主结算发完奖后 {@link #clear} 该账本, 防同一
 * 实体被重复派发死亡事件时二次发奖; 特勤侧只读不清, 两者先后由事件优先级决定, 不依赖同优先级的注册顺序。
 */
public final class ContributionTracker {

    private ContributionTracker() {
    }

    /**
     * 累加一笔玩家对冠军的命中 (净伤 ≤0 的命中整笔不记)。
     *
     * @param champion    冠军 capability 数据
     * @param playerId    造成伤害的玩家 UUID
     * @param grossDamage 本次毛伤 (诊断)
     * @param netDamage   本次实际扣掉的血
     * @param nowTick     当前 gameTime
     */
    public static void record(MiningChampionData champion, UUID playerId,
                              double grossDamage, double netDamage, long nowTick) {
        if (champion == null || playerId == null) {
            throw new IllegalArgumentException("champion/playerId must not be null");
        }
        champion.contributions().record(playerId, grossDamage, netDamage, nowTick);
    }

    /**
     * 只读地取某冠军的全部贡献记录 (按首次命中 tick 升序, 同 tick 按 UUID)。online 由调用方现查注入。
     *
     * @return 贡献记录列表 (空表示无人造成过净伤)
     */
    public static List<DamageContribution> snapshot(MiningChampionData champion, OnlineResolver onlineResolver) {
        if (champion == null) {
            throw new IllegalArgumentException("champion must not be null");
        }
        return champion.contributions().snapshot(onlineResolver);
    }

    /** 某冠军是否已有贡献记录。 */
    public static boolean hasLedger(MiningChampionData champion) {
        return champion != null && !champion.contributions().isEmpty();
    }

    /** 清空某冠军账本 (仅贡献池主结算在发奖后调用; 其余消费者只读)。 */
    public static void clear(MiningChampionData champion) {
        if (champion != null) {
            champion.contributions().clear();
        }
    }

    /** 在线判定回调 (handler 注入, 解耦纯逻辑层对 server/playerList 的依赖)。 */
    @FunctionalInterface
    public interface OnlineResolver {
        boolean isOnline(UUID playerId);
    }
}
