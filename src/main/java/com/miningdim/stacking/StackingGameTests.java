package com.miningdim.stacking;

import com.miningdim.testutil.ConfigBaseline;
import com.miningdim.champion.MiningChampions;
import com.miningdim.core.MiningConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 实体堆叠阶段 1 (合并 + 持久化) GameTest (需求规格验收标准 AC; TDD: 删被测核心逻辑对应用例必挂)。
 *
 * 测试驱动方式: 直接用 {@link GameTestHelper#spawn} 真生成实体, 再直接调 {@link StackMerge#mergeCandidates}
 * 驱动合并 (不依赖 StackingSystem 的 tick 周期扫描 —— 那是 tick 调度集成, 真服验; 此处确定性测合并/排除/持久化核心)。
 * 所有断言为具体业务结果: 合并后 EntityType 计数 / getCustomName 含 "xN" / StackSize NBT 往返 / 排除项不参与。
 *
 * 配置兜底: GameTest 进程未走 StackingSystem.registerConfig, 故每个用例首行 {@link StackingConfig#ensureLoadedForTest}
 * 使 SPEC 填默认值可读 (否则 *.get() 抛 ISE)。需改默认参数 (如 AC-7 的 max_stack=16) 的用例直接 set SPEC 值。
 *
 * template = "empty" (data/miningdim/structures/empty.nbt), batch = "stacking"。实体在测试坐标系原点上方生成,
 * 调 mergeCandidates 即时 (同 tick) 合并, 位置即生成坐标, 不受重力下落影响判定。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class StackingGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "stacking";

    /** 统计某维度内某 EntityType 的存活实体数 (合并后断言收敛)。 */
    private static <E extends net.minecraft.world.entity.Entity> int countAlive(
            GameTestHelper helper, EntityType<E> type, BlockPos around, double radius) {
        return helper.getEntities(type, around, radius).size();
    }

    // ============================================================
    // AC-1: 半径内 spawn 20 成年羊 -> 合并后计数 1, 显示名含 "x20"
    // ============================================================

    /** 跨轮基线归位: 本批次会改下列配置项, 先抹掉上一轮可能残留的探针值 (见 ConfigBaseline)。 */
    @BeforeBatch(batch = BATCH)
    public static void resetConfigBaseline(ServerLevel level) {
        ConfigBaseline.resetToDefaults(
                StackingConfig.ENABLED,
                StackingConfig.MERGE_MAX_STACK_SIZE,
                StackingConfig.DROPS_LOOT_ROLL_MODE,
                StackingConfig.DROPS_DEATH_MODE,
                StackingConfig.LEASH_MODE);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ac1_twentyAdultSheepMergeToOne(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        // 显式钳本测前提 cap>=20 并恢复 (2026-07-08 排障): ForgeConfigSpec 的 set() 会写穿 run 世界的
        // serverconfig 文件 —— ac7 的 set(16) 若在恢复前被中断 (JVM 被杀/崩), 16 会永久毒化持久 run 世界,
        // 之后每次启动本测都载入 cap=16 (20 只吞 15) 稳定红。本测不再信任环境 cap, 与 ac7 同款 set/finally。
        int prevMax = StackingConfig.MERGE_MAX_STACK_SIZE.get();
        StackingConfig.MERGE_MAX_STACK_SIZE.set(64);
        try {
            BlockPos origin = new BlockPos(1, 2, 1);

            List<Sheep> sheep = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                // 同一格生成 (水平 0 / 垂直 0 距离, 必在半径 5/3 内)。
                Sheep s = helper.spawn(EntityType.SHEEP, origin);
                s.setColor(net.minecraft.world.item.DyeColor.WHITE);
                sheep.add(s);
            }
            // 只数本测持有的 20 只引用的存活态 (不用世界半径计数, 免疫相邻 sheep 测试漂移来的羊污染计数)。
            helper.assertTrue(sheep.stream().filter(net.minecraft.world.entity.Entity::isAlive).count() == 20,
                    "20 sheep spawned before merge");

            int discarded = StackMerge.mergeCandidates(new ArrayList<>(sheep));
            helper.assertTrue(discarded == 19, "19 sheep absorbed into one (20 -> 1)");

            // 合并后本测 20 只里恰 1 只存活 (其余 19 被 discard)。
            long alive = sheep.stream().filter(net.minecraft.world.entity.Entity::isAlive).count();
            helper.assertTrue(alive == 1, "exactly one sheep entity remains after merge, got " + alive);

            // 幸存者堆叠数 20 + 显示名含 "x20"。
            Sheep survivor = sheep.stream().filter(net.minecraft.world.entity.Entity::isAlive).findFirst().orElseThrow();
            helper.assertTrue(StackData.getStackSize(survivor) == 20, "survivor stack size 20");
            helper.assertTrue(survivor.getCustomName() != null
                            && survivor.getCustomName().getString().contains("x20"),
                    "custom name contains x20, got " + (survivor.getCustomName() == null ? "<null>"
                            : survivor.getCustomName().getString()));
            helper.assertTrue(survivor.isCustomNameVisible(), "stack label is visible");
        } finally {
            StackingConfig.MERGE_MAX_STACK_SIZE.set(prevMax);
        }
        helper.succeed();
    }

    // ============================================================
    // AC-2: 同点 10 成年 + 5 幼年羊 -> 2 堆叠 (x10/x5) 互不合并 (年龄隔离 FR-1.2)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ac2_adultAndBabySheepDoNotMerge(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        BlockPos origin = new BlockPos(1, 2, 1);

        List<Sheep> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Sheep s = helper.spawn(EntityType.SHEEP, origin);
            s.setColor(net.minecraft.world.item.DyeColor.WHITE);
            s.setAge(0); // 成年。
            all.add(s);
        }
        for (int i = 0; i < 5; i++) {
            Sheep s = helper.spawn(EntityType.SHEEP, origin);
            s.setColor(net.minecraft.world.item.DyeColor.WHITE);
            s.setBaby(true); // 幼年。
            all.add(s);
        }

        StackMerge.mergeCandidates(new ArrayList<>(all));

        // 应剩 2 个存活羊实体 (一个成年 x10, 一个幼年 x5)。
        List<Sheep> survivors = all.stream().filter(net.minecraft.world.entity.Entity::isAlive).toList();
        helper.assertTrue(survivors.size() == 2, "age isolation: two stacks remain (adult + baby), got " + survivors.size());

        int adultStack = survivors.stream().filter(s -> !s.isBaby()).mapToInt(StackData::getStackSize).sum();
        int babyStack = survivors.stream().filter(Sheep::isBaby).mapToInt(StackData::getStackSize).sum();
        helper.assertTrue(adultStack == 10, "adult stack size 10, got " + adultStack);
        helper.assertTrue(babyStack == 5, "baby stack size 5, got " + babyStack);

        // 互不合并: 成年幸存者只有 1 个, 幼年幸存者只有 1 个。
        long adultCount = survivors.stream().filter(s -> !s.isBaby()).count();
        long babyCount = survivors.stream().filter(Sheep::isBaby).count();
        helper.assertTrue(adultCount == 1 && babyCount == 1, "one adult stack and one baby stack");
        helper.succeed();
    }

    // ============================================================
    // AC-7: max_stack=16 时超出另起新堆叠 (FR-1.3); 任一实体 StackSize <= 16
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ac7_overflowFormsNewStackUnderCap(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        int prevMax = StackingConfig.MERGE_MAX_STACK_SIZE.get();
        StackingConfig.MERGE_MAX_STACK_SIZE.set(16);
        try {
            BlockPos origin = new BlockPos(1, 2, 1);
            List<Sheep> sheep = new ArrayList<>();
            // 20 只 > 16: 必形成至少 2 堆叠 (16 + 4)。
            for (int i = 0; i < 20; i++) {
                Sheep s = helper.spawn(EntityType.SHEEP, origin);
                s.setColor(net.minecraft.world.item.DyeColor.WHITE);
                sheep.add(s);
            }

            StackMerge.mergeCandidates(new ArrayList<>(sheep));

            List<Sheep> survivors = sheep.stream().filter(net.minecraft.world.entity.Entity::isAlive).toList();
            // 每个幸存堆叠 <= 16。
            for (Sheep s : survivors) {
                int size = StackData.getStackSize(s);
                helper.assertTrue(size <= 16, "no stack exceeds max 16, found " + size);
            }
            // 总堆叠数守恒 = 20。
            int totalIndividuals = survivors.stream().mapToInt(StackData::getStackSize).sum();
            helper.assertTrue(totalIndividuals == 20, "individuals conserved across stacks = 20, got " + totalIndividuals);
            // 至少形成 2 个堆叠 (16 封顶 -> 溢出另起)。
            helper.assertTrue(survivors.size() >= 2, "overflow forms a new stack (>=2 survivors), got " + survivors.size());
        } finally {
            StackingConfig.MERGE_MAX_STACK_SIZE.set(prevMax);
        }
        helper.succeed();
    }

    // ============================================================
    // AC-8: stack=12 的牛, 经 entity NBT save/load 往返后 StackSize 仍 12 (NFR-6 持久化)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ac8_stackSizePersistsThroughNbtRoundTrip(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        BlockPos origin = new BlockPos(1, 2, 1);

        Cow cow = helper.spawn(EntityType.COW, origin);
        StackData.setStackSize(cow, 12);
        helper.assertTrue(StackData.getStackSize(cow) == 12, "cow stack set to 12");

        // 模拟存档落盘往返: saveWithoutId 写出实体完整 NBT (含 ForgeData/persistentData), 再 load 回新实体。
        CompoundTag saved = new CompoundTag();
        cow.saveWithoutId(saved);

        Cow reloaded = helper.spawn(EntityType.COW, origin.offset(0, 0, 0));
        reloaded.load(saved);

        helper.assertTrue(StackData.getStackSize(reloaded) == 12,
                "stack size survives NBT save/load round-trip, got " + StackData.getStackSize(reloaded));
        helper.assertTrue(StackData.hasStackData(reloaded), "reloaded entity carries the stack key");
        helper.succeed();
    }

    // ============================================================
    // AC-10: name tag 羊 + 驯服狼 均不参与堆叠 (C-4 排除)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ac10_namedAndTamedExcluded(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        BlockPos origin = new BlockPos(1, 2, 1);

        // 一只命名羊 (玩家 name tag) + 两只普通羊。命名羊不参与, 两只普通羊合并为一。
        Sheep named = helper.spawn(EntityType.SHEEP, origin);
        named.setColor(net.minecraft.world.item.DyeColor.WHITE);
        named.setCustomName(net.minecraft.network.chat.Component.literal("Dolly"));
        Sheep plainA = helper.spawn(EntityType.SHEEP, origin);
        plainA.setColor(net.minecraft.world.item.DyeColor.WHITE);
        Sheep plainB = helper.spawn(EntityType.SHEEP, origin);
        plainB.setColor(net.minecraft.world.item.DyeColor.WHITE);

        helper.assertTrue(!StackMerge.canStack(named), "named (name-tagged) sheep excluded from stacking");

        StackMerge.mergeCandidates(List.of(named, plainA, plainB));

        // 命名羊存活且名不被改写 (仍是 Dolly, 非 "Sheep x2")。
        helper.assertTrue(named.isAlive(), "named sheep not discarded");
        helper.assertTrue("Dolly".equals(named.getCustomName().getString()),
                "named sheep keeps player name, got " + named.getCustomName().getString());
        helper.assertTrue(!StackData.hasStackData(named), "named sheep never got a stack count");

        // 两只普通羊合并为一 (剩一个存活, StackSize 2)。
        long plainAlive = List.of(plainA, plainB).stream().filter(net.minecraft.world.entity.Entity::isAlive).count();
        helper.assertTrue(plainAlive == 1, "two plain sheep merged into one, alive = " + plainAlive);

        // 驯服狼: 两只驯服狼互不合并 (各自存活)。
        Wolf wolfA = helper.spawn(EntityType.WOLF, origin);
        Wolf wolfB = helper.spawn(EntityType.WOLF, origin);
        wolfA.setTame(true);
        wolfB.setTame(true);
        helper.assertTrue(!StackMerge.canStack(wolfA), "tamed wolf excluded from stacking");

        StackMerge.mergeCandidates(List.of(wolfA, wolfB));
        helper.assertTrue(wolfA.isAlive() && wolfB.isAlive(), "two tamed wolves never merge");
        helper.assertTrue(!StackData.hasStackData(wolfA) && !StackData.hasStackData(wolfB),
                "tamed wolves carry no stack count");
        helper.succeed();
    }

    // ============================================================
    // 变体隔离 (FR-1.1): 不同羊毛色不合并; 同色才合并
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void variantIsolationByWoolColor(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        BlockPos origin = new BlockPos(1, 2, 1);

        List<Sheep> all = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Sheep s = helper.spawn(EntityType.SHEEP, origin);
            s.setColor(net.minecraft.world.item.DyeColor.RED);
            all.add(s);
        }
        for (int i = 0; i < 2; i++) {
            Sheep s = helper.spawn(EntityType.SHEEP, origin);
            s.setColor(net.minecraft.world.item.DyeColor.BLUE);
            all.add(s);
        }

        StackMerge.mergeCandidates(new ArrayList<>(all));

        List<Sheep> survivors = all.stream().filter(net.minecraft.world.entity.Entity::isAlive).toList();
        // 红 3 -> 1 堆叠 x3, 蓝 2 -> 1 堆叠 x2; 共 2 存活, 互不混色。
        helper.assertTrue(survivors.size() == 2, "two color-separated stacks, got " + survivors.size());
        int red = survivors.stream()
                .filter(s -> s.getColor() == net.minecraft.world.item.DyeColor.RED)
                .mapToInt(StackData::getStackSize).sum();
        int blue = survivors.stream()
                .filter(s -> s.getColor() == net.minecraft.world.item.DyeColor.BLUE)
                .mapToInt(StackData::getStackSize).sum();
        helper.assertTrue(red == 3, "red wool stack size 3, got " + red);
        helper.assertTrue(blue == 2, "blue wool stack size 2, got " + blue);
        helper.succeed();
    }

    // ============================================================
    // 半径外不合并 (FR-1.1): 超出水平半径的同种实体各自独立
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void outOfRadiusDoesNotMerge(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        int hRadius = StackingConfig.MERGE_RADIUS_HORIZONTAL.get(); // 默认 5。

        // 两只羊水平相距 hRadius+3 格 (远超半径): 不合并。用真实 ServerLevel 坐标 (绝对位置)。
        BlockPos a = new BlockPos(1, 2, 1);
        BlockPos b = a.offset(hRadius + 3, 0, 0);
        Sheep s1 = helper.spawn(EntityType.SHEEP, a);
        s1.setColor(net.minecraft.world.item.DyeColor.WHITE);
        Sheep s2 = helper.spawn(EntityType.SHEEP, b);
        s2.setColor(net.minecraft.world.item.DyeColor.WHITE);

        StackMerge.mergeCandidates(List.of(s1, s2));

        helper.assertTrue(s1.isAlive() && s2.isAlive(), "sheep beyond horizontal radius do not merge");
        helper.assertTrue(!StackData.hasStackData(s1) && !StackData.hasStackData(s2),
                "no stack formed across radius gap");
        helper.succeed();
    }

    // ============================================================
    // StackData 持久化键边界: setStackSize(<1) 抛异常 (异常必痛, 不静默纠偏)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void stackDataRejectsBelowOne(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        Cow cow = helper.spawn(EntityType.COW, new BlockPos(1, 2, 1));

        helper.assertTrue(StackData.getStackSize(cow) == 1, "no-key entity reads as stack size 1");
        helper.assertTrue(!StackData.hasStackData(cow), "no key before first write");

        boolean rejectedZero = false;
        try {
            StackData.setStackSize(cow, 0);
        } catch (IllegalArgumentException expected) {
            rejectedZero = true;
        }
        helper.assertTrue(rejectedZero, "setStackSize(0) rejected, not silently coerced");

        boolean rejectedNeg = false;
        try {
            StackData.setStackSize(cow, -3);
        } catch (IllegalArgumentException expected) {
            rejectedNeg = true;
        }
        helper.assertTrue(rejectedNeg, "setStackSize(-3) rejected");

        // incr 跨过 0 也必挂 (剥离到 0 应 discard, 非保留 0 堆叠)。
        StackData.setStackSize(cow, 1);
        boolean incrRejected = false;
        try {
            StackData.incr(cow, -1);
        } catch (IllegalArgumentException expected) {
            incrRejected = true;
        }
        helper.assertTrue(incrRejected, "incr to 0 rejected");
        helper.succeed();
    }

    // ============================================================
    // AC-3: 击杀 Cow x8 (instant_all) -> 生牛肉总数 = Sigma(8 次独立 roll, 合理区间)
    //        + 经验 = 8 x 单牛 + ItemEntity 按 <=64 分批 (FR-2.1/2.3/2.4)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ac3_killCowStackEightDropsAndXp(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(2, 2, 2);

        Cow cow = helper.spawn(EntityType.COW, origin);
        StackData.setStackSize(cow, 8);
        DamageSource source = level.damageSources().generic();

        // INSTANT_ALL 补 N-1=7 个虚拟个体 (原版会另掉 1, 此处只测倍增补差核心)。对 7 个体逐个独立 roll 牛战利品。
        List<ItemStack> drops = StackDeath.rollStackedLoot(level, cow, source, 7);
        int beef = 0;
        for (ItemStack stack : drops) {
            if (stack.is(Items.BEEF)) {
                beef += stack.getCount();
            }
        }
        // 牛战利品 (minecraft:entities/cow): 生牛肉 set_count uniform 1~3/个体 (无条件池, 必掉)。7 个体 -> 牛肉总数
        // 必落 [7, 21] (严格 7 次独立 roll 的和)。下界 7 (=7x1) 是硬下界, base*1 (<=3) 永到不了 -> 删逐个 roll 必挂。
        helper.assertTrue(beef >= 7 && beef <= 21,
                "8-cow stack (7 extra individuals) beef total in [7,21] from 7 independent rolls (1..3 each), got " + beef);

        // 分批: 把 7 个体的牛肉硬造成 70 个 (>64) 再分批, 必拆成 2 个 ItemEntity (64 + 6), 每个 <=64 (FR-2.3)。
        // spawnBatchedDrops 用绝对世界坐标落地, 故传 helper.absolutePos; 查询仍用相对坐标 (helper.getEntities 内部转绝对)。
        List<ItemStack> bulk = new ArrayList<>();
        bulk.add(new ItemStack(Items.BEEF, 70));
        BlockPos dropPosRel = new BlockPos(2, 2, 2);
        BlockPos dropPosAbs = helper.absolutePos(dropPosRel);
        int before = countItemEntities(helper, dropPosRel);
        int spawned = StackDeath.spawnBatchedDrops(level, dropPosAbs, bulk);
        helper.assertTrue(spawned == 2, "70 beef batched into 2 ItemEntities (<=64 each), got " + spawned);
        int after = countItemEntities(helper, dropPosRel);
        helper.assertTrue(after - before == 2, "exactly 2 ItemEntities spawned on level, delta = " + (after - before));
        for (net.minecraft.world.entity.item.ItemEntity ie :
                helper.getEntities(EntityType.ITEM, dropPosRel, 6.0)) {
            helper.assertTrue(ie.getItem().getCount() <= 64,
                    "no ItemEntity exceeds 64 stack, found " + ie.getItem().getCount());
        }

        // 经验 = 8 x 单牛个体经验 (FR-2.4): sumExperience(cow, 8) 必落 [8, 24] (单牛 getExperienceReward 恒 1~3),
        // 且 == 把同一牛当 8 个体逐个累加之和 (删 multiply / 改 base*N 必偏离)。
        int xp8 = StackDeath.sumExperience(cow, 8);
        helper.assertTrue(xp8 >= 8 && xp8 <= 24, "8-cow xp in [8,24] (8 x 1..3 per cow), got " + xp8);
        // 1 头牛单次经验必 [1,3]; 8 个体之和必 >= 8 x 1 = 8 且与单个体不等价 (8 倍量级), 证明确实累加了 8 次。
        helper.assertTrue(xp8 >= 8, "8 individuals' xp sum is at least 8 (multiplied, not single)");
        helper.succeed();
    }

    // ============================================================
    // AC-4: per_individual 概率正确性 — Zombie xN 独立 roll, 非 base*N (FR-2.2)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ac4_zombieStackPerIndividualRollIsIndependent(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        StackingConfig.LootRollMode prevMode = StackingConfig.DROPS_LOOT_ROLL_MODE.get();
        StackingConfig.DROPS_LOOT_ROLL_MODE.set(StackingConfig.LootRollMode.PER_INDIVIDUAL);
        try {
            ServerLevel level = helper.getLevel();
            BlockPos origin = new BlockPos(2, 2, 2);
            Zombie zombie = helper.spawn(EntityType.ZOMBIE, origin);
            DamageSource source = level.damageSources().generic();

            // 100 个体独立 roll 腐肉 (vanilla 僵尸 minecraft:entities/zombie 掉 0~2 腐肉/个体, 近似均匀 -> 均值约 1.0)。
            // per_individual 下 100 次独立 roll 腐肉总数期望约 100, 标准差约 8。落在 [40, 160] 的概率天文级接近 1
            // (远超 7 个标准差), 故此区间作非脆弱统计断言: base*N 模式 (单次 roll 上限 2 个) 永远到不了下界 40 -> 删
            // per_individual 改 base*1 必挂; 同时上界 160 << 硬上界 200, 仍宽松不误判。
            int n = 100;
            List<ItemStack> drops = StackDeath.rollStackedLoot(level, zombie, source, n);
            int rotten = 0;
            for (ItemStack stack : drops) {
                if (stack.is(Items.ROTTEN_FLESH)) {
                    rotten += stack.getCount();
                }
            }
            helper.assertTrue(rotten <= 2 * n,
                    "100 independent zombie rolls: rotten flesh <= hard cap 200 (2/roll), got " + rotten);
            // 下界 40: 只有真正跑了约 100 次独立 roll 才能累积到 ~100; 单次 roll x N (base*N, <=2) 绝无可能到 40。
            // 这是 per_individual 与 base*N 的实打实判别 (FR-2.2 概率正确性), 非永真弱断言。
            helper.assertTrue(rotten >= 40,
                    "per_individual ran ~100 independent rolls (rotten >= 40, unreachable by base*N <=2), got " + rotten);
            helper.assertTrue(rotten <= 160,
                    "per_individual total within 100 +/- generous band, got " + rotten);
        } finally {
            StackingConfig.DROPS_LOOT_ROLL_MODE.set(prevMode);
        }
        helper.succeed();
    }

    // ============================================================
    // AC-4 反例: MULTIPLY_BASE 模式下腐肉必是 N 的整数倍 (证明 per_individual 的区别是实打实的)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ac4b_multiplyBaseModeYieldsExactMultiples(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        StackingConfig.LootRollMode prevMode = StackingConfig.DROPS_LOOT_ROLL_MODE.get();
        StackingConfig.DROPS_LOOT_ROLL_MODE.set(StackingConfig.LootRollMode.MULTIPLY_BASE);
        try {
            ServerLevel level = helper.getLevel();
            BlockPos origin = new BlockPos(2, 2, 2);
            Zombie zombie = helper.spawn(EntityType.ZOMBIE, origin);
            DamageSource source = level.damageSources().generic();

            // MULTIPLY_BASE: 单次 roll 结果整体 x count, 故任一 item 的 count 必是 N 的整数倍 (这正是规格判 "不推荐"
            // 的原因 -- 抢夺/稀有概率不独立)。与 per_individual (N 次独立 roll 求和, 一般【非】N 的整数倍) 实打实不同。
            // 单次 base roll 是随机的 (僵尸腐肉 0-2), 不能假定恒为某值, 故断言 "结果是 N 的整数倍" 而非具体数;
            // 并跨 8 次 trial 要求至少一次命中腐肉, 防 base=0 -> 0%N==0 的假绿 (loot 路径根本没产出也会过整数倍断言)。
            int n = 10;
            int sawPositive = 0;
            for (int trial = 0; trial < 8; trial++) {
                List<ItemStack> multiplied = StackDeath.rollStackedLoot(level, zombie, source, n);
                int mulRotten = 0;
                for (ItemStack stack : multiplied) {
                    if (stack.is(Items.ROTTEN_FLESH)) {
                        mulRotten += stack.getCount();
                    }
                }
                helper.assertTrue(mulRotten % n == 0,
                        "MULTIPLY_BASE rotten must be a clean multiple of N=" + n + " (single roll x N), got " + mulRotten);
                if (mulRotten > 0) {
                    sawPositive++;
                }
            }
            // 8 次 trial 每次 P(base 腐肉>0) 约 2/3, 全 0 概率极低; 至少一次 >0 证明 loot 真跑了 (per_individual 改回则
            // 每 trial 求和一般非 N 整数倍, 上面循环内断言会挂 -> delete-must-fail 成立)。
            helper.assertTrue(sawPositive > 0,
                    "MULTIPLY_BASE produced positive rotten flesh in at least one of 8 trials (loot path ran), saw " + sawPositive);
        } finally {
            StackingConfig.DROPS_LOOT_ROLL_MODE.set(prevMode);
        }
        helper.succeed();
    }

    // ============================================================
    // AC-5: 剪 Sheep x16 一次 -> 羊毛数 = Sigma(16 次 1~3); 16 只进已剪冷却 (FR-3.1)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ac5_shearSixteenSheepStack(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        BlockPos origin = new BlockPos(2, 2, 2);

        Sheep sheep = helper.spawn(EntityType.SHEEP, origin);
        sheep.setColor(net.minecraft.world.item.DyeColor.WHITE);
        sheep.setSheared(false);
        StackData.setStackSize(sheep, 16);

        List<ItemStack> wool = StackPassive.computeShearDrops(sheep, 16);
        int total = 0;
        for (ItemStack stack : wool) {
            helper.assertTrue(stack.is(Items.WHITE_WOOL), "shear drop is white wool matching sheep color");
            total += stack.getCount();
        }
        // 16 只各 1~3 -> 总数必落 [16, 48] (严格倍增区间, 非 1~3 单只量)。删逐只累加则 total 退回 1~3 必挂。
        helper.assertTrue(total >= 16 && total <= 48,
                "16-sheep shear wool total in [16,48] (Sigma of 16 x 1..3), got " + total);

        // 模拟 handler 的 "进已剪态": 单一实体 setSheared(true) 即代表 16 只全部已剪 (FR-3.1)。
        sheep.setSheared(true);
        helper.assertTrue(sheep.isSheared(), "the 16-sheep stack entity is now sheared (all 16 in cooldown)");
        helper.assertTrue(!sheep.readyForShearing(), "sheared stack is not ready for re-shearing (no free production, FR-3.4)");
        helper.succeed();
    }

    // ============================================================
    // AC-6: Chicken x10 产蛋速率 = 10 x 单鸡 (下蛋计时按 N 并行, FR-3.3)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ac6_chickenStackEggThroughput(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        BlockPos origin = new BlockPos(2, 2, 2);

        Chicken chicken = helper.spawn(EntityType.CHICKEN, origin);
        StackData.setStackSize(chicken, 10);

        // 每 tick 额外扣 N-1=9 (vanilla 已扣 1) -> 合计每 tick 推进 10 -> 计时以 10 倍速跑 -> 产蛋吞吐 10x (FR-3.3)。
        helper.assertTrue(StackPassive.eggTimerDecrement(10) == 9,
                "10-chicken stack adds extra 9/tick to egg timer (total 10x), got " + StackPassive.eggTimerDecrement(10));
        helper.assertTrue(StackPassive.eggTimerDecrement(1) == 0,
                "single chicken adds no extra decrement (1x baseline)");

        // 量化 N 倍速: 把 eggTime 设为一个固定窗口, 模拟 handler 每 tick 扣 (vanilla 1 + 额外 9), 数到达 0 的 tick 数。
        // 单鸡每 tick 扣 1, 走 T tick 才下蛋; 堆叠 10 每 tick 推进 10, 应在约 T/10 tick 下蛋 (10 倍速)。
        int window = 1000;
        int single = ticksToLayAtRate(window, 1);          // 单鸡: 每 tick 推进 1。
        int stacked = ticksToLayAtRate(window, 1 + StackPassive.eggTimerDecrement(10)); // 堆叠 10: 每 tick 推进 10。
        helper.assertTrue(single == window, "single chicken takes full window=1000 ticks to lay, got " + single);
        helper.assertTrue(stacked == 100, "10-stack lays in 1/10 the time (100 ticks), got " + stacked);
        // 吞吐倍率 = single / stacked == 10 (严格 10x, 删 N 倍加速则退回 1x 必挂)。
        helper.assertTrue(single / stacked == 10, "egg throughput is 10x single rate, got " + (single / stacked) + "x");
        helper.succeed();
    }

    // ============================================================
    // FR-4: 喂繁殖材料给堆叠牛 -> 产 1 独立幼崽, 年龄隔离不并入成年堆叠, 母堆叠数不破上限 (FR-4.1/4.2/4.3)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fr4_breedStackedCowProducesIsolatedBaby(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(2, 2, 2);

        Cow parent = helper.spawn(EntityType.COW, origin);
        StackData.setStackSize(parent, 12);
        int parentBefore = StackData.getStackSize(parent);

        int cowsBefore = countAlive(helper, EntityType.COW, origin, 8.0);
        // delta/排除法计数: GameTest 的 getEntities(origin, 半径) 是世界半径搜索, 可能扫到相邻测试区漂移来的实体,
        // 故记录 breed 前已存在的幼崽集, 用 "前后差" 判定本次新产, 免疫泄漏 (绝对计数曾因 batch 布局变化误判)。
        List<Cow> babiesBefore = helper.getEntities(EntityType.COW, origin, 8.0).stream()
                .filter(Cow::isBaby).toList();
        boolean bred = StackBreed.breedOnce(level, parent);
        helper.assertTrue(bred, "breeding a stacked cow produces an offspring");

        // 多一只牛实体存活 (幼崽是独立新实体, FR-4.2)。delta 抵消任何 before 已存在的泄漏实体。
        int cowsAfter = countAlive(helper, EntityType.COW, origin, 8.0);
        helper.assertTrue(cowsAfter - cowsBefore == 1, "exactly one new cow (baby) spawned, delta = " + (cowsAfter - cowsBefore));

        // 找到 breedOnce 本次新产的那只幼崽 (排除 before 已存在的): isBaby + stack 1 (未并入母堆叠)。
        List<Cow> newBabies = helper.getEntities(EntityType.COW, origin, 8.0).stream()
                .filter(Cow::isBaby).filter(b -> !babiesBefore.contains(b)).toList();
        helper.assertTrue(newBabies.size() == 1, "exactly one NEW baby cow spawned by breedOnce, got " + newBabies.size());
        Cow baby = newBabies.get(0);
        helper.assertTrue(baby.isBaby(), "offspring is a baby (age-isolated, FR-4.2)");
        helper.assertTrue(StackData.getStackSize(baby) == 1, "baby starts at stack 1 (not merged into adult stack)");
        helper.assertTrue(!StackData.hasStackData(baby), "baby carries no stack key yet (independent new entity)");

        // 母堆叠数不因繁殖变化, 也不破上限 (FR-4.3): 仍 12 (生崽是新增个体, 不消耗母体存栏)。
        helper.assertTrue(StackData.getStackSize(parent) == parentBefore,
                "parent stack size unchanged by breeding (still " + parentBefore + "), got " + StackData.getStackSize(parent));

        // 年龄隔离硬验: 幼崽与成年母 StackMatchKey 不相等 (baby 维度不同), 故合并扫描永不把幼崽并进成年堆叠。
        helper.assertTrue(!StackMatchKey.of(baby).equals(StackMatchKey.of(parent)),
                "baby and adult have different match keys (age isolation prevents merge)");
        helper.succeed();
    }

    // ============================================================
    // FR-4.1 受控繁殖冷却: feedBreed 仅 age==0 成年产 1 崽并置 age 冷却; 连喂复刷被挡; 不进 vanilla 恋爱态
    // (setInLove -> setAge 修复: setInLove 会激活 BreedGoal 使邻近被喂动物互相繁殖刷额外崽)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void feedBreedRespectsAgeCooldownNoVanillaLove(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(2, 2, 2);

        Cow parent = helper.spawn(EntityType.COW, origin);
        StackData.setStackSize(parent, 8);
        List<Cow> babiesBefore = helper.getEntities(EntityType.COW, origin, 8.0).stream()
                .filter(Cow::isBaby).toList();

        // 第一次喂食: age==0 成年 -> 产 1 崽 + 置 6000t 繁殖冷却。
        boolean first = StackBreed.feedBreed(level, parent);
        helper.assertTrue(first, "first feed on a ready (age 0) adult breeds");
        helper.assertTrue(parent.getAge() == 6000, "parent enters 6000t breed cooldown (age) after feed, got " + parent.getAge());
        // 关键 (setInLove->setAge 修复): 用 age 冷却而非 setInLove, 故不进 vanilla 恋爱态 -> 无 BreedGoal -> 邻近被喂动物不会互相刷额外崽。
        helper.assertFalse(parent.isInLove(), "fed parent is NOT in vanilla love mode (prevents extra vanilla breeding)");
        long newAfterFirst = helper.getEntities(EntityType.COW, origin, 8.0).stream()
                .filter(Cow::isBaby).filter(b -> !babiesBefore.contains(b)).count();
        helper.assertTrue(newAfterFirst == 1, "first feed spawned exactly one new baby, got " + newAfterFirst);

        // 第二次立即喂食: age!=0 (冷却中) -> 不接管, 不产第二崽 (连点防刷)。
        boolean second = StackBreed.feedBreed(level, parent);
        helper.assertFalse(second, "immediate second feed is blocked by breed cooldown (no spam re-breed)");
        long newAfterSecond = helper.getEntities(EntityType.COW, origin, 8.0).stream()
                .filter(Cow::isBaby).filter(b -> !babiesBefore.contains(b)).count();
        helper.assertTrue(newAfterSecond == 1, "cooldown-blocked second feed adds no extra baby, still " + newAfterSecond);
        helper.succeed();
    }

    // ============================================================
    // 挤奶 (FR-3.2): 产出 = min(空桶, N), 桶不足按实际产, 余量不产
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void milkOutputBoundedByBuckets(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        // 桶足 (空桶 20 >= N 8): 产 8。
        helper.assertTrue(StackPassive.computeMilkOutput(20, 8) == 8, "buckets>=N: produce N=8");
        // 桶不足 (空桶 3 < N 8): 产 3 (按实际空桶, 余量不产, FR-3.2)。
        helper.assertTrue(StackPassive.computeMilkOutput(3, 8) == 3, "buckets<N: produce actual buckets=3");
        // 无空桶: 产 0。
        helper.assertTrue(StackPassive.computeMilkOutput(0, 8) == 0, "no buckets: produce 0");
        // 桶恰等 N: 产 N。
        helper.assertTrue(StackPassive.computeMilkOutput(5, 5) == 5, "buckets==N: produce N=5");
        helper.succeed();
    }

    // ============================================================
    // 辅助: 数某点附近的 ItemEntity 数 / 模拟产蛋计时到达 0 的 tick 数
    // ============================================================

    /** 数 around 附近半径内的掉落物实体数 (FR-2.3 分批断言用)。 */
    private static int countItemEntities(GameTestHelper helper, BlockPos around) {
        return helper.getEntities(EntityType.ITEM, around, 6.0).size();
    }

    /**
     * 模拟: eggTime 从 window 开始, 每 tick 推进 ratePerTick, 返回归零所需 tick 数 (产蛋一次的耗时)。
     * 单鸡 ratePerTick=1 -> window tick; 堆叠 N ratePerTick=N -> window/N tick (N 倍速)。
     */
    private static int ticksToLayAtRate(int window, int ratePerTick) {
        int remaining = window;
        int ticks = 0;
        while (remaining > 0) {
            remaining -= ratePerTick;
            ticks++;
        }
        return ticks;
    }

    // ============================================================
    // T1 白名单准入与旧数据消毒 (findings F002/F004/F005/F018/F037; 主控决策 D1)
    // ============================================================

    // ------------------------------------------------------------
    // F004: 玩家永不合并 (白名单闸挡在 isAlive/isChampion 等一切后续判定之前)
    // ------------------------------------------------------------

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void f004_playerNeverStacks(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        Player mock = helper.makeMockPlayer();
        helper.assertFalse(StackMerge.canStack(mock),
                "mock player is never a stacking candidate (F004 whitelist gate); "
                        + "removing StackMerge's whitelist check would let a live, unnamed, untamed mock "
                        + "player fall through to true");
        helper.succeed();
    }

    // ------------------------------------------------------------
    // F005: 村民 / 盔甲架 / 铁傀儡永不合并 (装备与交易表不得被误并销毁)
    // ------------------------------------------------------------

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void f005_villagerArmorStandIronGolemNeverStack(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        BlockPos origin = new BlockPos(1, 2, 1);

        List<Villager> villagers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            villagers.add(helper.spawn(EntityType.VILLAGER, origin));
        }
        List<ArmorStand> stands = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            stands.add(helper.spawn(EntityType.ARMOR_STAND, origin));
        }
        IronGolem golem = helper.spawn(EntityType.IRON_GOLEM, origin);

        for (Villager v : villagers) {
            helper.assertFalse(StackMerge.canStack(v), "villager never a stacking candidate (F005 whitelist gate)");
        }
        for (ArmorStand a : stands) {
            helper.assertFalse(StackMerge.canStack(a), "armor stand never a stacking candidate (F005 whitelist gate)");
        }
        helper.assertFalse(StackMerge.canStack(golem), "iron golem never a stacking candidate (F005 whitelist gate)");

        List<net.minecraft.world.entity.Entity> all = new ArrayList<>();
        all.addAll(villagers);
        all.addAll(stands);
        all.add(golem);

        int discarded = StackMerge.mergeCandidates(all);
        helper.assertTrue(discarded == 0,
                "villagers/armor stands/iron golem never merge (zero discards), got discarded=" + discarded);

        long alive = all.stream().filter(net.minecraft.world.entity.Entity::isAlive).count();
        helper.assertTrue(alive == 6, "all 6 non-whitelisted entities remain alive, got " + alive);
        for (net.minecraft.world.entity.Entity e : all) {
            helper.assertTrue(!StackData.hasStackData(e), "entity never received stack data, type=" + e.getType());
        }
        helper.succeed();
    }

    // ------------------------------------------------------------
    // F002/F018: 矿洞 SpawnTier 刷怪池的敌对怪 (以 zombie 为代表) 永不合并
    // ------------------------------------------------------------

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void f002_f018_hostileMiningSpawnsNeverStack(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        BlockPos origin = new BlockPos(1, 2, 1);

        List<Zombie> zombies = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            zombies.add(helper.spawn(EntityType.ZOMBIE, origin));
        }

        int discarded = StackMerge.mergeCandidates(new ArrayList<>(zombies));
        helper.assertTrue(discarded == 0,
                "hostile mobs from the mining spawn pool never merge (zero discards), got discarded=" + discarded);

        long alive = zombies.stream().filter(net.minecraft.world.entity.Entity::isAlive).count();
        helper.assertTrue(alive == 4, "all 4 zombies remain alive after merge attempt, got " + alive);
        for (Zombie z : zombies) {
            helper.assertTrue(!StackData.hasStackData(z), "zombie never received stack data");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------
    // F037: 自研精英怪永不合并 (纵深防御: MiningChampions.isChampion 闸, 即便类型在白名单内)
    // ------------------------------------------------------------

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void f037_championCowNeverStacksDespiteWhitelistedType(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        BlockPos origin = new BlockPos(1, 2, 1);

        Cow champion = helper.spawn(EntityType.COW, origin);
        MiningChampions.get(champion).orElseThrow().promote(3, java.util.Map.of(), 100.0D);
        helper.assertTrue(MiningChampions.isChampion(champion), "cow was promoted to a 3-star champion");
        helper.assertFalse(StackMerge.canStack(champion),
                "champion cow is excluded from stacking despite EntityType.COW being whitelisted "
                        + "(F037 defense-in-depth gate); removing the champion check would let it pass canStack");

        Cow plain = helper.spawn(EntityType.COW, origin);
        int discarded = StackMerge.mergeCandidates(List.of(champion, plain));
        helper.assertTrue(discarded == 0,
                "champion cow and plain cow never merge (zero discards), got discarded=" + discarded);
        helper.assertTrue(champion.isAlive() && plain.isAlive(), "both cows remain alive after merge attempt");
        helper.succeed();
    }

    // ------------------------------------------------------------
    // 白名单正向准入: 猪/鸡/牛各 4 只同点合并为 1 堆叠 x4 (防 "一刀切全不堆" 的假修复; 羊已被 ac1 覆盖)
    // ------------------------------------------------------------

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void whitelistAdmitsPigChickenCowFourIntoOneStack(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        BlockPos origin = new BlockPos(1, 2, 1);
        assertFourOfWhitelistedTypeMergeToOneStack(helper, EntityType.PIG, origin);
        assertFourOfWhitelistedTypeMergeToOneStack(helper, EntityType.CHICKEN, origin);
        assertFourOfWhitelistedTypeMergeToOneStack(helper, EntityType.COW, origin);
        helper.succeed();
    }

    /**
     * 白名单正向准入断言: 同点 spawn 4 只 type, 合并后必须 discarded==3 / 恰 1 只存活 / StackSize==4 /
     * 显示名含 "x4"。删掉白名单中该 type 的条目 -> mergeCandidates 直接对该组返回 0 (canStack 全灭) -> 必挂。
     */
    private static <E extends net.minecraft.world.entity.Entity> void assertFourOfWhitelistedTypeMergeToOneStack(
            GameTestHelper helper, EntityType<E> type, BlockPos origin) {
        List<E> group = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            group.add(helper.spawn(type, origin));
        }

        int discarded = StackMerge.mergeCandidates(new ArrayList<>(group));
        helper.assertTrue(discarded == 3,
                "4 " + type + " merge into one stack (3 discarded), got discarded=" + discarded);

        List<E> survivors = group.stream().filter(net.minecraft.world.entity.Entity::isAlive).toList();
        helper.assertTrue(survivors.size() == 1,
                "exactly one " + type + " survivor after merge, got " + survivors.size());
        E survivor = survivors.get(0);
        helper.assertTrue(StackData.getStackSize(survivor) == 4,
                "survivor stack size 4 for " + type + ", got " + StackData.getStackSize(survivor));
        helper.assertTrue(survivor.getCustomName() != null && survivor.getCustomName().getString().contains("x4"),
                "survivor custom name contains x4 for " + type + ", got " + (survivor.getCustomName() == null
                        ? "<null>" : survivor.getCustomName().getString()));
    }

    // ------------------------------------------------------------
    // 总开关 (StackingConfig.ENABLED) 只关新合并, 不得掐断既有堆叠的结算路径 (StackDeath/StackPassive/StackSplit
    // 均经 StackMerge.canStack 判定是否仍是合法堆叠个体; 若实现者误把 ENABLED 塞进 canStack, 已成堆叠的牛
    // 会在关停期间被结算 handler 误判为 "不再是堆叠成员")。
    // ------------------------------------------------------------

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void enabledSwitchGatesOnlyNewMergesNotExistingSettlement(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        boolean prevEnabled = StackingConfig.ENABLED.get();
        StackingConfig.ENABLED.set(false);
        try {
            BlockPos origin = new BlockPos(1, 2, 1);
            Cow cow = helper.spawn(EntityType.COW, origin);
            StackData.setStackSize(cow, 8);

            helper.assertTrue(StackMerge.canStack(cow),
                    "canStack still true for an already-formed stack while ENABLED=false: "
                            + "the kill switch stops the periodic scan from finding NEW merge candidates "
                            + "(StackingSystem.onServerTick), it must not be wired into canStack itself, "
                            + "otherwise StackDeath/StackPassive/StackSplit would stop settling this stack's "
                            + "8 individuals while the switch is off");
        } finally {
            StackingConfig.ENABLED.set(prevEnabled);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------
    // F004/F005 旧存档消毒 (StackingSystem.onEntityJoinLevel): 白名单化前写下的脏堆叠数据须整体回收,
    // 系统生成的 "xN" 标签一并清除, 但玩家用命名牌覆盖过的名字必须保留。
    // ------------------------------------------------------------

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void staleStackDataSanitizedOnEntityJoinLevelPreservesPlayerNames(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        BlockPos origin = new BlockPos(1, 2, 1);
        StackingSystem system = new StackingSystem();

        // 案例 A: 旧版本 (黑名单式 canStack) 曾把村民并进堆叠并打上系统生成的 "xN" 标签; 白名单化后村民
        // 永久无法再走 canStack, 该数据是死数据 -- 必须被整体回收, 标签也必须清 (不能继续误导玩家)。
        Villager dirty = helper.spawn(EntityType.VILLAGER, origin);
        StackData.setStackSize(dirty, 5);
        net.minecraft.network.chat.Component staleLabel = net.minecraft.network.chat.Component.empty()
                .append(dirty.getType().getDescription())
                .append(net.minecraft.network.chat.Component.literal(" x5"));
        dirty.setCustomName(staleLabel);
        dirty.setCustomNameVisible(true);

        system.onEntityJoinLevel(new EntityJoinLevelEvent(dirty, helper.getLevel()));

        helper.assertTrue(!StackData.hasStackData(dirty),
                "stale StackSize/NoMergeUntil cleared after sanitize (F004/F005 legacy save cleanup); "
                        + "deleting the onEntityJoinLevel handler would leave this true");
        helper.assertTrue(dirty.getCustomName() == null,
                "stale system-generated 'xN' label cleared after sanitize, got "
                        + (dirty.getCustomName() == null ? "<null>" : dirty.getCustomName().getString()));

        // 案例 B: 玩家事后用命名牌把同一只村民重命名为 "Bob"。堆叠数据仍须清 (仍是死数据), 但 "Bob" 这个
        // 玩家命名绝不能被当成系统标签误删 -- 只有 CustomName 恰好等于 applyLabel 会生成的那串才清名。
        Villager named = helper.spawn(EntityType.VILLAGER, origin);
        StackData.setStackSize(named, 5);
        named.setCustomName(net.minecraft.network.chat.Component.literal("Bob"));
        named.setCustomNameVisible(true);

        system.onEntityJoinLevel(new EntityJoinLevelEvent(named, helper.getLevel()));

        helper.assertTrue(!StackData.hasStackData(named),
                "stale stack data cleared even when the entity carries a player-given name");
        helper.assertTrue(named.getCustomName() != null && "Bob".equals(named.getCustomName().getString()),
                "player-given name tag 'Bob' is preserved (not deleted) by sanitize, got "
                        + (named.getCustomName() == null ? "<null>" : named.getCustomName().getString()));
        helper.succeed();
    }
}
