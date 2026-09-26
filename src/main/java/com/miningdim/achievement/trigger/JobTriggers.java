package com.miningdim.achievement.trigger;

import com.miningdim.achievement.AchievementIds;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.PlayerTrigger;

import java.util.List;

/**
 * 6.2 里 P2 职业各行的自定义触发器: 唯一实例与注册入口, 与 P1 各行的 {@link AchievementTriggers} 并列。
 *
 * 钩子一律经这里的静态实例触发 (见 {@link JobHooks})。与 P1 同一纪律: 触发器一律注册, 各 trigger 方法里只做轻量比较、
 * 不查库 (第十章)。{@code munitions_batch} 没有条件字段, 直接复用原版的 {@link PlayerTrigger} 形态。
 */
public final class JobTriggers {

    public static final JobLevelTrigger JOB_LEVEL = new JobLevelTrigger();
    public static final ChefDishTrigger CHEF_DISH = new ChefDishTrigger();
    public static final BrewCompleteTrigger BREW_COMPLETE = new BrewCompleteTrigger();
    public static final TarotPlayTrigger TAROT_PLAY = new TarotPlayTrigger();
    public static final AgentSealTrigger AGENT_SEAL = new AgentSealTrigger();
    public static final NanoPlateProducedTrigger NANO_PLATE_PRODUCED = new NanoPlateProducedTrigger();
    /** 在军火台完成一批弹药生产 (被动结算与手动批次两个经验落账点都算)。 */
    public static final PlayerTrigger MUNITIONS_BATCH = new PlayerTrigger(AchievementIds.id("munitions_batch"));

    private static final List<CriterionTrigger<?>> ALL = List.of(JOB_LEVEL, CHEF_DISH, BREW_COMPLETE, TAROT_PLAY,
            AGENT_SEAL, NANO_PLATE_PRODUCED, MUNITIONS_BATCH);

    private JobTriggers() {
    }

    /**
     * 登记进原版触发器表。与 {@link AchievementTriggers#register} 同样只能在 FMLCommonSetup 的 enqueueWork 里
     * (主线程、每个进程一次、早于任何数据包加载) 调用, 由 {@link JobHooks#register} 挂上。
     */
    static void register() {
        ALL.forEach(CriteriaTriggers::register);
    }

    /** 全部职业触发器。 */
    public static List<CriterionTrigger<?>> all() {
        return ALL;
    }
}
