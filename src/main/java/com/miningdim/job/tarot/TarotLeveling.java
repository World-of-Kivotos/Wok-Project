package com.miningdim.job.tarot;

import com.miningdim.job.IJobService;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.tarot.TarotConfig;
import net.minecraft.server.level.ServerPlayer;

/**
 * 打牌经验结算 (TarotReader spec 9.1: 谁打谁得)。用牌者本人按品质入账原始经验, 框架统一做每日衰减/翻日/升级
 * (职业侧只发原始经验, 不自行折算 — {@link IJobService#grantXp} 契约)。开包/持有绝不给经验 (反代练)。
 *
 * 等级门控 (spec 9.4): {@link #canUseQuality} 校验玩家塔罗师等级是否够用某品质 (门控卡在用牌, 不卡持有)。
 *
 * 单牌原始经验从 {@link TarotConfig} 实时读 (C6 不硬编码)。
 */
public final class TarotLeveling {

    private TarotLeveling() {
    }

    /** 玩家当前塔罗师等级 (1-10; 未挂载 capability 返回 1)。 */
    public static int level(ServerPlayer player) {
        IJobService svc = JobServices.jobService();
        return svc.level(player, JobId.TAROT);
    }

    /** 等级门控 (spec 9.4): 该品质要求等级 <= 玩家等级才可用牌。 */
    public static boolean canUseQuality(ServerPlayer player, TarotQuality quality) {
        return level(player) >= quality.requiredLevel();
    }

    /**
     * 打出一张牌的经验结算 (spec 9.1: 谁打谁得)。按品质取原始经验经框架入账给打牌者本人。
     * @return 经衰减折算后实际入账的有效经验 (>=0)
     */
    public static long grantPlayXp(ServerPlayer player, TarotQuality quality) {
        long raw = rawXpFor(quality);
        return JobServices.jobService().grantXp(player, JobId.TAROT, raw);
    }

    /** 合成成功的小额额外经验 (spec 9.1)。 */
    public static long grantCraftXp(ServerPlayer player) {
        return JobServices.jobService().grantXp(player, JobId.TAROT, TarotConfig.XP_CRAFT_SUCCESS.get());
    }

    /** 某品质的单牌原始经验 (spec 9.1: R8/SR16/SSR32/UR60/闪耀120; 实时读 config)。 */
    public static long rawXpFor(TarotQuality quality) {
        return switch (quality) {
            case R -> TarotConfig.XP_R.get();
            case SR -> TarotConfig.XP_SR.get();
            case SSR -> TarotConfig.XP_SSR.get();
            case UR -> TarotConfig.XP_UR.get();
            case SHINY -> TarotConfig.XP_SHINY.get();
        };
    }
}
