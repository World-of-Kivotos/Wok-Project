package com.miningdim.job.miner;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;

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
 * LivingHurtEvent 判 source 身份精确区分陷阱伤与怪/枪/玩家 TNT 伤。当前 trap 子系统未提供该专属源, 动态陷阱一律用
 * 原版环境机制造伤 (落石 FALLING_BLOCK / 钟乳石 FALLING_STALACTITE / 砸落铁砧 FALLING_ANVIL / 岩浆 LAVA / 着火
 * IN_FIRE·ON_FIRE / 炽热地面 HOT_FLOOR / 非玩家爆炸 EXPLOSION)。第七章给出的降级路径: 专属源缺失时按这些
 * 环境伤类型集合识别陷阱伤 ({@link #isTrapSource}), 用矿脉抗性减伤兑现 "降级为生存缓冲" 的承诺。
 *
 * 红线 (守不漂战斗力): 降级集合刻意只含环境/陷阱型伤, 不含任何战斗向来源 —— 怪物近战/远程、枪械
 * (MOB_ATTACK / PLAYER_ATTACK / 弹射物) 不在集合内零减免; 爆炸只认 {@link DamageTypes#EXPLOSION}
 * (苦力怕/床/重生锚/陷阱 TNT), 排除 {@link DamageTypes#PLAYER_EXPLOSION} (玩家点燃的 TNT = 潜在 PvP),
 * 杜绝把玩家 TNT 战斗伤当陷阱伤软化破坏 PvP attrition。trap 子系统将来暴露专属源后, 在此追加
 * {@code source.is(TrapDamageSources.TRAP_TNT/...)} 身份比较收紧即可, 减伤接线无需再动 ({@link MinerSystem}
 * register 注册的 "矿脉抗性" 源已按 inMiningRegion + isTrapSource + 等级门控统一结算)。
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
     * 判定一笔伤害是否来自陷阱专属来源 (第七章降级路径)。
     *
     * trap 子系统专属 DamageSource 未落地前, 动态陷阱一律借原版环境伤机制造伤, 故按环境伤类型集合识别:
     * 落石/钟乳石/铁砧 (砸落型) + 岩浆/着火/炽热地面 (热源型) + 非玩家爆炸 (苦力怕/床/陷阱 TNT)。
     *
     * 红线: 集合刻意排除一切战斗来源 (近战/远程/枪械/弹射物) 与 {@link DamageTypes#PLAYER_EXPLOSION}
     * (玩家点燃的 TNT, 潜在 PvP), 不把战斗伤误当陷阱伤软化。trap 暴露专属源后在此追加
     * {@code source.is(TrapDamageSources.TRAP_TNT/...)} 身份比较收紧即可。
     */
    public static boolean isTrapSource(DamageSource source) {
        if (source == null) {
            return false;
        }
        return source.is(DamageTypes.FALLING_BLOCK)
                || source.is(DamageTypes.FALLING_STALACTITE)
                || source.is(DamageTypes.FALLING_ANVIL)
                || source.is(DamageTypes.LAVA)
                || source.is(DamageTypes.IN_FIRE)
                || source.is(DamageTypes.ON_FIRE)
                || source.is(DamageTypes.HOT_FLOOR)
                // 仅非玩家爆炸 (苦力怕/床/陷阱 TNT); PLAYER_EXPLOSION 排除以守 PvP attrition 红线。
                || source.is(DamageTypes.EXPLOSION);
    }
}
