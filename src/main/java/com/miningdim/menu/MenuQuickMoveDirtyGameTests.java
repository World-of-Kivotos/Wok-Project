package com.miningdim.menu;

import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * V02 回归: 公共菜单基类 {@link AbstractMiningMenu} 的 Shift 移物必须标脏背后方块实体。
 *
 * 缺陷: Forge SlotItemHandler 不覆写 setChanged, 基类部分移出后的 {@code slot.setChanged()} 与 vanilla
 * moveItemStackTo 合并分支的 {@code slot.setChanged()} 都落到一个 0 格空容器上; 活栈被就地改了数量, BE 却没
 * 标脏, 区块 isUnsaved 为 false 时 ChunkMap.save 直接跳过, 卸载/重启后容器回档 —— 部分移出复制物品, 合并放入
 * 吞物。另外 vanilla 合并分支不判 mayPlace, 玩家手里的同种物品会被并进只取不放的输出槽。
 *
 * 断言口径: 先 {@code chunk.setUnsaved(false)} 模拟 "区块已存过盘", 再走真菜单 quickMoveStack, 断言区块重新
 * 变脏 (ChunkMap.save 的真实判据)。三种受影响机台各走一遍: 工程师生产台 (输入槽收任意物品)、酿酒台 (5 投料槽
 * 收任意物品)、枪匠冲压机 (V02 本体)。
 *
 * 模块边界: 本类在 wok-core (com.miningdim.menu), 不直接引用各职业模块的类; 方块与菜单一律按注册名经 Forge
 * 注册表取, 菜单按客户端同款 extraData (BlockPos) 经 MenuType 构造, 走的是与生产环境相同的构造器。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MenuQuickMoveDirtyGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "menu_quickmove_dirty";
    private static final BlockPos BENCH_REL = new BlockPos(1, 1, 1);

    /** 三台机台的第 0 容器槽都收铁锭 (冲压机零件槽只认铁锭; 生产台/酿酒台输入槽收任意物品)。 */
    private static final Item MATERIAL = Items.IRON_INGOT;
    /** 填满玩家背包用的占位物 (与 MATERIAL 不可合并)。 */
    private static final Item FILLER = Items.COBBLESTONE;

    /** 被测机台: 方块注册名 + 菜单注册名 (都在本 mod 命名空间下)。 */
    private record Bench(String blockId, String menuId) {
    }

    private static final Bench PRODUCTION_TABLE = new Bench("production_table_high", "production_table");
    private static final Bench BREWING_STATION = new Bench("brewing_station", "brewing_station");
    private static final Bench GUNSMITH_PRESS = new Bench("gunsmith_press", "gunsmith_press");

    private MenuQuickMoveDirtyGameTests() {
    }

    // ============================================================
    // Shift 部分移出: 容器 64 个, 玩家背包只容得下 63 个 -> 残留 1 个, 必须经 set 写回标脏
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void productionTableShiftPartialTakeMarksChunkDirty(GameTestHelper helper) {
        assertPartialTakeMarksDirty(helper, PRODUCTION_TABLE);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void brewingStationShiftPartialTakeMarksChunkDirty(GameTestHelper helper) {
        assertPartialTakeMarksDirty(helper, BREWING_STATION);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gunsmithPressShiftPartialTakeMarksChunkDirty(GameTestHelper helper) {
        assertPartialTakeMarksDirty(helper, GUNSMITH_PRESS);
    }

    // ============================================================
    // Shift 合并放入: 容器已有 10 个, 玩家 Shift 20 个同种物品 -> 合并成 30, 必须经 set 写回标脏
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void productionTableShiftMergeInMarksChunkDirty(GameTestHelper helper) {
        assertMergeInMarksDirty(helper, PRODUCTION_TABLE);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void brewingStationShiftMergeInMarksChunkDirty(GameTestHelper helper) {
        assertMergeInMarksDirty(helper, BREWING_STATION);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gunsmithPressShiftMergeInMarksChunkDirty(GameTestHelper helper) {
        assertMergeInMarksDirty(helper, GUNSMITH_PRESS);
    }

    // ============================================================
    // 合并阶段跳过 mayPlace=false 的输出槽: 输出槽里已有 1 个同种物品, Shift 放入不得并进去
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void productionTableShiftMergeSkipsOutputSlot(GameTestHelper helper) {
        assertMergeSkipsOutputSlot(helper, PRODUCTION_TABLE);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void brewingStationShiftMergeSkipsOutputSlot(GameTestHelper helper) {
        assertMergeSkipsOutputSlot(helper, BREWING_STATION);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gunsmithPressShiftMergeSkipsOutputSlot(GameTestHelper helper) {
        assertMergeSkipsOutputSlot(helper, GUNSMITH_PRESS);
    }

    // ============================================================
    // 关窗兜底: 服务端 removed() 对背后方块实体补一次 setChanged
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void productionTableCloseMarksChunkDirty(GameTestHelper helper) {
        assertCloseMarksDirty(helper, PRODUCTION_TABLE);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void brewingStationCloseMarksChunkDirty(GameTestHelper helper) {
        assertCloseMarksDirty(helper, BREWING_STATION);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gunsmithPressCloseMarksChunkDirty(GameTestHelper helper) {
        assertCloseMarksDirty(helper, GUNSMITH_PRESS);
    }

    // ---- 场景实现 ----

    private static void assertPartialTakeMarksDirty(GameTestHelper helper, Bench bench) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        AbstractMiningMenu menu = openBench(helper, bench, player);
        menu.getSlot(0).set(new ItemStack(MATERIAL, 64));
        // 玩家背包 35 格塞满占位物, 快捷栏 0 放 1 个同种物品: 只容得下 63 个, 容器残留 1 个 (部分移出)。
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            player.getInventory().setItem(i, new ItemStack(FILLER, 64));
        }
        player.getInventory().setItem(0, new ItemStack(MATERIAL, 1));
        LevelChunk chunk = cleanChunk(helper);

        ItemStack moved = menu.quickMoveStack(player, 0);

        helper.assertTrue(moved.getCount() == 64,
                bench.menuId() + ": quickMoveStack must report the pre-move stack (64), got " + moved.getCount());
        helper.assertTrue(menu.getSlot(0).getItem().getCount() == 1,
                bench.menuId() + ": partial shift-take must leave exactly 1 in the container slot, got "
                        + menu.getSlot(0).getItem().getCount());
        helper.assertTrue(player.getInventory().getItem(0).getCount() == 64,
                bench.menuId() + ": the 63 moved items must land on the player's partial stack");
        helper.assertTrue(chunk.isUnsaved(),
                bench.menuId() + ": partial shift-take must mark the block entity dirty, otherwise the chunk is "
                        + "skipped on unload and the container rolls back to 64 (item dupe)");
        helper.succeed();
    }

    private static void assertMergeInMarksDirty(GameTestHelper helper, Bench bench) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        AbstractMiningMenu menu = openBench(helper, bench, player);
        menu.getSlot(0).set(new ItemStack(MATERIAL, 10));
        player.getInventory().setItem(0, new ItemStack(MATERIAL, 20));
        int playerSlot = menuSlotOfInventory(menu, player, 0);
        LevelChunk chunk = cleanChunk(helper);

        menu.quickMoveStack(player, playerSlot);

        helper.assertTrue(menu.getSlot(0).getItem().getCount() == 30,
                bench.menuId() + ": shift-merge must top the existing stack up to 30, got "
                        + menu.getSlot(0).getItem().getCount());
        helper.assertTrue(player.getInventory().getItem(0).isEmpty(),
                bench.menuId() + ": all 20 items must leave the player slot");
        helper.assertTrue(chunk.isUnsaved(),
                bench.menuId() + ": shift-merge into an existing stack must mark the block entity dirty, otherwise "
                        + "the merged items vanish when the chunk unloads");
        helper.succeed();
    }

    private static void assertMergeSkipsOutputSlot(GameTestHelper helper, Bench bench) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        AbstractMiningMenu menu = openBench(helper, bench, player);
        // 三台机台的输出槽都是最后一个容器槽 (只取不放)。直接 set 绕过 mayPlace 放入 1 个同种物品。
        int outputSlot = menu.containerSlotCount() - 1;
        helper.assertFalse(menu.getSlot(outputSlot).mayPlace(new ItemStack(MATERIAL)),
                bench.menuId() + ": precondition - the last container slot must be the take-only output slot");
        menu.getSlot(outputSlot).set(new ItemStack(MATERIAL, 1));
        player.getInventory().setItem(0, new ItemStack(MATERIAL, 20));
        int playerSlot = menuSlotOfInventory(menu, player, 0);

        menu.quickMoveStack(player, playerSlot);

        helper.assertTrue(menu.getSlot(outputSlot).getItem().getCount() == 1,
                bench.menuId() + ": shift-merge must not top up the take-only output slot, got "
                        + menu.getSlot(outputSlot).getItem().getCount());
        helper.assertTrue(menu.getSlot(0).getItem().is(MATERIAL) && menu.getSlot(0).getItem().getCount() == 20,
                bench.menuId() + ": the 20 items must go to the empty input slot instead, got "
                        + menu.getSlot(0).getItem());
        helper.assertTrue(player.getInventory().getItem(0).isEmpty(),
                bench.menuId() + ": all 20 items must leave the player slot");
        helper.succeed();
    }

    private static void assertCloseMarksDirty(GameTestHelper helper, Bench bench) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        AbstractMiningMenu menu = openBench(helper, bench, player);
        LevelChunk chunk = cleanChunk(helper);

        menu.removed(player);

        helper.assertTrue(chunk.isUnsaved(),
                bench.menuId() + ": closing the menu server-side must mark the backing block entity dirty");
        helper.succeed();
    }

    // ---- 工具 ----

    /** 放机台方块, 按客户端同款 extraData (BlockPos) 经注册表里的 MenuType 构造真菜单 (服务端 level)。 */
    private static AbstractMiningMenu openBench(GameTestHelper helper, Bench bench, ServerPlayer player) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(MiningConstants.MODID, bench.blockId()));
        if (block == null || block == Blocks.AIR) {
            throw new IllegalStateException("block not registered: " + bench.blockId());
        }
        helper.setBlock(BENCH_REL, block);
        BlockPos abs = helper.absolutePos(BENCH_REL);
        if (helper.getLevel().getBlockEntity(abs) == null) {
            throw new IllegalStateException("block entity not present for " + bench.blockId() + " at " + abs);
        }
        MenuType<?> type = ForgeRegistries.MENU_TYPES.getValue(
                new ResourceLocation(MiningConstants.MODID, bench.menuId()));
        if (type == null) {
            throw new IllegalStateException("menu type not registered: " + bench.menuId());
        }
        FriendlyByteBuf extraData = new FriendlyByteBuf(Unpooled.buffer());
        extraData.writeBlockPos(abs);
        AbstractContainerMenu menu = type.create(1, player.getInventory(), extraData);
        if (!(menu instanceof AbstractMiningMenu miningMenu)) {
            throw new IllegalStateException(bench.menuId() + " is not an AbstractMiningMenu: " + menu);
        }
        return miningMenu;
    }

    /** 模拟 "区块已存过盘": 清掉脏标记并确认没有别的东西把它重新标脏。 */
    private static LevelChunk cleanChunk(GameTestHelper helper) {
        LevelChunk chunk = helper.getLevel().getChunkAt(helper.absolutePos(BENCH_REL));
        chunk.setUnsaved(false);
        helper.assertFalse(chunk.isUnsaved(), "precondition - chunk must start clean");
        return chunk;
    }

    /** 菜单里对应玩家背包第 inventoryIndex 格的槽号 (基类先铺主背包 9-35, 再铺快捷栏 0-8)。 */
    private static int menuSlotOfInventory(AbstractMiningMenu menu, Player player, int inventoryIndex) {
        for (int i = menu.containerSlotCount(); i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == inventoryIndex) {
                return i;
            }
        }
        throw new IllegalStateException("player inventory slot " + inventoryIndex + " not found in menu");
    }
}
