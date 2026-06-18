package com.miningdim.job.miner;

import net.minecraft.world.damagesource.DamageSource;

/**
 * 生存类结算 (Miner_Job_DesignSpec 第七章, 守 "不漂战斗力" 红线)。本类只承载纯计算与来源判定:
 *  - 矿脉抗性减伤: 仅 "陷阱专属来源" 减伤 ({@link #reducedDamage}), 对怪/枪/玩家 TNT 零作用 (红线)。
 *  - 耐压时间项缩放: 由 {@link MinerSkills#dangerTimeFactor} 给系数, 实际注入 Danger.evaluate 属 pressure 子系统改动
 *    (见 foundationGaps), 本类不直接改 danger。
 *
 * 脱险归途 (读条 + 进入前 fallback 回退态送回, 见 MinerActions.executeEvacuate) 与声东击西 (spawnFreeze) 的执行编排在 {@link MinerActions} (需世界/服务端态);
 * 本类只放可单测的纯函数与来源判定, 与 MinerSkills 分工: MinerSkills 给数值, 本类给 "怎么用在伤害上"。
 *
 * 陷阱专属来源判定 (第七章): 理想路径是 trap 子系统暴露 {@code TrapDamageSources} 专属 DamageSource,
 * LivingHurtEvent 判 source 身份精确区分陷阱伤与怪/枪/玩家 TNT 伤。当前 trap 子系统未提供该专属源 (动态陷阱用原版
 * 机制造伤: 落石 FALLING_BLOCK / 岩浆 / 苦力怕爆炸, 跨子系统改动不在本任务可写范围, 见 foundationGaps); 故
 * {@link #isTrapSource} 现一律返回 false (红线安全降级: 宁可不减伤, 不可误把战斗伤当陷阱伤减), trap 暴露专属源后改为
 * 身份比较即自动生效。{@link MinerSystem#onLivingHurt} 的减伤接线已就绪 (按 isTrapSource 门控), 无需再改接线。
 */
public final class MinerSurvival {

    private MinerSurvival() {
    }

    /**
     * 对一笔伤害按矿工等级的矿脉抗性减伤后返回新伤害值 (仅当 source 为陷阱专属来源时减伤)。
     * 非陷阱来源原样返回 (红线: 对怪/枪/玩家 TNT 零作用)。减伤比封顶 35% (MinerSkills 内已钳)。
     *
     * @param level  矿工等级
     * @param source 伤害来源
     * @param amount 原始伤害
     * @return 减伤后伤害 (>= 0)
     */
    public static float reducedDamage(int level, DamageSource source, float amount) {
        if (!isTrapSource(source)) {
            return amount; // 非陷阱来源: 零减免。
        }
        double reduction = MinerSkills.trapDamageReduction(level);
        if (reduction <= 0.0D) {
            return amount;
        }
        float result = (float) (amount * (1.0D - reduction));
        return Math.max(0.0f, result);
    }

    /**
     * 判定一笔伤害是否来自陷阱专属来源。
     *
     * 当前实现 (保守, 待 trap 子系统 TrapDamageSources 落地后收紧): trap 子系统的动态陷阱用原版机制造成伤害
     * (苦力怕爆炸 / 岩浆接触 / 下落方块 / explosion), 无专属 DamageSource 可与自然来源区分。为不误把怪/枪/玩家
     * TNT 伤当陷阱伤 (会破红线变战斗减伤), 本判定在专属源缺失时 **一律返回 false** (零减伤优于误减伤)。
     * trap 暴露专属源后, 在此改为 {@code source.is(TrapDamageSources.TRAP_TNT/...)} 身份比较。
     */
    public static boolean isTrapSource(DamageSource source) {
        if (source == null) {
            return false;
        }
        // 红线优先: 专属源未落地时不臆测来源, 返回 false (宁可不减伤, 不可误减战斗伤)。
        // 占位识别钩子 (trap 专属源落地后替换): 此处不做任何前缀猜测以免误伤红线。
        return false;
    }
}
