package com.miningdim.trap;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.miner.ChainMiningEngine;
import com.miningdim.job.miner.MinerSurvival;
import com.miningdim.job.miner.TrapScanService;
import com.miningdim.registry.ModBlocks;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.trap.block.TrapOreBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 静态陷阱协议级伪装契约 GameTest。用户裁决后世界里已无可被识破的 trap_ore —— 陷阱身份只存 {@link TrapRegistry},
 * 触发/探测/连锁一律查注册表。断言具体业务结果 (删被测逻辑必挂, 禁弱校验): 注册表 CRUD + 序列化往返 + 分 chunk
 * nearby / 触发取消并调度且幽灵条目守卫 / 探测按等级揭示且只揭示下发的 / 连锁对已揭示跳过对未揭示触发且不计产出 /
 * damageTypeFor 与矿脉抗性联动。纯逻辑 + 真实 ServerLevel 直读, 不依赖外部 mod。真实爆炸/岩浆/落石世界写属 vanilla
 * 机制, 需 runClient/真服观测, 不在 dev GameTest 断言范围。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class TrapGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "trap";

    // ============================================================
    // 注册表: CRUD + nearby (分 chunk / 多 chunk / 边界半径) + 序列化往返
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void trapRegistryCrudNearbyAndSerializationRoundTrip(GameTestHelper helper) {
        // 独立实例 (不碰 level 共享 SavedData), 纯逻辑断言。
        TrapRegistry reg = new TrapRegistry();
        // 同 chunk (0,0) 两格 + 相邻 chunk (0,1) 一格 + 远 chunk (0,4) 一格 (含负 y 打包)。
        BlockPos a = new BlockPos(5, 20, 5);        // chunk (0,0)
        BlockPos aSibling = new BlockPos(6, -30, 5); // chunk (0,0), 负 y
        BlockPos b = new BlockPos(5, 20, 21);       // chunk (0,1)
        BlockPos far = new BlockPos(5, 20, 69);     // chunk (0,4)
        reg.put(a, StaticTrapKind.TNT_VEIN);
        reg.put(aSibling, StaticTrapKind.COLLAPSING_TUNNEL);
        reg.put(b, StaticTrapKind.FAKE_ORE);
        reg.put(far, StaticTrapKind.LAVA_POCKET);

        // get 命中/未命中。
        helper.assertTrue(reg.get(a) == StaticTrapKind.TNT_VEIN, "get(a) == TNT_VEIN");
        helper.assertTrue(reg.get(aSibling) == StaticTrapKind.COLLAPSING_TUNNEL, "get(aSibling) == COLLAPSING_TUNNEL");
        helper.assertTrue(reg.get(b) == StaticTrapKind.FAKE_ORE, "get(b) == FAKE_ORE");
        helper.assertTrue(reg.get(new BlockPos(999, 0, 999)) == null, "get(miss) == null");

        // remove 只删该格, 同 chunk 兄弟存活。
        reg.remove(a);
        helper.assertTrue(reg.get(a) == null, "after remove(a) get(a) == null");
        helper.assertTrue(reg.get(aSibling) == StaticTrapKind.COLLAPSING_TUNNEL, "same-chunk sibling survives remove(a)");

        // nearby 多 chunk: 中心 (5,20,13) 半径 12 覆盖 chunk (0,0)+(0,1), 收 aSibling+b, 不收 far。
        List<BlockPos> nearMulti = positions(reg.nearby(new BlockPos(5, 20, 13), 12));
        helper.assertTrue(nearMulti.size() == 2, "nearby(r=12) spans 2 chunks -> 2 entries, got " + nearMulti.size());
        helper.assertTrue(nearMulti.contains(aSibling) && nearMulti.contains(b), "nearby(r=12) has aSibling + b");
        helper.assertFalse(nearMulti.contains(far), "nearby(r=12) excludes far chunk (0,4)");

        // nearby 边界半径: 中心 (5,20,5) 半径 4 只落 chunk (0,0), 仅收 aSibling (a 已删)。
        List<BlockPos> nearOne = positions(reg.nearby(new BlockPos(5, 20, 5), 4));
        helper.assertTrue(nearOne.size() == 1 && nearOne.contains(aSibling),
                "nearby(r=4) single chunk -> only aSibling, got " + nearOne.size());

        // 序列化往返: save -> load 后陷阱身份逐条相等 (含负 y 打包 / 多 chunk)。
        CompoundTag tag = reg.save(new CompoundTag());
        TrapRegistry loaded = TrapRegistry.load(tag);
        helper.assertTrue(loaded.get(aSibling) == StaticTrapKind.COLLAPSING_TUNNEL, "round-trip aSibling kind");
        helper.assertTrue(loaded.get(b) == StaticTrapKind.FAKE_ORE, "round-trip b kind");
        helper.assertTrue(loaded.get(far) == StaticTrapKind.LAVA_POCKET, "round-trip far kind (chunk 0,4)");
        helper.assertTrue(loaded.get(a) == null, "round-trip: removed a stays absent");
        helper.succeed();
    }

    // ============================================================
    // 触发: 注册表命中 -> 取消 + 移除 + 调度; 幽灵条目守卫 -> 清条目, 不调度
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void trapTriggerCancelsRemovesAndSchedules(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TrapRegistry reg = TrapRegistry.get(level);
        // 各测试用自身结构区绝对坐标, 天然与其它测试隔离。
        BlockPos hit = helper.absolutePos(new BlockPos(1, 1, 0));
        BlockState coal = Blocks.COAL_ORE.defaultBlockState();
        helper.setBlock(new BlockPos(1, 1, 0), Blocks.COAL_ORE);
        reg.put(hit, StaticTrapKind.FAKE_ORE);

        int before = TrapSystem.get().pendingDelayedTaskCount();
        boolean triggered = StaticTrapTrigger.tryTriggerTrap(level, hit, coal);
        helper.assertTrue(triggered, "registry hit on a disguise ore -> tryTriggerTrap returns true (caller cancels break)");
        helper.assertTrue(reg.get(hit) == null, "triggered trap entry removed from registry");
        helper.assertTrue(TrapSystem.get().pendingDelayedTaskCount() == before + 1,
                "one reaction-window task scheduled on trigger");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void trapTriggerGhostGuardClearsEntryWithoutScheduling(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TrapRegistry reg = TrapRegistry.get(level);
        BlockPos ghost = helper.absolutePos(new BlockPos(2, 1, 0));
        // 注册表还记着陷阱, 但该坐标方块是石头 (非伪装矿石族) -> 幽灵条目。
        reg.put(ghost, StaticTrapKind.TNT_VEIN);
        BlockState stone = Blocks.STONE.defaultBlockState();

        int before = TrapSystem.get().pendingDelayedTaskCount();
        boolean triggered = StaticTrapTrigger.tryTriggerTrap(level, ghost, stone);
        helper.assertFalse(triggered, "ghost entry (block not ore-family) -> not triggered, break proceeds normally");
        helper.assertTrue(reg.get(ghost) == null, "ghost entry removed by consistency guard");
        helper.assertTrue(TrapSystem.get().pendingDelayedTaskCount() == before,
                "ghost guard schedules no reaction-window task");
        helper.succeed();
    }

    // ============================================================
    // 探测: L5-7 只揭示非致死且只揭示下发的; L8+ 全揭示; 半径 0 空
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void trapScanFiltersLethalAndRevealsOnlyReturned(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TrapRegistry reg = TrapRegistry.get(level);
        BlockPos center = helper.absolutePos(new BlockPos(1, 1, 1));
        // 2 非致死 (假矿/崩塌) + 2 致死 (TNT/岩浆袋); 探测只读注册表, 无需世界方块。
        BlockPos fake = helper.absolutePos(new BlockPos(0, 1, 0));
        BlockPos collapse = helper.absolutePos(new BlockPos(2, 1, 0));
        BlockPos tnt = helper.absolutePos(new BlockPos(0, 1, 2));
        BlockPos lava = helper.absolutePos(new BlockPos(2, 1, 2));
        reg.put(fake, StaticTrapKind.FAKE_ORE);
        reg.put(collapse, StaticTrapKind.COLLAPSING_TUNNEL);
        reg.put(tnt, StaticTrapKind.TNT_VEIN);
        reg.put(lava, StaticTrapKind.LAVA_POCKET);

        // L5-7 (lethalAllowed=false): 只下发 2 非致死, 且只揭示这 2 个 (致死被过滤的不揭示)。
        UUID low = UUID.randomUUID();
        List<BlockPos> nonLethal = TrapScanService.scanWorld(level, center, 8, false, low);
        helper.assertTrue(nonLethal.size() == 2, "L5-7 scan returns only 2 non-lethal, got " + nonLethal.size());
        helper.assertTrue(reg.isRevealed(low, fake) && reg.isRevealed(low, collapse),
                "L5-7 reveals the 2 returned non-lethal traps");
        helper.assertFalse(reg.isRevealed(low, tnt) || reg.isRevealed(low, lava),
                "L5-7 does NOT reveal lethal traps hidden by the filter");

        // L8+ (lethalAllowed=true): 4 个全收全揭示。
        UUID high = UUID.randomUUID();
        List<BlockPos> all = TrapScanService.scanWorld(level, center, 8, true, high);
        helper.assertTrue(all.size() == 4, "L8+ scan returns all 4 traps, got " + all.size());
        helper.assertTrue(reg.isRevealed(high, fake) && reg.isRevealed(high, collapse)
                        && reg.isRevealed(high, tnt) && reg.isRevealed(high, lava),
                "L8+ reveals all 4 returned traps");

        // 半径 0 -> 空 (与 scan 上游门控一致), 且不揭示。
        UUID zero = UUID.randomUUID();
        helper.assertTrue(TrapScanService.scanWorld(level, center, 0, true, zero).isEmpty(), "radius 0 yields no hits");
        helper.assertFalse(reg.isRevealed(zero, fake), "radius 0 reveals nothing");

        // 清理本测试写入的注册表条目 (共享 level SavedData)。
        reg.remove(fake);
        reg.remove(collapse);
        reg.remove(tnt);
        reg.remove(lava);
        helper.succeed();
    }

    // ============================================================
    // 连锁: 已揭示位跳过 (方块保留, 不触发); 未揭示位触发 (移除+调度) 且不计产出
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chainSkipsRevealedTrapKeepingBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // 手持正确档位镐: 新 chainable 谓词要求 isCorrectToolForDrops, 空手会使 coal origin 不可连锁 (整链不启动)。
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_PICKAXE));
        TrapRegistry reg = TrapRegistry.get(level);
        ChainMiningEngine engine = new ChainMiningEngine();

        // coal_ore 矿脉: origin 与相邻的伪装陷阱; 陷阱已被该玩家探测揭示。
        helper.setBlock(new BlockPos(0, 1, 0), Blocks.COAL_ORE);
        helper.setBlock(new BlockPos(1, 1, 0), Blocks.COAL_ORE);
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        BlockPos trap = helper.absolutePos(new BlockPos(1, 1, 0));
        reg.put(trap, StaticTrapKind.FAKE_ORE);
        reg.markRevealed(player.getUUID(), trap);

        List<BlockPos> produced = new ArrayList<>();
        int before = TrapSystem.get().pendingDelayedTaskCount();
        int broken = engine.chainBreak(player, origin, level, 16, (pos, block, drops) -> produced.add(pos));

        helper.assertTrue(broken == 0, "revealed trap is skipped, no chain break counted (broken=" + broken + ")");
        helper.assertFalse(produced.contains(trap), "revealed trap not in chain output settlement");
        helper.assertTrue(reg.get(trap) == StaticTrapKind.FAKE_ORE, "revealed trap entry preserved (not triggered)");
        helper.assertBlockPresent(Blocks.COAL_ORE, new BlockPos(1, 1, 0)); // 方块保留原地。
        helper.assertTrue(TrapSystem.get().pendingDelayedTaskCount() == before,
                "revealed trap schedules no reaction-window task");
        reg.remove(trap);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chainTriggersUnrevealedTrapExcludingItFromOutput(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // 手持正确档位镐: 新 chainable 谓词要求 isCorrectToolForDrops, 空手会使 coal origin 不可连锁 (整链不启动)。
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_PICKAXE));
        TrapRegistry reg = TrapRegistry.get(level);
        ChainMiningEngine engine = new ChainMiningEngine();

        helper.setBlock(new BlockPos(0, 1, 0), Blocks.COAL_ORE);
        helper.setBlock(new BlockPos(1, 1, 0), Blocks.COAL_ORE);
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        BlockPos trap = helper.absolutePos(new BlockPos(1, 1, 0));
        reg.put(trap, StaticTrapKind.TNT_VEIN); // 未揭示 (未 markRevealed)。

        List<BlockPos> produced = new ArrayList<>();
        int before = TrapSystem.get().pendingDelayedTaskCount();
        int broken = engine.chainBreak(player, origin, level, 16, (pos, block, drops) -> produced.add(pos));

        helper.assertTrue(broken == 0, "triggered trap is not counted as a chain break (broken=" + broken + ")");
        helper.assertFalse(produced.contains(trap), "triggered trap excluded from chain output settlement");
        helper.assertTrue(reg.get(trap) == null, "unrevealed trap triggered -> entry removed");
        helper.assertTrue(TrapSystem.get().pendingDelayedTaskCount() == before + 1,
                "unrevealed trap trigger schedules one reaction-window task");
        helper.succeed();
    }

    // ============================================================
    // 回归: damageTypeFor 与矿脉抗性联动 + KIND<->TrapType 双射 (保持不变)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void trapEffectDamageTypesAreRecognizedByVeinResistance(GameTestHelper helper) {
        // 每种陷阱触发效果的伤害类型固定映射 (改触发效果引入战斗向来源即挂)。
        helper.assertTrue(StaticTrapTrigger.damageTypeFor(StaticTrapKind.TNT_VEIN) == DamageTypes.EXPLOSION,
                "TNT_VEIN triggers a (non-player) EXPLOSION");
        helper.assertTrue(StaticTrapTrigger.damageTypeFor(StaticTrapKind.FAKE_ORE) == DamageTypes.EXPLOSION,
                "FAKE_ORE triggers a (non-player) EXPLOSION");
        helper.assertTrue(StaticTrapTrigger.damageTypeFor(StaticTrapKind.LAVA_POCKET) == DamageTypes.LAVA,
                "LAVA_POCKET triggers LAVA damage");
        helper.assertTrue(StaticTrapTrigger.damageTypeFor(StaticTrapKind.COLLAPSING_TUNNEL) == DamageTypes.FALLING_BLOCK,
                "COLLAPSING_TUNNEL triggers FALLING_BLOCK damage");

        // 且每种映射类型都被矿脉抗性识别为陷阱专属来源 -> 减伤 (L5+, 矿洞内) 自动覆盖全部静态陷阱, 闭合审计 Critical。
        for (StaticTrapKind kind : StaticTrapKind.values()) {
            ResourceKey<DamageType> dt = StaticTrapTrigger.damageTypeFor(kind);
            helper.assertTrue(MinerSurvival.isTrapSource(sourceOf(helper, dt)),
                    kind + " effect damage type " + dt.location() + " must be recognized by miner vein resistance");
        }
        // 反向红线: 玩家点燃的 TNT (PLAYER_EXPLOSION) 与近战 (PLAYER_ATTACK) 不得被当陷阱伤软化 (守 PvP attrition)。
        helper.assertFalse(MinerSurvival.isTrapSource(sourceOf(helper, DamageTypes.PLAYER_EXPLOSION)),
                "PLAYER_EXPLOSION is NOT a trap source (PvP red line)");
        helper.assertFalse(MinerSurvival.isTrapSource(sourceOf(helper, DamageTypes.PLAYER_ATTACK)),
                "PLAYER_ATTACK is NOT a trap source (combat red line)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void staticTrapKindMapsToTrapTypeAndLethality(GameTestHelper helper) {
        // KIND <-> TrapType 双射。
        helper.assertTrue(StaticTrapKind.TNT_VEIN.trapType() == TrapType.TNT_VEIN, "TNT_VEIN -> TrapType.TNT_VEIN");
        helper.assertTrue(StaticTrapKind.LAVA_POCKET.trapType() == TrapType.LAVA_POCKET, "LAVA_POCKET -> TrapType.LAVA_POCKET");
        helper.assertTrue(StaticTrapKind.COLLAPSING_TUNNEL.trapType() == TrapType.COLLAPSING_TUNNEL, "COLLAPSING -> TrapType.COLLAPSING_TUNNEL");
        helper.assertTrue(StaticTrapKind.FAKE_ORE.trapType() == TrapType.FAKE_ORE, "FAKE_ORE -> TrapType.FAKE_ORE");
        // 致死分类 (探测按此分级: L5-7 仅非致死 / L8+ 含致死)。
        helper.assertTrue(StaticTrapKind.TNT_VEIN.trapType().lethal(), "TNT_VEIN is lethal");
        helper.assertTrue(StaticTrapKind.LAVA_POCKET.trapType().lethal(), "LAVA_POCKET is lethal");
        helper.assertFalse(StaticTrapKind.FAKE_ORE.trapType().lethal(), "FAKE_ORE is non-lethal");
        helper.assertFalse(StaticTrapKind.COLLAPSING_TUNNEL.trapType().lethal(), "COLLAPSING_TUNNEL is non-lethal");

        // TrapOreBlock KIND 属性对每个 kind 可 setValue/getValue 往返 (布点时 datapack 据此, 转换前瞬时存在)。
        for (StaticTrapKind k : StaticTrapKind.values()) {
            BlockState st = ModBlocks.TRAP_ORE.get().defaultBlockState().setValue(TrapOreBlock.KIND, k);
            helper.assertTrue(st.getValue(TrapOreBlock.KIND) == k, "TrapOreBlock KIND round-trips for " + k);
        }
        helper.succeed();
    }

    // ============================================================
    // 调试落点 (/mining trap place 命令核心): place 写伪装矿石 + 登记陷阱身份; 非法 skin 被拒。
    // 删 setBlock 或 registry.put -> 断言必挂; 删 isDisguiseOre 契约前置 -> 非法 skin 不再抛必挂。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void trapDebugPlaceWritesSkinAndRegistersIdentity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TrapRegistry reg = TrapRegistry.get(level);
        BlockPos rel = new BlockPos(3, 1, 0);
        BlockPos pos = helper.absolutePos(rel);
        BlockState skin = Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(); // 合法伪装矿石 (铁的深板岩变体)。

        BlockState placed = TrapDebugPlacement.place(level, pos, StaticTrapKind.TNT_VEIN, skin);
        // 目标位方块态 == skin, 且注册表登记为该 kind (世界只存真伪装矿石, 陷阱身份进注册表)。
        helper.assertTrue(placed == skin, "place returns the applied skin state");
        helper.assertTrue(level.getBlockState(pos).is(Blocks.DEEPSLATE_IRON_ORE),
                "target block state set to the skin ore (deepslate iron)");
        helper.assertTrue(reg.get(pos) == StaticTrapKind.TNT_VEIN, "registry records the trap kind at the placed pos");

        // 非法 skin (非伪装矿石族, 如石头) 被拒: place 抛 IllegalArgumentException, 不写世界不登记。
        BlockPos rel2 = new BlockPos(4, 1, 0);
        BlockPos pos2 = helper.absolutePos(rel2);
        boolean threw = false;
        try {
            TrapDebugPlacement.place(level, pos2, StaticTrapKind.FAKE_ORE, Blocks.STONE.defaultBlockState());
        } catch (IllegalArgumentException rejected) {
            threw = true;
        }
        helper.assertTrue(threw, "place rejects a non-disguise-ore skin (stone) with IllegalArgumentException");
        helper.assertTrue(reg.get(pos2) == null, "rejected skin leaves no registry entry at that pos");

        reg.remove(pos); // 清理共享 SavedData。
        helper.succeed();
    }

    // ---- 测试辅助 ----

    /** 把 nearby 返回的 Entry 列表投影成坐标列表 (断言收集内容)。 */
    private static List<BlockPos> positions(List<TrapRegistry.Entry> entries) {
        List<BlockPos> out = new ArrayList<>(entries.size());
        for (TrapRegistry.Entry e : entries) {
            out.add(e.pos());
        }
        return out;
    }

    /** 由 DamageType 键构造一个该类型的 DamageSource (供断言 isTrapSource 分类; 从伤害类型注册表取 Holder)。 */
    private static DamageSource sourceOf(GameTestHelper helper, ResourceKey<DamageType> key) {
        return new DamageSource(helper.getLevel().registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(key));
    }
}
