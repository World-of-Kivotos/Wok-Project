package com.miningdim.stacking;

import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 实体堆叠 拆分 / 拴绳 / 剪毛恢复 GameTest (F066 拆分与拴绳语义; F095 剪毛按 N 个体结算; FR-1.1 已剪态等价键)。
 *
 * 范式与 {@link StackingGameTests} 完全一致 (类上 {@code @GameTestHolder} + {@code @PrefixGameTestTemplate(false)},
 * 每方法首行 {@link StackingConfig#ensureLoadedForTest}, template = "empty", batch = "stacking"), 避免 Forge 按
 * structure namespace 过滤导致的假绿陷阱。
 *
 * 本文件只驱动 {@link StackSplit} / {@link StackPassive} 的纯逻辑与直接方法调用 (不依赖 tick 调度或
 * {@code PlayerInteractEvent} 事件总线), 与 {@link StackingGameTests} 分工互不重叠, 断言均落到具体堆叠数 /
 * 实体存活 delta / 布尔状态 / 恢复账推进次数, 无一为 {@code assertTrue(x != null)} 一类永真弱校验。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class StackingInteractionGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "stacking";

    /** 统计某维度内某 EntityType 的存活实体数 (delta 计数用, 抄 {@link StackingGameTests#countAlive})。 */
    private static <E extends Entity> int countAlive(
            GameTestHelper helper, EntityType<E> type, BlockPos around, double radius) {
        return helper.getEntities(type, around, radius).size();
    }

    /** 统计半径内某物品的落地总数 (跨多个 ItemEntity 求和), 供掉落断言用。 */
    private static int countItemsOfType(GameTestHelper helper, net.minecraft.world.item.Item item, BlockPos around) {
        int total = 0;
        for (ItemEntity itemEntity : helper.getEntities(EntityType.ITEM, around, 6.0)) {
            if (itemEntity.getItem().is(item)) {
                total += itemEntity.getItem().getCount();
            }
        }
        return total;
    }

    // ============================================================
    // F066 / FR-5.1: 拆分从堆叠数 N>1 剥离 1 个独立个体, 源堆叠 -1 且标签刷新, 附近实体数 +1
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void f066SplitOnePeelsSingleIndividual(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(1, 2, 1);

        Cow cow = helper.spawn(EntityType.COW, origin);
        StackData.setStackSize(cow, 8);
        int cowsBefore = countAlive(helper, EntityType.COW, origin, 8.0);

        Animal newCow = StackSplit.splitOne(level, cow);

        // (a) 返回的新牛 isAlive 且 StackSize == 1。删 setStackSize(single,1) 或让新个体不落地则必挂。
        helper.assertTrue(newCow.isAlive(), "split-off individual is alive");
        helper.assertTrue(StackData.getStackSize(newCow) == 1,
                "split-off individual stack size is 1, got " + StackData.getStackSize(newCow));

        // (b) 源牛 StackSize == 7。删 incr(source, -1) 则源仍是 8, 必挂。
        helper.assertTrue(StackData.getStackSize(cow) == 7,
                "source stack decremented to 7, got " + StackData.getStackSize(cow));

        // (c) 源牛显示名含 "x7"。删 applyLabel(source) 则名仍是旧的 "x8", 必挂。
        helper.assertTrue(cow.getCustomName() != null && cow.getCustomName().getString().contains("x7"),
                "source label refreshed to x7, got "
                        + (cow.getCustomName() == null ? "<null>" : cow.getCustomName().getString()));

        // (d) 附近牛实体数恰好 +1 (delta 计数, 免疫相邻测试区漂移)。
        int cowsAfter = countAlive(helper, EntityType.COW, origin, 8.0);
        helper.assertTrue(cowsAfter - cowsBefore == 1,
                "exactly one new cow entity appears near origin, delta = " + (cowsAfter - cowsBefore));
        helper.succeed();
    }

    // ============================================================
    // F066: 拆出的个体带拆分保护期, 不被下一轮扫描立刻吸回; 保护期清零后恢复正常合并语义
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void f066SplitGraceBlocksImmediateReabsorption(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(1, 2, 1);

        Cow cow = helper.spawn(EntityType.COW, origin);
        StackData.setStackSize(cow, 8);
        Animal newCow = StackSplit.splitOne(level, cow);

        // 保护期内: mergeCandidates 必不吸收刚拆出的个体。删 getNoMergeUntil 保护期判定则必挂 (discarded 变 1)。
        int discardedDuringGrace = StackMerge.mergeCandidates(List.of(cow, newCow));
        helper.assertTrue(discardedDuringGrace == 0,
                "grace period blocks the immediate re-merge pass, discarded = " + discardedDuringGrace);
        helper.assertTrue(newCow.isAlive(), "split-off individual survives the merge pass during its grace period");
        helper.assertTrue(StackData.getStackSize(newCow) == 1,
                "split-off individual stays at stack size 1 during its grace period");

        // 保护期清零后: 恢复正常合并语义 (证明不是 "永久不可合并" 蒙混过关)。
        StackData.setNoMergeUntil(newCow, 0);
        int discardedAfterGrace = StackMerge.mergeCandidates(List.of(cow, newCow));
        helper.assertTrue(discardedAfterGrace == 1,
                "after the grace period elapses, normal merge semantics resume, discarded = " + discardedAfterGrace);
        helper.succeed();
    }

    // ============================================================
    // FR-5.1: 拆分保真 —— 羊的色/已剪态与年龄段全部被复制到新个体
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fr51SplitPreservesVariantAndAgeState(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(1, 2, 1);

        Sheep sheep = helper.spawn(EntityType.SHEEP, origin);
        sheep.setColor(DyeColor.RED);
        sheep.setSheared(true);
        StackData.setStackSize(sheep, 5);

        Animal splitAnimal = StackSplit.splitOne(level, sheep);
        helper.assertTrue(splitAnimal instanceof Sheep, "split-off individual from a sheep stack is a Sheep");
        Sheep splitSheep = (Sheep) splitAnimal;

        // 删羊状态拷贝 (out.setColor/out.setSheared) -> 变白羊/带毛羊, 必挂。
        helper.assertTrue(splitSheep.getColor() == DyeColor.RED,
                "split-off sheep keeps wool color RED, got " + splitSheep.getColor());
        helper.assertTrue(splitSheep.isSheared(), "split-off sheep keeps sheared=true");
        helper.assertTrue(!splitSheep.isBaby(), "split-off sheep from an adult source is not a baby");

        Cow babyParent = helper.spawn(EntityType.COW, origin);
        babyParent.setBaby(true);
        StackData.setStackSize(babyParent, 4);
        Animal splitBaby = StackSplit.splitOne(level, babyParent);

        // 删 single.setAge(source.getAge()) -> 幼崽拆出后变成年, 必挂。
        helper.assertTrue(splitBaby.isBaby(), "split-off individual from a baby source is also a baby");
        helper.succeed();
    }

    // ============================================================
    // F066 / FR-5.2: 拴绳两种语义 —— SPLIT_ONE 拆出并单独拴住 1 只; WHOLE_STACK 放行原版整堆语义
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fr52LeashModeSplitOneVsWholeStack(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(1, 2, 1);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        StackingConfig.LeashMode prevMode = StackingConfig.LEASH_MODE.get();

        // ---- SPLIT_ONE: handleLead 接管, 拆出 1 只并拴住它, 源堆叠 -1 ----
        StackingConfig.LEASH_MODE.set(StackingConfig.LeashMode.SPLIT_ONE);
        try {
            Cow cow = helper.spawn(EntityType.COW, origin);
            StackData.setStackSize(cow, 6);
            List<Cow> cowsBefore = helper.getEntities(EntityType.COW, origin, 8.0);

            boolean handled = StackSplit.handleLead(level, player, cow);
            helper.assertTrue(handled, "SPLIT_ONE leash mode: handleLead reports it took over the interaction");
            helper.assertTrue(StackData.getStackSize(cow) == 5,
                    "source stack decremented to 5 after SPLIT_ONE lead, got " + StackData.getStackSize(cow));

            // 附近牛数恰好 +1, 用前后 delta/排除法定位新拆出的那只 (handleLead 只返回 boolean, 不返回实体)。
            List<Cow> cowsAfter = helper.getEntities(EntityType.COW, origin, 8.0);
            helper.assertTrue(cowsAfter.size() - cowsBefore.size() == 1,
                    "SPLIT_ONE lead spawns exactly one new cow, delta = " + (cowsAfter.size() - cowsBefore.size()));
            List<Cow> newCows = cowsAfter.stream().filter(c -> !cowsBefore.contains(c)).toList();
            helper.assertTrue(newCows.size() == 1,
                    "exactly one NEW cow identified via before/after exclusion, got " + newCows.size());
            Cow leashed = newCows.get(0);

            helper.assertTrue(leashed.isLeashed(), "split-off individual is leashed");
            helper.assertTrue(leashed.getLeashHolder() == player,
                    "split-off individual's leash holder is the interacting player");
            // 拴住的个体退出合并候选 (StackMerge.canMerge 的 isLeashed 闸), 否则会被吸回堆叠; 但它仍是合法堆叠
            // 个体 (findings 1/3/5 回归修复: isLeashed 不得混进 canStack, 否则死亡/被动产出/拆分结算会被误判为
            // "不再是堆叠成员")。
            helper.assertTrue(!StackMerge.canMerge(leashed),
                    "leashed split-off individual is excluded from further merge candidacy");
            helper.assertTrue(StackMerge.canStack(leashed),
                    "leashed individual remains a legitimate stack member for settlement purposes (canStack)");
        } finally {
            StackingConfig.LEASH_MODE.set(prevMode);
        }

        // ---- WHOLE_STACK: handleLead 不接管, 放行原版整堆拴绳, 源堆叠不变, 不产生新实体 ----
        StackingConfig.LEASH_MODE.set(StackingConfig.LeashMode.WHOLE_STACK);
        try {
            Cow cow2 = helper.spawn(EntityType.COW, origin);
            StackData.setStackSize(cow2, 6);
            int cowsBefore2 = countAlive(helper, EntityType.COW, origin, 8.0);

            boolean handled2 = StackSplit.handleLead(level, player, cow2);
            helper.assertFalse(handled2,
                    "WHOLE_STACK leash mode: handleLead defers to vanilla whole-stack leashing");
            helper.assertTrue(StackData.getStackSize(cow2) == 6,
                    "WHOLE_STACK mode leaves source stack size unchanged at 6, got " + StackData.getStackSize(cow2));

            int cowsAfter2 = countAlive(helper, EntityType.COW, origin, 8.0);
            helper.assertTrue(cowsAfter2 - cowsBefore2 == 0,
                    "WHOLE_STACK mode spawns no new cow entity, delta = " + (cowsAfter2 - cowsBefore2));
        } finally {
            StackingConfig.LEASH_MODE.set(prevMode);
        }
        helper.succeed();
    }

    // ============================================================
    // F095: 剪毛恢复按 N 个体结算 —— 需要恰好 N 次独立吃草整堆才重新可剪, 非固定 tick 冷却/一次回满
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void f095ShearRegrowSettlesPerIndividual(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        BlockPos origin = new BlockPos(2, 2, 2);

        Sheep sheep = helper.spawn(EntityType.SHEEP, origin);
        sheep.setColor(DyeColor.WHITE);
        sheep.setSheared(false);
        StackData.setStackSize(sheep, 16);

        helper.assertTrue(sheep.readyForShearing(), "unsheared adult stack is ready for shearing before marking");

        StackPassive.markStackSheared(sheep, 16);
        helper.assertTrue(sheep.isSheared(), "stack marked sheared after markStackSheared(sheep, 16)");
        helper.assertTrue(!sheep.readyForShearing(), "sheared stack is not ready for re-shearing right after marking");

        // 第 1 次模拟吃草: regrowTick 消耗 1 笔恢复账, 但整堆 (16 只里的 15 只仍待长毛) 仍不可剪。
        // 删 F095 修复 (即恢复成只有一句 setSheared(true)) -> 一次吃草即整堆可剪, 本断言必挂。
        sheep.setSheared(false);
        boolean firstConsumed = StackPassive.regrowTick(sheep);
        helper.assertTrue(firstConsumed, "1st simulated grass-eating consumes one regrow ledger entry, returns true");
        helper.assertTrue(sheep.isSheared(), "after 1/16 regrowth ticks, the 16-strong stack is still shown sheared");
        helper.assertTrue(!sheep.readyForShearing(), "after 1/16 regrowth ticks, stack is not yet ready for shearing");

        // 第 2..15 次模拟吃草 (共 14 次), 每次仍不可剪。
        for (int i = 2; i <= 15; i++) {
            sheep.setSheared(false);
            boolean consumed = StackPassive.regrowTick(sheep);
            helper.assertTrue(consumed, "grass-eating #" + i + " consumes a regrow ledger entry, returns true");
            helper.assertTrue(!sheep.readyForShearing(), "after " + i + "/16 regrowth ticks, still not shearable");
        }

        // 第 16 次: 恰好走完整个恢复账, 整堆恢复可剪 (不多不少 16 次)。
        sheep.setSheared(false);
        boolean lastConsumed = StackPassive.regrowTick(sheep);
        helper.assertTrue(lastConsumed, "16th grass-eating consumes the final regrow ledger entry, returns true");
        helper.assertTrue(!sheep.isSheared(), "after exactly 16/16 regrowth ticks, sheared flips back to false");
        helper.assertTrue(sheep.readyForShearing(), "after exactly 16/16 regrowth ticks, stack is shearable again");

        // 兜底补记分支: 发射器/其它 mod 剪毛不经 markStackSheared, regrowTick 首次调用须按当前 stackSize=8 补记
        // 恢复账, 不留 "一次吃草回满" 的捷径。删补记分支 -> 单次吃草即整堆恢复, 必挂。
        Sheep externallySheared = helper.spawn(EntityType.SHEEP, origin.offset(3, 0, 0));
        externallySheared.setColor(DyeColor.WHITE);
        externallySheared.setSheared(true);
        StackData.setStackSize(externallySheared, 8);

        StackPassive.regrowTick(externallySheared); // 首次调用: 补记 8 笔恢复账, 不消耗、不清 sheared。
        externallySheared.setSheared(false);
        boolean fallbackConsumed = StackPassive.regrowTick(externallySheared);
        helper.assertTrue(fallbackConsumed, "fallback ledger consumes one entry after backfilling, returns true");
        helper.assertTrue(externallySheared.isSheared(),
                "after only 1/8 regrowth ticks the backfilled 8-strong stack is put back to sheared=true");
        helper.succeed();
    }

    // ============================================================
    // FR-1.1: 已剪态是等价键的一个维度 —— 已剪羊与带毛羊分堆, 各自堆叠数守恒
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fr11ShearedStateIsPartOfMatchKey(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        BlockPos origin = new BlockPos(1, 2, 1);

        List<Sheep> all = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Sheep s = helper.spawn(EntityType.SHEEP, origin);
            s.setColor(DyeColor.WHITE);
            s.setSheared(true);
            all.add(s);
        }
        for (int i = 0; i < 2; i++) {
            Sheep s = helper.spawn(EntityType.SHEEP, origin);
            s.setColor(DyeColor.WHITE);
            s.setSheared(false);
            all.add(s);
        }

        StackMerge.mergeCandidates(new ArrayList<>(all));

        // 删 StackMatchKey.variantSignature 的 sheared 维度 -> 5 只并成 1 只, 必挂。
        List<Sheep> survivors = all.stream().filter(Entity::isAlive).toList();
        helper.assertTrue(survivors.size() == 2,
                "sheared-state isolation: two stacks remain (sheared + woolly), got " + survivors.size());

        int shearedStack = survivors.stream().filter(Sheep::isSheared).mapToInt(StackData::getStackSize).sum();
        int woollyStack = survivors.stream().filter(s -> !s.isSheared()).mapToInt(StackData::getStackSize).sum();
        helper.assertTrue(shearedStack == 3, "sheared stack size 3, got " + shearedStack);
        helper.assertTrue(woollyStack == 2, "woolly stack size 2, got " + woollyStack);
        helper.succeed();
    }

    // ============================================================
    // findings 1/3/5 (三独立复核者共同指出的回归): 被拴住的堆叠死亡时仍必须走满 FR-2 倍增结算 —— isLeashed 只应
    // 挡合并候选 (canMerge), 绝不能挡结算准入 (canStack), 否则整堆战利品被静默丢弃
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void leashedStackStillSettlesDeathDropsOnKill(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(1, 2, 1);
        ServerPlayer leashHolder = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        StackingConfig.DeathMode prevMode = StackingConfig.DROPS_DEATH_MODE.get();
        StackingConfig.DROPS_DEATH_MODE.set(StackingConfig.DeathMode.INSTANT_ALL);
        try {
            Cow cow = helper.spawn(EntityType.COW, origin);
            StackData.setStackSize(cow, 5);

            // 模拟 interaction.leashMode=WHOLE_STACK 下原版把整堆拴住 (StackSplit.handleLead 对该模式 return
            // false, 放行原版 Mob.mobInteract 执行的正是 setLeashedTo 这一步)。
            cow.setLeashedTo(leashHolder, true);
            helper.assertTrue(cow.isLeashed(), "cow is leashed, simulating a WHOLE_STACK lead interaction");

            // 核心断言: canStack (结算侧判据) 对被拴住的堆叠仍为 true; 只有 canMerge (合并候选判据) 才因
            // isLeashed 排除。若 isLeashed 仍混在 canStack 里, 下面的 onLivingDeath 会在准入判据处直接 return,
            // 补掉落全部为 0。
            helper.assertTrue(StackMerge.canStack(cow),
                    "leashed 5-strong stack remains a legitimate stack member for settlement (canStack)");
            helper.assertTrue(!StackMerge.canMerge(cow),
                    "leashed stack is excluded from merge candidacy (canMerge)");

            int beefBefore = countItemsOfType(helper, Items.BEEF, origin);

            // 直接构造并驱动 onLivingDeath (与 StackDeath 类文档一致的 "纯核心/handler 分离" 测试方式)。
            // source 用 generic (非玩家击杀): 只验证补掉落是否发生, 不涉及经验 (FR-2.6 环境致死不掉经验, 与本例
            // 的断言目标无关)。
            LivingDeathEvent event = new LivingDeathEvent(cow, level.damageSources().generic());
            new StackDeath().onLivingDeath(event);

            int beefAfter = countItemsOfType(helper, Items.BEEF, origin);
            helper.assertTrue(beefAfter > beefBefore,
                    "leashed stack death still rolls extra drops for the other 4 virtual individuals (beef before="
                            + beefBefore + ", after=" + beefAfter + "); a regression (isLeashed folded back into "
                            + "canStack) would leave this count unchanged because onLivingDeath returns before "
                            + "rollStackedLoot ever runs");
        } finally {
            StackingConfig.DROPS_DEATH_MODE.set(prevMode);
        }
        helper.succeed();
    }

    // ============================================================
    // findings 2/4/6 (三独立复核者共同指出的回归): 两个已剪堆叠合并后, 恢复账 (REGROW_KEY) 必须按比例搬到幸存者
    // 身上求和, 不得随被并方 discard 一起丢弃 —— 否则堆叠越合越大, 免冷却二次剪毛的漏洞按合并次数复活
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mergeCarriesShearRegrowLedgerAcrossMerge(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        BlockPos origin = new BlockPos(1, 2, 1);

        // 两堆各 8 只白羊, 各自独立剪毛后开出各自的 8 笔恢复账, 再靠近合并 (牧场常见操作: 分摊剪毛再自然聚拢)。
        Sheep stackA = helper.spawn(EntityType.SHEEP, origin);
        stackA.setColor(DyeColor.WHITE);
        StackData.setStackSize(stackA, 8);
        StackPassive.markStackSheared(stackA, 8);

        Sheep stackB = helper.spawn(EntityType.SHEEP, origin);
        stackB.setColor(DyeColor.WHITE);
        StackData.setStackSize(stackB, 8);
        StackPassive.markStackSheared(stackB, 8);

        int discarded = StackMerge.mergeCandidates(List.of(stackA, stackB));
        helper.assertTrue(discarded == 1, "two 8-strong sheared stacks merge into one, discarded=" + discarded);

        Sheep survivor = List.of(stackA, stackB).stream().filter(Entity::isAlive).findFirst().orElseThrow();
        helper.assertTrue(StackData.getStackSize(survivor) == 16,
                "merged survivor stack size is 16, got " + StackData.getStackSize(survivor));

        // 核心断言: 恢复账必须是 16 笔, 不是被并方那 8 笔丢失后只剩的 8 笔。删 StackMerge -> mergeRegrowLedger
        // 的调用即退回旧漏洞: 第 9 次吃草时 readyForShearing() 会提前翻 true (16 只堆叠只需 8 次吃草整堆回满,
        // 免冷却按半价复活), 下面循环第 9 轮的断言必挂。
        for (int i = 1; i <= 15; i++) {
            survivor.setSheared(false);
            boolean consumed = StackPassive.regrowTick(survivor);
            helper.assertTrue(consumed, "grass-eating #" + i + " of 16 (merged stack) consumes a ledger entry");
            helper.assertTrue(!survivor.readyForShearing(),
                    "after " + i + "/16 regrowth ticks on the merged 16-strong stack, must NOT be shearable yet "
                            + "(shearable here means the absorbed party's regrow ledger was silently dropped)");
        }
        survivor.setSheared(false);
        boolean lastConsumed = StackPassive.regrowTick(survivor);
        helper.assertTrue(lastConsumed, "16th grass-eating consumes the final regrow ledger entry");
        helper.assertTrue(survivor.readyForShearing(), "after exactly 16/16 regrowth ticks, stack is shearable again");
        helper.succeed();
    }

    // ============================================================
    // findings 8: 玩家给拆分出的个体命名后仍必须被排除 —— 命名闸须靠 "CustomName 是否等于系统自打标签" 判定,
    // 不能靠 StackData.hasStackData (splitOne 会给拆出个体写 StackSize=1, 这是合法数据但与 "被系统命名" 无关)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void namedSplitOffIndividualStaysExcludedFromMerging(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(1, 2, 1);

        Cow cow = helper.spawn(EntityType.COW, origin);
        StackData.setStackSize(cow, 6);
        Animal split = StackSplit.splitOne(level, cow); // source 6 -> 5; split 带 StackSize=1 + 拆分保护期。
        split.setCustomName(net.minecraft.network.chat.Component.literal("Bessie"));

        // 保护期一过 (FR-5.1) 后命名闸必须仍然生效。删 StackMerge.isSelfAppliedLabel 或退回旧的
        // "!StackData.hasStackData" 判据都会让这里的 canStack 变 true, 必挂。
        StackData.setNoMergeUntil(split, 0);
        helper.assertTrue(!StackMerge.canStack(split),
                "named split-off individual is excluded from stacking despite carrying StackData (StackSize=1 "
                        + "from splitOne); a regression here means the naming gate is keyed off hasStackData "
                        + "instead of comparing the CustomName against the system's own applied label");

        Cow plain = helper.spawn(EntityType.COW, origin);
        StackData.setStackSize(plain, 3);
        int discarded = StackMerge.mergeCandidates(List.of(split, cow, plain));
        helper.assertTrue(discarded == 1,
                "only the two unnamed cow stacks merge (named individual stays out of the candidate pool "
                        + "entirely), discarded=" + discarded);
        helper.assertTrue(split.isAlive(), "named split-off individual survives the merge pass");
        helper.assertTrue(split.getCustomName() != null && "Bessie".equals(split.getCustomName().getString()),
                "named split-off individual keeps its player-given name, got "
                        + (split.getCustomName() == null ? "<null>" : split.getCustomName().getString()));
        helper.assertTrue(StackData.getStackSize(split) == 1,
                "named split-off individual's stack size is untouched at 1, got " + StackData.getStackSize(split));
        helper.succeed();
    }

    // ============================================================
    // findings 9: 带鞍猪不参与堆叠 —— discard() (合并对被并方的操作) 不像原版 die() 那样触发 dropEquipment,
    // 若不排除, 被并方的鞍会随 discard 直接从世界上消失
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void saddledPigExcludedFromStacking(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        BlockPos origin = new BlockPos(1, 2, 1);

        Pig saddled = helper.spawn(EntityType.PIG, origin);
        saddled.equipSaddle(null);
        helper.assertTrue(saddled.isSaddled(), "pig carries a saddle before the merge attempt");
        helper.assertTrue(!StackMerge.canStack(saddled),
                "saddled pig is excluded from stacking (findings 9); a regression here would let it be merged "
                        + "and discard()-ed without ever dropping its saddle, since discard() does not run "
                        + "vanilla dropEquipment the way die() does");

        Pig plain = helper.spawn(EntityType.PIG, origin);
        int discarded = StackMerge.mergeCandidates(List.of(saddled, plain));
        helper.assertTrue(discarded == 0,
                "saddled pig never merges (zero discards; the lone plain pig has no other candidate to merge "
                        + "with), got discarded=" + discarded);
        helper.assertTrue(saddled.isAlive() && saddled.isSaddled(), "saddled pig survives with its saddle intact");
        helper.assertTrue(!StackData.hasStackData(saddled), "saddled pig never receives stack data");
        helper.succeed();
    }
}
