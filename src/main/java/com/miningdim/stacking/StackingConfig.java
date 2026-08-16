package com.miningdim.stacking;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * 实体堆叠 (Mob Stacking) 全部参数的唯一来源 (需求规格 "一、默认参数表"; 硬编码即缺陷 C6, 走 ForgeConfigSpec)。
 * 自持一份 SERVER 级 SPEC, 由 {@link StackingSystem} 在本 package 内注册 (不碰中央 MiningServerConfig, 范式同
 * {@link com.miningdim.job.chef.ChefConfig})。业务代码经各 {@code *.get()} 实时读取 (禁缓存, 与工程惯例一致),
 * 故 /reload 改值即时生效。
 *
 * 字段与规格默认参数表 1:1 对应, 默认值照抄。passive 倍增 (剪毛/挤奶/产蛋) 与 multiply_xp 默认按规格开启,
 * 但提供 config 开关 —— 被动产出 xN + 经验 xN 是 faucet 倍增器, 与反洗钱定价强耦合 (见 Economy_BalanceSheet),
 * 服主平衡评审前可一键关被动倍增保守运营。
 *
 * 本期 (阶段 1 合并 + 持久化) 仅 merge.* / exclusions.* 有读取方 (StackMerge / StackingSystem); drops.* /
 * passive.* 由阶段 2 (主动/被动产出) 消费, 但默认参数表要求全量定义, 故此处一并落地 (有明确后续读取方, 非死键)。
 *
 * 顶层 {@link #ENABLED} 是子系统总开关 (F094 修复配套的运维旋钮, 与合并候选白名单 —— 由 {@link StackMerge} 硬编码,
 * 非 config 键 —— 是两回事: 白名单决定 "谁能堆", 本开关决定 "整个子系统是否发新的合并")。interaction.* 是 FR-5
 * 拆分/拴绳语义 (F066), 由 {@link StackSplit} 消费。
 */
public final class StackingConfig {

    private StackingConfig() {
    }

    public static final ForgeConfigSpec SPEC;

    /** merge.trigger 枚举: ON_MOVE (仅对移动过的实体尝试合并) / INTERVAL (纯周期全量扫描)。 */
    public enum MergeTrigger {
        ON_MOVE,
        INTERVAL
    }

    /** drops.death_mode 枚举: INSTANT_ALL (整堆瞬死掉全部) / ONE_PER_KILL (每次击杀剥离 1)。阶段 2 消费。 */
    public enum DeathMode {
        INSTANT_ALL,
        ONE_PER_KILL
    }

    /** drops.loot_roll_mode 枚举: PER_INDIVIDUAL (逐个独立 roll) / MULTIPLY_BASE (base×N, 不推荐)。阶段 2 消费。 */
    public enum LootRollMode {
        PER_INDIVIDUAL,
        MULTIPLY_BASE
    }

    /** interaction.leashMode 枚举 (FR-5.2): 拴绳作用于整堆还是拆出单个个体。 */
    public enum LeashMode {
        WHOLE_STACK,
        SPLIT_ONE
    }

    /** 子系统总开关 (F094 修复配套); false 时新合并全面停止, 但已成堆叠仍可正常走掉落/被动/拆分结算, 不丢个体。 */
    public static final ForgeConfigSpec.BooleanValue ENABLED;

    // ---- merge.* (合并核心; 阶段 1 消费) ----
    public static final ForgeConfigSpec.IntValue MERGE_RADIUS_HORIZONTAL;
    public static final ForgeConfigSpec.IntValue MERGE_RADIUS_VERTICAL;
    public static final ForgeConfigSpec.EnumValue<MergeTrigger> MERGE_TRIGGER;
    public static final ForgeConfigSpec.IntValue MERGE_SCAN_INTERVAL;
    public static final ForgeConfigSpec.IntValue MERGE_MAX_STACK_SIZE;
    public static final ForgeConfigSpec.BooleanValue MERGE_REQUIRE_MOVED;

    // ---- drops.* (主动产出; 阶段 2 消费) ----
    public static final ForgeConfigSpec.EnumValue<DeathMode> DROPS_DEATH_MODE;
    public static final ForgeConfigSpec.EnumValue<LootRollMode> DROPS_LOOT_ROLL_MODE;
    public static final ForgeConfigSpec.BooleanValue DROPS_MULTIPLY_XP;

    // ---- passive.* (被动产出倍增; 阶段 2 消费; faucet 倍增器, 可关) ----
    public static final ForgeConfigSpec.BooleanValue PASSIVE_SHEAR_ENABLED;
    public static final ForgeConfigSpec.BooleanValue PASSIVE_MILK_ENABLED;
    public static final ForgeConfigSpec.BooleanValue PASSIVE_EGG_ENABLED;

    // ---- exclusions.* (排除规则; 阶段 1 消费) ----
    public static final ForgeConfigSpec.BooleanValue EXCLUSIONS_NAMED;
    public static final ForgeConfigSpec.BooleanValue EXCLUSIONS_TAMED;
    public static final ForgeConfigSpec.BooleanValue EXCLUSIONS_BOSS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXCLUSIONS_BLACKLIST;

    // ---- interaction.* (拆分/拴绳; FR-5 消费) ----
    public static final ForgeConfigSpec.EnumValue<LeashMode> LEASH_MODE;
    public static final ForgeConfigSpec.IntValue SPLIT_GRACE_TICKS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        ENABLED = b.comment("Master kill switch for the entity stacking subsystem. false stops all NEW merges (the periodic scan short-circuits); already-formed stacks keep settling correctly through drops/passive/split handlers so no individual is lost. Hot-reloadable.")
                .define("enabled", true);
        // 默认 true: 合并候选已收口到白名单四种低价值农场动物 (决策 D1), 崩服级/资产损毁级后果 (F004/F005/F018/F037)
        // 已随白名单化全部消除, 剩余只是 F038 那条按决策 D2 接受的掉落等价语义, 无需默认关停等待人工开启。

        b.push("merge");
        MERGE_RADIUS_HORIZONTAL = b.comment("Horizontal merge radius in blocks (FR-1.1)")
                .defineInRange("radiusHorizontal", 5, 0, 64);
        MERGE_RADIUS_VERTICAL = b.comment("Vertical merge radius in blocks (FR-1.1)")
                .defineInRange("radiusVertical", 3, 0, 64);
        MERGE_TRIGGER = b.comment("ON_MOVE: only entities that moved since last scan are merge candidates (NFR-3); INTERVAL: full periodic scan")
                .defineEnum("trigger", MergeTrigger.ON_MOVE);
        MERGE_SCAN_INTERVAL = b.comment("Fallback periodic scan period in ticks (100 = 5s); also the primary scan period when trigger=INTERVAL")
                .defineInRange("scanIntervalTicks", 100, 1, 6000);
        MERGE_MAX_STACK_SIZE = b.comment("Max stack size on any single entity; overflow forms a new stack (FR-1.3)")
                .defineInRange("maxStackSize", 64, 1, 100000);
        MERGE_REQUIRE_MOVED = b.comment("Only attempt merges for entities that changed block position since last scan, to cut idle-farm scan cost (NFR-3)")
                .define("requireMoved", true);
        b.pop();

        b.push("drops");
        DROPS_DEATH_MODE = b.comment("INSTANT_ALL: one kill drops all N and removes the stack; ONE_PER_KILL: each kill peels off 1 (FR-2.5)")
                .defineEnum("deathMode", DeathMode.INSTANT_ALL);
        DROPS_LOOT_ROLL_MODE = b.comment("PER_INDIVIDUAL: roll the loot table independently per virtual individual (FR-2.2); MULTIPLY_BASE: base*N (statistically wrong, not recommended)")
                .defineEnum("lootRollMode", LootRollMode.PER_INDIVIDUAL);
        DROPS_MULTIPLY_XP = b.comment("Multiply dropped experience by stack size (FR-2.4). FAUCET MULTIPLIER: disable for conservative economy operation before balance review")
                .define("multiplyXp", true);
        b.pop();

        b.push("passive");
        b.comment("Passive-production multipliers (FR-3). FAUCET MULTIPLIERS coupled to anti-laundering pricing (Economy_BalanceSheet): disable any to neutralize that faucet.");
        PASSIVE_SHEAR_ENABLED = b.comment("Multiply wool output when shearing a sheep stack (FR-3.1)")
                .define("shearEnabled", true);
        PASSIVE_MILK_ENABLED = b.comment("Multiply milk output when milking a cow stack, bounded by empty buckets carried (FR-3.2)")
                .define("milkEnabled", true);
        PASSIVE_EGG_ENABLED = b.comment("Multiply egg-laying throughput for a chicken stack (FR-3.3)")
                .define("eggEnabled", true);
        b.pop();

        b.push("exclusions");
        EXCLUSIONS_NAMED = b.comment("Named (name-tagged) entities never participate in stacking (C-4 / FR-1.4)")
                .define("named", true);
        EXCLUSIONS_TAMED = b.comment("Tamed entities (wolves/cats/horses/parrots) never participate in stacking (C-4)")
                .define("tamed", true);
        EXCLUSIONS_BOSS = b.comment("Bosses never participate in stacking (C-4)")
                .define("boss", true);
        EXCLUSIONS_BLACKLIST = b.comment("Entity ids excluded from stacking, e.g. modded entities (C-3); format \"namespace:path\"")
                .defineList("blacklist", List.of(), o -> o instanceof String);
        b.pop();

        b.push("interaction");
        LEASH_MODE = b.comment("FR-5.2 leash semantics. SPLIT_ONE: using a lead on a stack peels off exactly 1 individual and leashes that one. WHOLE_STACK: the lead attaches to the whole stack entity (vanilla behaviour); a leashed stack stops absorbing others.")
                .defineEnum("leashMode", LeashMode.SPLIT_ONE);
        SPLIT_GRACE_TICKS = b.comment("FR-5.1 ticks during which a freshly split-off individual is excluded from re-merging, so the player can lead it away (600 = 30s)")
                .defineInRange("splitGraceTicks", 600, 0, 72000);
        b.pop();

        SPEC = b.build();
    }

    /**
     * 测试兜底加载: GameTest 时 StackingSystem 未接入主类 registerConfig 链, SPEC 未绑定任何 config,
     * 此时直接 {@code *.get()} 会抛 ISE。用一个空内存 config 触发 spec 自校正填默认值 (范式同 ChefConfig.ensureLoadedForTest)。
     * 已加载则空操作 (不覆盖真实 SERVER toml)。仅 GameTest 调用。
     */
    public static void ensureLoadedForTest() {
        if (!SPEC.isLoaded()) {
            SPEC.setConfig(CommentedConfig.inMemory());
        }
    }
}
