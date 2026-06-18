package com.miningdim.job.chef;

import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import net.minecraft.server.level.ServerPlayer;

/**
 * 谁做谁得经验入账 (Chef_Job_DesignSpec 第七章; 仿工程师 producerUUID 堵代练)。
 *
 * 完成一道菜 -> 经验只记给操作小游戏的厨师 (操作者), 买来/别人做的菜不产经验 (做菜阶段一次性入账, 吃时不再
 * 给经验, 见 {@link ChefConsumeHandler} 不调本类)。按达成品质取原始经验 ({@link ChefConfig#rawXp}), 经共享
 * {@link com.miningdim.job.IJobService#grantXp} 入账 —— 框架统一做每日 UTC 衰减软上限 + 翻日 + 升级, 厨师
 * 不自实现衰减表 (地基铁律: 职业只发原始经验)。
 */
public final class ChefXpHandler {

    private ChefXpHandler() {
    }

    /**
     * 给操作厨师入账一道菜的经验 (按达成品质)。
     *
     * @param operator 操作小游戏并完成做菜的厨师 (谁做谁得)
     * @param achieved 达成品质档
     * @return 经衰减折算后实际入账的有效经验 (>=0; capability 未挂载返回 0)
     */
    public static long award(ServerPlayer operator, ChefQuality achieved) {
        long rawXp = ChefConfig.rawXp(achieved);
        return JobServices.jobService().grantXp(operator, JobId.CHEF, rawXp);
    }
}
