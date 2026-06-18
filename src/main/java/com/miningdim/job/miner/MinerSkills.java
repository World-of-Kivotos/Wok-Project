package com.miningdim.job.miner;

/**
 * 矿工逐级数值表的唯一解析点 (纯计算, 无副作用, 便于 GameTest 断言具体业务结果)。
 *
 * 成长模型 ("解锁后逐级变强", Miner_Job_DesignSpec 第二章): 每个技能在其解锁级前数值为 0 / 不生效;
 * 解锁级到 L10 线性插值 (端点见 {@link MinerConstants})。本类不持任何状态, 输入矿工等级 (1-10) 输出当前值。
 *
 * 全部数值实时取 {@link MinerConstants} (集成阶段改读 MiningServerConfig); 不在本类硬编码任何裸值。
 */
public final class MinerSkills {

    private MinerSkills() {
    }

    // ---- 五、被动类 ----

    /**
     * 挖矿提速倍率 (BreakSpeed event.setNewSpeed(speed * mult))。L1 = 1.15, L10 = 2.10 (+110% 封顶)。
     * 未解锁 (理论上 L1 即解锁) 返回 1.0 (无加成)。
     */
    public static double digSpeedMultiplier(int level) {
        if (level < MinerConstants.DIG_SPEED_UNLOCK_LEVEL) {
            return 1.0D;
        }
        double bonus = lerpUnlockToMax(level, MinerConstants.DIG_SPEED_UNLOCK_LEVEL,
                MinerConstants.DIG_SPEED_BONUS_AT_UNLOCK, MinerConstants.DIG_SPEED_BONUS_AT_MAX);
        return 1.0D + bonus;
    }

    /** 不耗耐久概率 [0,1]。L1 = 0.05, L10 = 0.30 封顶。未解锁返回 0。 */
    public static double durabilitySaveChance(int level) {
        if (level < MinerConstants.DURABILITY_SAVE_UNLOCK_LEVEL) {
            return 0.0D;
        }
        return lerpUnlockToMax(level, MinerConstants.DURABILITY_SAVE_UNLOCK_LEVEL,
                MinerConstants.DURABILITY_SAVE_CHANCE_AT_UNLOCK, MinerConstants.DURABILITY_SAVE_CHANCE_AT_MAX);
    }

    /** 是否免疫挖掘疲劳 (里程碑, L4 起)。 */
    public static boolean immuneToMiningFatigue(int level) {
        return level >= MinerConstants.MINING_FATIGUE_IMMUNE_UNLOCK_LEVEL;
    }

    // ---- 六、获取更多矿 (时运 B) ----

    /** 矿脉时运额外掉落期望 [0,..]。L4 = 0.08, L10 = 0.50。未解锁返回 0 (无额外掉落)。 */
    public static double fortuneExtraExpectancy(int level) {
        if (level < MinerConstants.FORTUNE_UNLOCK_LEVEL) {
            return 0.0D;
        }
        return lerpUnlockToMax(level, MinerConstants.FORTUNE_UNLOCK_LEVEL,
                MinerConstants.FORTUNE_EXTRA_AT_UNLOCK, MinerConstants.FORTUNE_EXTRA_AT_MAX);
    }

    // ---- 四、速挖类 ----

    /** 连锁充能池大小 (块)。L2 = 16, L10 = 48。未解锁返回 0。 */
    public static int chainChargePool(int level) {
        if (level < MinerConstants.CHAIN_UNLOCK_LEVEL) {
            return 0;
        }
        return (int) Math.round(lerpUnlockToMax(level, MinerConstants.CHAIN_UNLOCK_LEVEL,
                MinerConstants.CHAIN_POOL_AT_UNLOCK, MinerConstants.CHAIN_POOL_AT_MAX));
    }

    /** 连锁整池回满时长 (tick)。L2 = 6000 (5 min), L10 = 4200 (3.5 min)。未解锁返回最长 (回满最慢)。 */
    public static int chainRefillFullTicks(int level) {
        if (level < MinerConstants.CHAIN_UNLOCK_LEVEL) {
            return MinerConstants.CHAIN_REFILL_FULL_TICKS_AT_UNLOCK;
        }
        return (int) Math.round(lerpUnlockToMax(level, MinerConstants.CHAIN_UNLOCK_LEVEL,
                MinerConstants.CHAIN_REFILL_FULL_TICKS_AT_UNLOCK, MinerConstants.CHAIN_REFILL_FULL_TICKS_AT_MAX));
    }

    /** 隧道挖 CD (tick)。L9 = 600 (30s), L10 = 400 (20s)。未解锁返回 Integer.MAX_VALUE (永不就绪)。 */
    public static int tunnelCooldownTicks(int level) {
        if (level < MinerConstants.TUNNEL_UNLOCK_LEVEL) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.round(lerpUnlockToMax(level, MinerConstants.TUNNEL_UNLOCK_LEVEL,
                MinerConstants.TUNNEL_CD_TICKS_AT_UNLOCK, MinerConstants.TUNNEL_CD_TICKS_AT_MAX));
    }

    // ---- 三、探测类 ----

    /** 矿物探测半径 (格)。L3 = 6, L10 = 16。未解锁返回 0。 */
    public static int oreScanRadius(int level) {
        if (level < MinerConstants.ORE_SCAN_UNLOCK_LEVEL) {
            return 0;
        }
        return (int) Math.round(lerpUnlockToMax(level, MinerConstants.ORE_SCAN_UNLOCK_LEVEL,
                MinerConstants.ORE_SCAN_RADIUS_AT_UNLOCK, MinerConstants.ORE_SCAN_RADIUS_AT_MAX));
    }

    /** 矿物探测 CD (tick)。L3 = 6000 (300s), L10 = 3600 (180s)。未解锁返回 Integer.MAX_VALUE。 */
    public static int oreScanCooldownTicks(int level) {
        if (level < MinerConstants.ORE_SCAN_UNLOCK_LEVEL) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.round(lerpUnlockToMax(level, MinerConstants.ORE_SCAN_UNLOCK_LEVEL,
                MinerConstants.ORE_SCAN_CD_TICKS_AT_UNLOCK, MinerConstants.ORE_SCAN_CD_TICKS_AT_MAX));
    }

    /** 陷阱探测半径 (格)。L5 = 6, L10 = 12。未解锁返回 0。 */
    public static int trapScanRadius(int level) {
        if (level < MinerConstants.TRAP_SCAN_UNLOCK_LEVEL) {
            return 0;
        }
        return (int) Math.round(lerpUnlockToMax(level, MinerConstants.TRAP_SCAN_UNLOCK_LEVEL,
                MinerConstants.TRAP_SCAN_RADIUS_AT_UNLOCK, MinerConstants.TRAP_SCAN_RADIUS_AT_MAX));
    }

    /** 陷阱探测 CD (tick)。L5 = 4800 (240s), L10 = 3000 (150s)。未解锁返回 Integer.MAX_VALUE。 */
    public static int trapScanCooldownTicks(int level) {
        if (level < MinerConstants.TRAP_SCAN_UNLOCK_LEVEL) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.round(lerpUnlockToMax(level, MinerConstants.TRAP_SCAN_UNLOCK_LEVEL,
                MinerConstants.TRAP_SCAN_CD_TICKS_AT_UNLOCK, MinerConstants.TRAP_SCAN_CD_TICKS_AT_MAX));
    }

    // ---- 七、生存类 ----

    /**
     * 耐压: danger 时间项累积/衰减缩放系数。L4 = 0.85, L10 = 0.60 封底; 不低于封底, 不为 0, 不动 zoneTerm。
     * 未解锁 (L1-3) 返回 1.0 (无缩放, 不减压力)。
     */
    public static double dangerTimeFactor(int level) {
        if (level < MinerConstants.DANGER_RESIST_UNLOCK_LEVEL) {
            return 1.0D;
        }
        double v = lerpUnlockToMax(level, MinerConstants.DANGER_RESIST_UNLOCK_LEVEL,
                MinerConstants.DANGER_TIME_FACTOR_AT_UNLOCK, MinerConstants.DANGER_TIME_FACTOR_AT_MAX);
        // 红线封底: 即使插值/配置漂移也绝不低于封底 (防实质免疫压力系统)。
        return Math.max(MinerConstants.DANGER_TIME_FACTOR_FLOOR, v);
    }

    /**
     * 矿脉抗性: 陷阱专属来源减伤比 [0, 0.35]。L5 = 0.10, L10 = 0.35 封顶。
     * 未解锁返回 0 (无减伤)。封顶硬钳 (防变战斗减伤天赋)。
     */
    public static double trapDamageReduction(int level) {
        if (level < MinerConstants.VEIN_RESIST_UNLOCK_LEVEL) {
            return 0.0D;
        }
        double v = lerpUnlockToMax(level, MinerConstants.VEIN_RESIST_UNLOCK_LEVEL,
                MinerConstants.VEIN_RESIST_REDUCTION_AT_UNLOCK, MinerConstants.VEIN_RESIST_REDUCTION_AT_MAX);
        return Math.min(MinerConstants.VEIN_RESIST_REDUCTION_CAP, v);
    }

    /** 脱险归途 CD (tick)。L7 = 9600 (8 min), L10 = 6000 (5 min)。未解锁返回 Integer.MAX_VALUE。 */
    public static int evacuateCooldownTicks(int level) {
        if (level < MinerConstants.EVACUATE_UNLOCK_LEVEL) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.round(lerpUnlockToMax(level, MinerConstants.EVACUATE_UNLOCK_LEVEL,
                MinerConstants.EVACUATE_CD_TICKS_AT_UNLOCK, MinerConstants.EVACUATE_CD_TICKS_AT_MAX));
    }

    /** 声东击西 CD (tick)。L9 = 6000 (5 min), L10 = 4200 (3.5 min)。未解锁返回 Integer.MAX_VALUE。 */
    public static int decoyCooldownTicks(int level) {
        if (level < MinerConstants.DECOY_UNLOCK_LEVEL) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.round(lerpUnlockToMax(level, MinerConstants.DECOY_UNLOCK_LEVEL,
                MinerConstants.DECOY_CD_TICKS_AT_UNLOCK, MinerConstants.DECOY_CD_TICKS_AT_MAX));
    }

    // ---- 解锁状态查询 (供网络/HUD/开关校验) ----

    public static boolean chainUnlocked(int level) {
        return level >= MinerConstants.CHAIN_UNLOCK_LEVEL;
    }

    public static boolean tunnelUnlocked(int level) {
        return level >= MinerConstants.TUNNEL_UNLOCK_LEVEL;
    }

    public static boolean oreScanUnlocked(int level) {
        return level >= MinerConstants.ORE_SCAN_UNLOCK_LEVEL;
    }

    public static boolean trapScanUnlocked(int level) {
        return level >= MinerConstants.TRAP_SCAN_UNLOCK_LEVEL;
    }

    public static boolean evacuateUnlocked(int level) {
        return level >= MinerConstants.EVACUATE_UNLOCK_LEVEL;
    }

    public static boolean decoyUnlocked(int level) {
        return level >= MinerConstants.DECOY_UNLOCK_LEVEL;
    }

    public static boolean autoCollectUnlocked(int level) {
        return level >= MinerConstants.AUTO_COLLECT_UNLOCK_LEVEL;
    }

    public static boolean autoSmeltBaseUnlocked(int level) {
        return level >= MinerConstants.AUTO_SMELT_BASE_UNLOCK_LEVEL;
    }

    public static boolean autoSmeltGoldUnlocked(int level) {
        return level >= MinerConstants.AUTO_SMELT_GOLD_UNLOCK_LEVEL;
    }

    /**
     * 解锁级到 L10 线性插值: level==unlockLevel 返回 atUnlock, level>=MAX_LEVEL 返回 atMax, 中间线性。
     * 调用方须保证 level >= unlockLevel (未解锁分支由各 getter 提前返回处理)。
     */
    private static double lerpUnlockToMax(int level, int unlockLevel, double atUnlock, double atMax) {
        int clamped = Math.min(MinerConstants.MAX_LEVEL, Math.max(unlockLevel, level));
        int span = MinerConstants.MAX_LEVEL - unlockLevel;
        if (span <= 0) {
            return atMax; // 解锁级即满级 (理论不出现, 防 0 除)。
        }
        double t = (double) (clamped - unlockLevel) / (double) span;
        return atUnlock + (atMax - atUnlock) * t;
    }
}
