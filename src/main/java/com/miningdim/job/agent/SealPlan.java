package com.miningdim.job.agent;

/**
 * 封印计划纯逻辑 (SpecialAgent_Job_DesignSpec 六章封印支线): 给定干员等级 + 目标精英星级 + 词条类别, 裁决
 * 是否可封印 + 产出窗口时长 + CD。纯函数, 不碰实体; 真封印执行 (IChampion.getServer().setAffixes 临时移除 +
 * 到期恢复) 落在集成层 (compileOnly, ModList 守卫)。
 *
 * 校验三门 (六章类型/星级门控):
 *  1. 类别门: 被动 L3 起可封; 机制/核心类 (命定之死/小男孩/天雷) 仅 L8+ 可短暂封印 (绝不永久移除)。
 *  2. 星级门: 可封星级随级抬到 10★ (maxSealableStar(L)=L), 目标星 > 干员可封星级则拒 (需更高等级)。
 *  3. (槽位/CD 占用门不在本类: 那依赖每精英活跃封印态, 见 {@link SealRegistry}; 本类只做"该等级能否封该类别该星"
 *     的静态校验 + 产出窗口/CD。)
 *
 * 校验失败返回带原因的 {@link Result} (六章: 失败给原因提示 需更高等级 / 类别未解锁), 不抛异常 (校验失败是
 * 正常业务分支, 给玩家提示, 非异常)。GameTest 直断言可否/原因/窗口/CD, 删校验必挂。
 */
public final class SealPlan {

    private SealPlan() {
    }

    /** 封印校验失败原因 (六章面板失败提示; lang key agent.seal.fail.* 据此)。 */
    public enum FailReason {
        /** 本类别封印未解锁 (被动 L<3 / 机制 L<8)。 */
        CATEGORY_LOCKED,
        /** 目标星级超过干员可封星级 (需更高等级)。 */
        STAR_TOO_HIGH
    }

    /**
     * 封印计划结果 (不可变值对象): 成功带窗口/CD, 失败带原因。
     *
     * @param ok            是否可封印
     * @param windowSeconds 成功时的封印窗口时长 (秒; 失败为 0)
     * @param cooldownSeconds 成功时的封印 CD (秒; 失败为 0)
     * @param reason        失败原因 (成功为 null)
     */
    public record Result(boolean ok, int windowSeconds, int cooldownSeconds, FailReason reason) {

        static Result success(int windowSeconds, int cooldownSeconds) {
            return new Result(true, windowSeconds, cooldownSeconds, null);
        }

        static Result fail(FailReason reason) {
            return new Result(false, 0, 0, reason);
        }
    }

    /**
     * 裁决某干员等级能否封印某初始星级精英的某类别词条 (六章三门校验; 不含槽位/CD 占用)。
     *
     * @param agentLevel  干员等级 (内部经 clampLevel 夹 [1,10])
     * @param star        精英初始星级 (1-10)
     * @param category    词条类别 (被动 / 机制)
     * @return 成功带窗口/CD; 失败带原因 ({@link FailReason})
     */
    public static Result plan(int agentLevel, int star, SealCategory category) {
        int lv = AgentSkillTable.clampLevel(agentLevel);

        // 门 1: 类别解锁。
        boolean categoryUnlocked = switch (category) {
            case PASSIVE -> AgentSkillTable.isPassiveSealUnlocked(lv);
            case MECHANIC -> AgentSkillTable.isMechanicSealUnlocked(lv);
        };
        if (!categoryUnlocked) {
            return Result.fail(FailReason.CATEGORY_LOCKED);
        }

        // 门 2: 星级。可封星级随级抬到 10★ (maxSealableStar(L)=L)。
        if (star > AgentSkillTable.maxSealableStar(lv)) {
            return Result.fail(FailReason.STAR_TOO_HIGH);
        }

        int window = AgentSkillTable.sealWindowSeconds(lv, category);
        int cd = AgentSkillTable.sealCooldownSeconds(lv, category);
        return Result.success(window, cd);
    }

    /** 便捷布尔入口: 某等级能否封该星该类别 (不关心窗口/CD)。 */
    public static boolean canSeal(int agentLevel, int star, SealCategory category) {
        return plan(agentLevel, star, category).ok();
    }
}
