package com.miningdim.donation;

import com.miningdim.core.MiningConstants;
import com.miningdim.menu.AbstractMiningMenu;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.SlotItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 捐赠箱 GameTest。每条用例都走真实路径: 方块 use 开界面、菜单 clicked (含 Shift 点击) 移物、
 * closeContainer 触发退还与落账、ServerPlayerGameMode.destroyBlock 触发 BreakEvent、原版漏斗 tick 推送、
 * /donationbox 指令经 Brigadier 执行。删掉被测逻辑中的任一环, 对应断言必挂 (各用例注释写明反例)。
 *
 * 模拟玩家自建而不用 testutil 的共享工具: 这里需要互不相同的玩家名 (协管按名字解析) 与可控的权限等级
 * (GameTestServer 的 OP 等级恒为 0, PlayerList.op 给不出 &gt;= 2 的权限, 只能覆写 getPermissionLevel)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class DonationBoxGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "donation";
    private static final BlockPos BOX = new BlockPos(1, 2, 1);
    private static final AtomicInteger NAME_SEQUENCE = new AtomicInteger();
    private static final int PLAYER_SLOTS = 36;

    private DonationBoxGameTests() {
    }

    // ============================================================
    // 1. 捐赠转入仓库 + 放置者即箱主
    // ============================================================

    /** 反例: 删掉 DonationDonorMenu.broadcastChanges 里的 flushInput, 石头停在投入格, 仓库为 0。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void donatedBlocksMoveIntoStorageAndPlacerBecomesOwner(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "own", 0);
        ServerPlayer donor = player(helper, "don", 0);
        DonationBoxBlockEntity box = placeBox(helper, owner);
        helper.assertTrue(owner.getUUID().equals(box.ownerId()), "placer must be recorded as owner");
        helper.assertTrue(owner.getGameProfile().getName().equals(box.ownerName()), "owner name must be recorded");

        donor.getInventory().setItem(0, new ItemStack(Items.STONE, 32));
        AbstractContainerMenu opened = use(helper, donor);
        helper.assertTrue(opened instanceof DonationDonorMenu, "a non-manager must get the donor menu, got " + opened);
        DonationDonorMenu menu = (DonationDonorMenu) opened;

        menu.clicked(menuSlotOf(menu, 0), 0, ClickType.QUICK_MOVE, donor);
        menu.broadcastChanges();

        helper.assertTrue(box.storage().count(Items.STONE) == 32,
                "all 32 stone must move into storage, got " + box.storage().count(Items.STONE));
        helper.assertTrue(donor.getInventory().countItem(Items.STONE) == 0, "donor keeps no stone");
        helper.assertTrue(menu.input().isEmpty(), "input grid must be drained once accepted");

        int before = box.ledger().size();
        donor.closeContainer();
        helper.assertTrue(box.ledger().size() == before + 1, "closing the session writes exactly one row");
        DonationLedger.Entry entry = box.ledger().entriesNewestFirst().get(0);
        assertEntry(helper, entry, donor.getUUID(), DonationLedger.Action.DEPOSIT, Items.STONE, 32);
        helper.succeed();
    }

    // ============================================================
    // 2. 捐赠者无法取出
    // ============================================================

    /**
     * 捐赠界面没有任何绑定仓库的槽位, 各种点击都碰不到仓库; 自动化视图任何面都抽不出东西, 底面干脆不暴露。
     * 反例: 让 DonationAutomationHandler.extractItem 转发给仓库, 抽取断言即挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void donorCannotReachStorageAndAutomationCannotExtract(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "own", 0);
        ServerPlayer donor = player(helper, "don", 0);
        DonationBoxBlockEntity box = placeBox(helper, owner);
        box.storage().setStackInSlot(0, new ItemStack(Items.OAK_PLANKS, 64));

        DonationDonorMenu menu = (DonationDonorMenu) use(helper, donor);
        helper.assertTrue(menu.slots.size() == DonationDonorMenu.INPUT_SLOTS + PLAYER_SLOTS,
                "donor menu must hold only 9 input slots plus the player inventory, got " + menu.slots.size());
        for (Slot slot : menu.slots) {
            helper.assertFalse(slot instanceof SlotItemHandler, "no donor slot may be backed by the storage handler");
        }
        for (int i = 0; i < DonationDonorMenu.INPUT_SLOTS; i++) {
            menu.clicked(i, 0, ClickType.PICKUP, donor);
            menu.clicked(i, 0, ClickType.QUICK_MOVE, donor);
            menu.clicked(i, 0, ClickType.SWAP, donor);
            menu.clicked(i, 0, ClickType.THROW, donor);
        }
        // 双击收集同类物品: 只会在界面里的槽位之间收集, 仓库里的木板收不过来。
        donor.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 1));
        int hotbar0 = menuSlotOf(menu, 0);
        menu.clicked(hotbar0, 0, ClickType.PICKUP, donor);
        menu.clicked(hotbar0, 0, ClickType.PICKUP_ALL, donor);
        helper.assertTrue(menu.getCarried().getCount() == 1,
                "double-click collect must not pull planks out of storage, carried " + menu.getCarried());
        menu.clicked(hotbar0, 0, ClickType.PICKUP, donor);

        Direction[] exposed = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (Direction side : exposed) {
            IItemHandler handler = box.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
            helper.assertTrue(handler != null, "automation input must be exposed on side " + side);
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                helper.assertTrue(handler.extractItem(slot, 64, false).isEmpty(),
                        "extraction must be impossible on side " + side + " slot " + slot);
            }
            handler.getStackInSlot(0).shrink(64);
        }
        helper.assertFalse(box.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.DOWN).isPresent(),
                "the bottom face must not expose any item handler");
        helper.assertFalse(box.getCapability(ForgeCapabilities.ITEM_HANDLER, null).isPresent(),
                "the sideless query must not expose any item handler");
        helper.assertTrue(box.storage().count(Items.OAK_PLANKS) == 64,
                "storage must still hold 64 planks, got " + box.storage().count(Items.OAK_PLANKS));
        helper.assertTrue(donor.getInventory().countItem(Items.OAK_PLANKS) == 1, "donor still has only its own plank");
        donor.closeContainer();
        helper.succeed();
    }

    // ============================================================
    // 3. 白名单
    // ============================================================

    /** 反例: 去掉 DonationWhitelist 的 hasTag 判定, 装满钻石的潜影盒被判 DENIED_BY_TAG 而非 CARRIES_NBT。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void whitelistRefusesNonBlocksNbtCarriersAndDeniedTag(GameTestHelper helper) {
        ItemStack filledShulker = filledShulker();
        ItemStack renamedStone = new ItemStack(Items.STONE);
        renamedStone.setHoverName(Component.literal("gift"));

        assertVerdict(helper, new ItemStack(Items.STONE, 64), DonationWhitelist.Verdict.ACCEPTED);
        assertVerdict(helper, new ItemStack(Items.OAK_PLANKS), DonationWhitelist.Verdict.ACCEPTED);
        assertVerdict(helper, new ItemStack(Items.DIAMOND), DonationWhitelist.Verdict.NOT_A_BLOCK);
        assertVerdict(helper, new ItemStack(Items.IRON_PICKAXE), DonationWhitelist.Verdict.NOT_A_BLOCK);
        // 原版的胡萝卜/马铃薯/浆果是 ItemNameBlockItem, 只看 BlockItem 会被当建材收进来。
        assertVerdict(helper, new ItemStack(Items.CARROT), DonationWhitelist.Verdict.EDIBLE);
        assertVerdict(helper, new ItemStack(Items.POTATO), DonationWhitelist.Verdict.EDIBLE);
        assertVerdict(helper, new ItemStack(Items.SWEET_BERRIES), DonationWhitelist.Verdict.EDIBLE);
        assertVerdict(helper, new ItemStack(Items.GLOW_BERRIES), DonationWhitelist.Verdict.EDIBLE);
        assertVerdict(helper, new ItemStack(Items.CAKE), DonationWhitelist.Verdict.DENIED_BY_TAG);
        assertVerdict(helper, new ItemStack(Items.WHEAT_SEEDS), DonationWhitelist.Verdict.ACCEPTED);
        assertVerdict(helper, filledShulker, DonationWhitelist.Verdict.CARRIES_NBT);
        assertVerdict(helper, renamedStone, DonationWhitelist.Verdict.CARRIES_NBT);
        assertVerdict(helper, new ItemStack(Items.SHULKER_BOX), DonationWhitelist.Verdict.DENIED_BY_TAG);
        assertVerdict(helper, new ItemStack(Items.RED_SHULKER_BOX), DonationWhitelist.Verdict.DENIED_BY_TAG);
        assertVerdict(helper, new ItemStack(Items.DIAMOND_BLOCK), DonationWhitelist.Verdict.DENIED_BY_TAG);
        assertVerdict(helper, new ItemStack(Items.EMERALD_BLOCK), DonationWhitelist.Verdict.DENIED_BY_TAG);
        assertVerdict(helper, new ItemStack(Items.NETHERITE_BLOCK), DonationWhitelist.Verdict.DENIED_BY_TAG);
        assertVerdict(helper, new ItemStack(Items.ANCIENT_DEBRIS), DonationWhitelist.Verdict.DENIED_BY_TAG);
        assertVerdict(helper, new ItemStack(Items.IRON_ORE), DonationWhitelist.Verdict.DENIED_BY_TAG);
        assertVerdict(helper, new ItemStack(Items.DEEPSLATE_DIAMOND_ORE), DonationWhitelist.Verdict.DENIED_BY_TAG);
        assertVerdict(helper, new ItemStack(Items.SPAWNER), DonationWhitelist.Verdict.DENIED_BY_TAG);
        assertVerdict(helper, new ItemStack(Items.BEDROCK), DonationWhitelist.Verdict.DENIED_BY_TAG);
        assertVerdict(helper, new ItemStack(DonationRegistry.DONATION_BOX_ITEM.get()), DonationWhitelist.Verdict.DENIED_BY_TAG);
        assertVerdict(helper, new ItemStack(Items.COMMAND_BLOCK), DonationWhitelist.Verdict.CREATIVE_ONLY);
        assertVerdict(helper, new ItemStack(Items.STRUCTURE_BLOCK), DonationWhitelist.Verdict.CREATIVE_ONLY);
        assertVerdict(helper, new ItemStack(Items.JIGSAW), DonationWhitelist.Verdict.CREATIVE_ONLY);

        ServerPlayer owner = player(helper, "own", 0);
        ServerPlayer donor = player(helper, "don", 0);
        DonationBoxBlockEntity box = placeBox(helper, owner);
        List<ItemStack> rejected = List.of(new ItemStack(Items.DIAMOND, 5), filledShulker.copy(),
                new ItemStack(Items.DIAMOND_BLOCK, 3), new ItemStack(Items.IRON_ORE, 7), new ItemStack(Items.SHULKER_BOX));
        for (int i = 0; i < rejected.size(); i++) {
            donor.getInventory().setItem(9 + i, rejected.get(i).copy());
        }
        DonationDonorMenu menu = (DonationDonorMenu) use(helper, donor);
        for (int i = 0; i < rejected.size(); i++) {
            menu.clicked(menuSlotOf(menu, 9 + i), 0, ClickType.QUICK_MOVE, donor);
        }
        menu.broadcastChanges();
        for (int i = 0; i < rejected.size(); i++) {
            helper.assertTrue(ItemStack.matches(donor.getInventory().getItem(9 + i), rejected.get(i)),
                    "shift-click must leave refused " + rejected.get(i) + " in the donor inventory");
        }
        helper.assertTrue(menu.input().isEmpty(), "no refused item may reach the input grid");

        menu.setCarried(new ItemStack(Items.DIAMOND_BLOCK));
        menu.clicked(0, 0, ClickType.PICKUP, donor);
        helper.assertTrue(menu.getSlot(0).getItem().isEmpty() && menu.getCarried().is(Items.DIAMOND_BLOCK),
                "dropping a refused item onto an input slot must be rejected");
        menu.setCarried(ItemStack.EMPTY);

        ItemStack blockViaStorage = new ItemStack(Items.DIAMOND_BLOCK, 2);
        helper.assertTrue(ItemStack.matches(box.storage().insertItem(0, blockViaStorage.copy(), false), blockViaStorage),
                "the storage handler itself must refuse denied items");
        IItemHandler automation = box.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.UP).orElse(null);
        helper.assertTrue(automation != null, "automation input missing");
        helper.assertTrue(ItemStack.matches(automation.insertItem(0, filledShulker.copy(), false), filledShulker),
                "automation must refuse the filled shulker box");
        helper.assertTrue(box.storage().usedSlots() == 0, "storage must stay empty, used " + box.storage().usedSlots());
        donor.closeContainer();
        helper.succeed();
    }

    // ============================================================
    // 4. 仓库满: 余量留在投入格, 关闭时退还, 总数守恒; 背包满时掉在脚下
    // ============================================================

    /** 反例: 删掉 DonationDonorMenu.removed 里的 clearContainer, 投入格余量随界面一起消失, 退还断言挂。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fullStorageLeavesRemainderInInputAndRefundsOnClose(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "own", 0);
        ServerPlayer donor = player(helper, "don", 0);
        DonationBoxBlockEntity box = placeBox(helper, owner);
        for (int slot = 0; slot < DonationStorage.SLOTS - 1; slot++) {
            box.storage().setStackInSlot(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        box.storage().setStackInSlot(DonationStorage.SLOTS - 1, new ItemStack(Items.STONE, 60));
        donor.getInventory().setItem(0, new ItemStack(Items.STONE, 20));
        donor.getInventory().setItem(1, new ItemStack(Items.DIRT, 16));

        DonationDonorMenu menu = (DonationDonorMenu) use(helper, donor);
        menu.clicked(menuSlotOf(menu, 0), 0, ClickType.QUICK_MOVE, donor);
        menu.broadcastChanges();
        menu.clicked(menuSlotOf(menu, 1), 0, ClickType.QUICK_MOVE, donor);
        menu.broadcastChanges();

        helper.assertTrue(box.storage().count(Items.STONE) == 64, "only 4 stone fit, storage holds " + box.storage().count(Items.STONE));
        helper.assertTrue(inputCount(menu, Items.STONE) == 16, "16 stone must wait in the input grid, got " + inputCount(menu, Items.STONE));
        helper.assertTrue(inputCount(menu, Items.DIRT) == 16, "dirt cannot fit at all, got " + inputCount(menu, Items.DIRT));
        helper.assertTrue(donor.getInventory().countItem(Items.STONE) + inputCount(menu, Items.STONE)
                        + (box.storage().count(Items.STONE) - 60) == 20, "stone must be conserved mid-session");

        donor.closeContainer();
        helper.assertTrue(menu.input().isEmpty(), "closing must empty the input grid");
        helper.assertTrue(donor.getInventory().countItem(Items.STONE) == 16, "16 stone refunded, got " + donor.getInventory().countItem(Items.STONE));
        helper.assertTrue(donor.getInventory().countItem(Items.DIRT) == 16, "16 dirt refunded, got " + donor.getInventory().countItem(Items.DIRT));
        helper.assertTrue(box.storage().count(Items.STONE) == 64 && box.storage().count(Items.DIRT) == 0,
                "storage keeps exactly what was accepted");
        assertEntry(helper, box.ledger().entriesNewestFirst().get(0), donor.getUUID(),
                DonationLedger.Action.DEPOSIT, Items.STONE, 4);

        // 第二次: 投入格里留着泥土, 关界面前把背包塞满 -> 退还的泥土掉在脚下, 不凭空消失。
        DonationDonorMenu second = (DonationDonorMenu) use(helper, donor);
        int dirtSlot = donor.getInventory().findSlotMatchingItem(new ItemStack(Items.DIRT, 16));
        helper.assertTrue(dirtSlot >= 0, "dirt must be back in the inventory");
        second.clicked(menuSlotOf(second, dirtSlot), 0, ClickType.QUICK_MOVE, donor);
        second.broadcastChanges();
        helper.assertTrue(inputCount(second, Items.DIRT) == 16, "dirt waits in the input grid again");
        for (int slot = 0; slot < PLAYER_SLOTS; slot++) {
            donor.getInventory().setItem(slot, new ItemStack(Items.GRAVEL, 64));
        }
        donor.closeContainer();
        int dropped = droppedCount(helper, donor.blockPosition(), Items.DIRT, 2.5D);
        helper.assertTrue(dropped == 16, "with a full inventory the 16 dirt must drop at the donor's feet, got " + dropped);
        discardDrops(helper, donor.blockPosition(), 2.5D);
        helper.succeed();
    }

    // ============================================================
    // 5. Shift 点击守恒 (两种界面)
    // ============================================================

    /** 反例: 让 DonationManagerMenu 的仓库槽用普通 Slot (不过白名单), 钻石会被 Shift 进仓库。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shiftClickConservesItemsAndHonorsWhitelistInBothMenus(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "own", 0);
        ServerPlayer donor = player(helper, "don", 0);
        DonationBoxBlockEntity box = placeBox(helper, owner);

        for (int i = 9; i <= 11; i++) {
            donor.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
        }
        donor.getInventory().setItem(12, new ItemStack(Items.DIAMOND, 5));
        donor.getInventory().setItem(13, new ItemStack(Items.OAK_PLANKS, 64));
        DonationDonorMenu menu = (DonationDonorMenu) use(helper, donor);
        for (int i = 9; i <= 13; i++) {
            menu.clicked(menuSlotOf(menu, i), 0, ClickType.QUICK_MOVE, donor);
            menu.broadcastChanges();
        }
        helper.assertTrue(box.storage().count(Items.STONE) + donor.getInventory().countItem(Items.STONE) == 192
                && box.storage().count(Items.STONE) == 192, "all 192 stone accounted for in storage");
        helper.assertTrue(box.storage().count(Items.OAK_PLANKS) == 64, "planks moved into storage");
        helper.assertTrue(donor.getInventory().getItem(12).is(Items.DIAMOND) && donor.getInventory().getItem(12).getCount() == 5,
                "diamonds must stay where they were");

        // 仓库塞满后 Shift 进投入格的东西停在那里, 再 Shift 回背包, 一件不少。
        int free = 0;
        for (int slot = 0; slot < DonationStorage.SLOTS; slot++) {
            if (box.storage().getStackInSlot(slot).isEmpty()) {
                box.storage().setStackInSlot(slot, new ItemStack(Items.COBBLESTONE, 64));
                free++;
            }
        }
        helper.assertTrue(free == DonationStorage.SLOTS - 4, "4 slots were in use before filling, filled " + free);
        donor.getInventory().setItem(14, new ItemStack(Items.DIRT, 64));
        menu.clicked(menuSlotOf(menu, 14), 0, ClickType.QUICK_MOVE, donor);
        menu.broadcastChanges();
        int dirtInput = -1;
        for (int i = 0; i < DonationDonorMenu.INPUT_SLOTS; i++) {
            if (menu.getSlot(i).getItem().is(Items.DIRT)) {
                dirtInput = i;
            }
        }
        helper.assertTrue(dirtInput >= 0, "dirt must wait in the input grid while storage is full");
        menu.clicked(dirtInput, 0, ClickType.QUICK_MOVE, donor);
        helper.assertTrue(donor.getInventory().countItem(Items.DIRT) == 64 && menu.input().isEmpty(),
                "shift-click from the input grid returns all 64 dirt");
        donor.closeContainer();

        // 管理界面: 仓库 -> 背包 -> 仓库, 数量守恒; 白名单外的物品 Shift 不进仓库。
        for (int slot = 0; slot < DonationStorage.SLOTS; slot++) {
            if (box.storage().getStackInSlot(slot).is(Items.COBBLESTONE)) {
                box.storage().setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
        DonationManagerMenu manager = (DonationManagerMenu) use(helper, owner);
        int stoneSlot = storageSlotOf(box, Items.STONE);
        manager.clicked(stoneSlot, 0, ClickType.QUICK_MOVE, owner);
        helper.assertTrue(owner.getInventory().countItem(Items.STONE) == 64
                        && box.storage().count(Items.STONE) == 128, "one stack of 64 moved out, 128 remain");
        int ownerStone = owner.getInventory().findSlotMatchingItem(new ItemStack(Items.STONE, 64));
        manager.clicked(menuSlotOf(manager, ownerStone), 0, ClickType.QUICK_MOVE, owner);
        helper.assertTrue(owner.getInventory().countItem(Items.STONE) == 0
                && box.storage().count(Items.STONE) == 192, "stone moved back, total 192 conserved");
        owner.getInventory().setItem(20, new ItemStack(Items.DIAMOND, 3));
        owner.getInventory().setItem(21, filledShulker());
        manager.clicked(menuSlotOf(manager, 20), 0, ClickType.QUICK_MOVE, owner);
        manager.clicked(menuSlotOf(manager, 21), 0, ClickType.QUICK_MOVE, owner);
        helper.assertTrue(owner.getInventory().getItem(20).getCount() == 3 && owner.getInventory().getItem(21).hasTag(),
                "refused items stay in the manager's inventory");
        helper.assertTrue(box.storage().count(Items.DIAMOND) == 0, "no diamond may enter storage");
        owner.closeContainer();
        helper.succeed();
    }

    // ============================================================
    // 6. 非箱主破坏被取消
    // ============================================================

    /** 反例: 删掉 DonationEvents.onBreak 与 onDestroyedByPlayer 的拦截, destroyBlock 返回 true 且箱子消失。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void strangersAndCoAdminsCannotBreakTheBox(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "own", 0);
        ServerPlayer stranger = player(helper, "str", 0);
        ServerPlayer coAdmin = player(helper, "coa", 0);
        DonationBoxBlockEntity box = placeBox(helper, owner);
        box.addCoAdmin(coAdmin.getUUID(), coAdmin.getGameProfile().getName());
        box.storage().setStackInSlot(0, new ItemStack(Items.STONE, 10));
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BOX);
        BlockState state = level.getBlockState(abs);

        helper.assertTrue(state.getDestroyProgress(stranger, level, abs) == 0.0F, "stranger dig progress must be 0");
        helper.assertTrue(state.getDestroyProgress(coAdmin, level, abs) == 0.0F, "co-admin dig progress must be 0");
        helper.assertTrue(state.getDestroyProgress(owner, level, abs) > 0.0F, "owner can dig");

        helper.assertFalse(stranger.gameMode.destroyBlock(abs), "stranger break must be cancelled");
        helper.assertFalse(coAdmin.gameMode.destroyBlock(abs), "co-admin break must be cancelled");
        stranger.setGameMode(GameType.CREATIVE);
        helper.assertFalse(stranger.gameMode.destroyBlock(abs), "creative instant break must be cancelled too");
        helper.assertFalse(state.onDestroyedByPlayer(level, abs, stranger, true, level.getFluidState(abs)),
                "the block-level guard must refuse even if BreakEvent was let through");

        helper.assertTrue(level.getBlockEntity(abs) == box && box.storage().count(Items.STONE) == 10,
                "the box and its storage must be untouched");
        helper.assertTrue(droppedCount(helper, abs, Items.STONE, 1.5D) == 0
                && droppedCount(helper, abs, DonationRegistry.DONATION_BOX_ITEM.get(), 1.5D) == 0, "nothing may drop");

        helper.assertTrue(state.getPistonPushReaction() == PushReaction.BLOCK, "pistons must not push the box");
        helper.assertFalse(state.canEntityDestroy(level, abs, EntityType.WITHER.create(level)), "withers cannot destroy it");
        helper.assertFalse(state.canEntityDestroy(level, abs, EntityType.ENDER_DRAGON.create(level)), "dragons cannot destroy it");
        helper.succeed();
    }

    /**
     * 只看硬度的非玩家破坏路径必须把箱子当成不可破坏 (硬度为负); 箱主仍能按名义硬度走真实的生存挖掘
     * (START/STOP 两个动作, 服务端按 tick 数核对进度), 陌生人挥满同样的时长也挖不动。
     * 反例: 把硬度改回 2.5, 硬度断言即挂; 只改硬度不改 getDestroyProgress, 箱主的生存挖掘拆不掉箱子。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 200)
    public static void hardnessGatedBreakersCannotBreakTheBoxButTheOwnerStillMinesIt(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "own", 0);
        ServerPlayer stranger = player(helper, "str", 0);
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BOX);
        DonationBoxBlockEntity box = placeBox(helper, owner);
        box.storage().setStackInSlot(0, new ItemStack(Items.STONE, 5));
        BlockState state = level.getBlockState(abs);

        helper.assertTrue(state.getDestroySpeed(level, abs) < 0.0F,
                "hardness must be negative so hardness-gated breakers treat the box as unbreakable, got "
                        + state.getDestroySpeed(level, abs));
        owner.setOnGround(true);
        stranger.setOnGround(true);
        float expected = owner.getDigSpeed(state, abs) / DonationRegistry.NOMINAL_HARDNESS / 30.0F;
        float actual = state.getDestroyProgress(owner, level, abs);
        helper.assertTrue(actual > 0.0F && Math.abs(actual - expected) < 1.0E-6F,
                "the owner digs at the nominal hardness, expected " + expected + " got " + actual);
        helper.assertTrue(state.getDestroyProgress(stranger, level, abs) == 0.0F, "a stranger's dig progress stays 0");

        int top = level.getMaxBuildHeight();
        stranger.gameMode.handleBlockBreakAction(abs, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                Direction.UP, top, 1);
        owner.gameMode.handleBlockBreakAction(abs, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                Direction.UP, top, 1);
        helper.assertTrue(level.getBlockState(abs).is(DonationRegistry.DONATION_BOX.get()), "nothing breaks instantly");

        // 徒手每 tick 1/75, 服务端 STOP 时要求累计 >= 0.7, 约 52 tick; 留足余量。
        helper.runAfterDelay(80, () -> {
            stranger.gameMode.handleBlockBreakAction(abs, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    Direction.UP, top, 2);
            helper.assertTrue(level.getBlockState(abs).is(DonationRegistry.DONATION_BOX.get()),
                    "a stranger's full-length swing must not break the box");
            owner.gameMode.handleBlockBreakAction(abs, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    Direction.UP, top, 2);
            helper.assertTrue(level.getBlockState(abs).isAir(), "the owner's survival mining must break the box");
            helper.assertTrue(droppedCount(helper, abs, Items.STONE, 1.5D) == 5
                            && droppedCount(helper, abs, DonationRegistry.DONATION_BOX_ITEM.get(), 1.5D) == 1,
                    "the owner's break drops the contents and one plain box");
            discardDrops(helper, abs, 1.5D);
            helper.succeed();
        });
    }

    // ============================================================
    // 7. 箱主/OP 破坏: 散落内容物 + 一个不带内容物的普通箱子
    // ============================================================

    /** 反例: 删掉 DonationBoxBlock.onRemove 的散落逻辑, 内容物随方块实体一起消失。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ownerAndOperatorBreakDropContentsAndAPlainBox(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "own", 0);
        ServerPlayer operator = player(helper, "opr", 2);
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BOX);

        DonationBoxBlockEntity box = placeBox(helper, owner);
        box.storage().setStackInSlot(0, new ItemStack(Items.STONE, 10));
        box.storage().setStackInSlot(1, new ItemStack(Items.DIRT, 5));
        box.storage().setStackInSlot(7, new ItemStack(Items.OAK_PLANKS, 64));
        helper.assertTrue(owner.gameMode.destroyBlock(abs), "the owner may break the box");
        helper.assertTrue(level.getBlockState(abs).isAir(), "the box must be gone");
        helper.assertTrue(droppedCount(helper, abs, Items.STONE, 1.5D) == 10, "10 stone dropped");
        helper.assertTrue(droppedCount(helper, abs, Items.DIRT, 1.5D) == 5, "5 dirt dropped");
        helper.assertTrue(droppedCount(helper, abs, Items.OAK_PLANKS, 1.5D) == 64, "64 planks dropped");
        helper.assertTrue(droppedCount(helper, abs, DonationRegistry.DONATION_BOX_ITEM.get(), 1.5D) == 1,
                "exactly one box item dropped");
        for (ItemEntity entity : drops(helper, abs, 1.5D)) {
            if (entity.getItem().is(DonationRegistry.DONATION_BOX_ITEM.get())) {
                helper.assertFalse(entity.getItem().hasTag(), "the dropped box must not carry any contents");
            }
        }
        discardDrops(helper, abs, 1.5D);

        ServerPlayer secondOwner = player(helper, "own", 0);
        DonationBoxBlockEntity second = placeBox(helper, secondOwner);
        second.storage().setStackInSlot(3, new ItemStack(Items.STONE, 7));
        helper.assertTrue(operator.gameMode.destroyBlock(abs), "an OP may break anyone's box");
        helper.assertTrue(level.getBlockState(abs).isAir(), "the second box must be gone");
        helper.assertTrue(droppedCount(helper, abs, Items.STONE, 1.5D) == 7, "7 stone dropped by the OP break");
        discardDrops(helper, abs, 1.5D);
        helper.succeed();
    }

    // ============================================================
    // 8. 爆炸不摧毁
    // ============================================================

    /**
     * 走原版爆炸结算 (Explosion.finalizeExplosion): 把箱子与一块对照石头都强行塞进"要炸掉"的名单, 石头消失、
     * 箱子与仓库原样、不掉箱子物品。不用 level.explode: 那条路径还会伤害同批次其它用例的模拟玩家。
     * 反例: 删掉 DonationBoxBlock.onBlockExploded 覆写, 箱子被炸成空气。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void explosionsNeitherDestroyNorDuplicateTheBox(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DonationBoxBlockEntity box = placeBox(helper, null);
        box.storage().setStackInSlot(0, new ItemStack(Items.STONE, 32));
        BlockPos abs = helper.absolutePos(BOX);
        BlockPos control = helper.absolutePos(BOX.east());
        helper.setBlock(BOX.east(), Blocks.STONE.defaultBlockState());
        BlockState state = level.getBlockState(abs);

        Explosion probe = new Explosion(level, null, abs.getX() + 0.5D, abs.getY() + 0.5D, abs.getZ() + 0.5D,
                1000.0F, false, Explosion.BlockInteraction.DESTROY);
        helper.assertTrue(state.getExplosionResistance(level, abs, probe) >= DonationRegistry.EXPLOSION_RESISTANCE,
                "explosion resistance must be obsidian-grade");
        helper.assertFalse(state.canDropFromExplosion(level, abs, probe), "an explosion must never drop the box");

        Explosion blast = new Explosion(level, null, abs.getX() + 0.5D, abs.getY() + 0.5D, abs.getZ() + 1.5D,
                1000.0F, false, Explosion.BlockInteraction.DESTROY);
        blast.getToBlow().addAll(List.of(abs, control));
        blast.finalizeExplosion(false);

        helper.assertTrue(level.getBlockState(control).isAir(), "control: an ordinary block in the blast list is destroyed");
        helper.assertTrue(level.getBlockState(abs).is(DonationRegistry.DONATION_BOX.get()), "the box must survive");
        helper.assertTrue(level.getBlockEntity(abs) == box && box.storage().count(Items.STONE) == 32,
                "the same block entity with its storage must survive");
        helper.assertTrue(droppedCount(helper, abs, DonationRegistry.DONATION_BOX_ITEM.get(), 3.0D) == 0
                        && droppedCount(helper, abs, Items.STONE, 3.0D) == 0, "the blast must not drop the box or its contents");
        discardDrops(helper, abs, 3.0D);
        helper.succeed();
    }

    // ============================================================
    // 9. 协管: 添加后可取, 移除后界面被关、不可再取
    // ============================================================

    /** 反例: 删掉 removeCoAdmin 里的 closeUnauthorizedViewers 与 clicked 的权限重查, 被移除者还能继续取物。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void coAdminCanWithdrawUntilRemovedThenIsLockedOut(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "own", 0);
        ServerPlayer co = player(helper, "coa", 0);
        MinecraftServer server = helper.getLevel().getServer();
        DonationBoxBlockEntity box = placeBox(helper, owner);
        box.storage().setStackInSlot(0, new ItemStack(Items.STONE, 40));
        box.storage().setStackInSlot(1, new ItemStack(Items.DIRT, 20));

        helper.assertTrue(use(helper, co) instanceof DonationDonorMenu, "before being added the player only donates");
        co.closeContainer();

        DonationBoxService.Outcome added = DonationBoxService.addCoAdmin(box, server, owner, co.getGameProfile().getName());
        helper.assertTrue(added.ok() && box.isCoAdmin(co.getUUID()), "owner adds the co-admin by name, got " + added.status());
        AbstractContainerMenu opened = use(helper, co);
        helper.assertTrue(opened instanceof DonationManagerMenu, "a co-admin must get the manager menu, got " + opened);
        DonationManagerMenu menu = (DonationManagerMenu) opened;
        menu.clicked(0, 0, ClickType.QUICK_MOVE, co);
        helper.assertTrue(co.getInventory().countItem(Items.STONE) == 40 && box.storage().count(Items.STONE) == 0,
                "the co-admin withdraws all 40 stone");

        DonationBoxService.Outcome removed = DonationBoxService.removeCoAdmin(box, server, owner, co.getGameProfile().getName());
        helper.assertTrue(removed.ok() && !box.isCoAdmin(co.getUUID()), "owner removes the co-admin, got " + removed.status());
        helper.assertTrue(co.containerMenu == co.inventoryMenu, "the open manager menu must be closed on removal");
        helper.assertFalse(menu.stillValid(co), "the stale menu must no longer be valid");
        menu.clicked(1, 0, ClickType.QUICK_MOVE, co);
        helper.assertTrue(box.storage().count(Items.DIRT) == 20 && co.getInventory().countItem(Items.DIRT) == 0,
                "clicks on the stale menu must be ignored");
        assertEntry(helper, box.ledger().entriesNewestFirst().get(0), co.getUUID(),
                DonationLedger.Action.WITHDRAW, Items.STONE, 40);

        helper.assertTrue(use(helper, co) instanceof DonationDonorMenu, "after removal the player only donates again");
        co.closeContainer();
        helper.succeed();
    }

    /**
     * 协管名单: 上限 8、离线玩家经 profile cache 解析、解析不到明确失败、只有箱主或 OP 能改。
     *
     * GameTest 服务端本身不带 profile cache (getProfileCache 为 null), 所以这里新建一个真实的 GameProfileCache
     * 实例 (落在临时文件, 仓库接口是空实现, 保证不会发出任何网络请求), 经与业务相同的
     * {@link DonationProfiles#resolve} 路径解析。反例: 去掉 resolve 里的缓存查找, 离线玩家解析失败。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void coAdminListCapsAtEightResolvesCachedNamesAndRejectsUnknown(GameTestHelper helper) throws IOException {
        ServerPlayer owner = player(helper, "own", 0);
        ServerPlayer stranger = player(helper, "str", 0);
        ServerPlayer operator = player(helper, "opr", 2);
        MinecraftServer server = helper.getLevel().getServer();
        DonationBoxBlockEntity box = placeBox(helper, owner);

        File cacheFile = new File(System.getProperty("java.io.tmpdir"), "dbx-usercache-" + UUID.randomUUID() + ".json");
        try {
            GameProfileCache cache = new GameProfileCache((names, agent, callback) -> {
            }, cacheFile);
            DonationProfiles.Resolver resolver = name -> DonationProfiles.resolve(server.getPlayerList(), cache, name);
            GameProfile offline = new GameProfile(UUID.randomUUID(), uniqueName("off"));
            cache.add(offline);
            helper.assertTrue(server.getPlayerList().getPlayer(offline.getId()) == null, "the cached player is offline");

            DonationBoxService.Outcome viaCache = DonationBoxService.addCoAdmin(box, resolver, owner,
                    offline.getName().toUpperCase(Locale.ROOT));
            helper.assertTrue(viaCache.ok() && box.isCoAdmin(offline.getId()),
                    "an offline player known to the profile cache resolves by name, got " + viaCache.status());
            helper.assertTrue(offline.getName().equals(box.coAdmins().get(offline.getId())), "the cached spelling is stored");

            assertStatus(helper, DonationBoxService.addCoAdmin(box, resolver, owner, uniqueName("zzz")),
                    DonationBoxService.Status.NOT_FOUND, "a name nobody on this server has used");
            assertStatus(helper, DonationBoxService.addCoAdmin(box, resolver, owner, "abcdefghijklmnopq"),
                    DonationBoxService.Status.NOT_FOUND, "a 17-character name");
            assertStatus(helper, DonationBoxService.addCoAdmin(box, resolver, stranger, stranger.getGameProfile().getName()),
                    DonationBoxService.Status.NO_PERMISSION, "a stranger editing the list");
            assertStatus(helper, DonationBoxService.addCoAdmin(box, resolver, owner, owner.getGameProfile().getName()),
                    DonationBoxService.Status.IS_OWNER, "the owner as co-admin");
            assertStatus(helper, DonationBoxService.addCoAdmin(box, resolver, owner, offline.getName()),
                    DonationBoxService.Status.ALREADY_PRESENT, "a duplicate");
            helper.assertFalse(box.isCoAdmin(stranger.getUUID()), "failed edits must not change the list");

            for (int i = 0; i < DonationBoxBlockEntity.MAX_CO_ADMINS - 1; i++) {
                helper.assertTrue(box.addCoAdmin(UUID.randomUUID(), "filler" + i) == DonationBoxBlockEntity.CoAdminChange.ADDED,
                        "filler " + i + " fits under the cap");
            }
            helper.assertTrue(box.coAdmins().size() == DonationBoxBlockEntity.MAX_CO_ADMINS, "the list is full");
            assertStatus(helper, DonationBoxService.addCoAdmin(box, resolver, operator, stranger.getGameProfile().getName()),
                    DonationBoxService.Status.LIMIT_REACHED, "a ninth co-admin");
            helper.assertTrue(box.coAdmins().size() == DonationBoxBlockEntity.MAX_CO_ADMINS
                    && !box.isCoAdmin(stranger.getUUID()), "the cap must hold");

            assertStatus(helper, DonationBoxService.removeCoAdmin(box, resolver, operator, offline.getName()),
                    DonationBoxService.Status.OK, "an OP removing an offline co-admin by stored name");
            assertStatus(helper, DonationBoxService.removeCoAdmin(box, resolver, operator, offline.getName()),
                    DonationBoxService.Status.NOT_PRESENT, "removing twice");
            helper.assertTrue(box.coAdmins().size() == DonationBoxBlockEntity.MAX_CO_ADMINS - 1, "one slot freed");
        } finally {
            Files.deleteIfExists(cacheFile.toPath());
        }
        helper.succeed();
    }

    // ============================================================
    // 10. 漏斗: 过白名单输入, 任何面抽不出
    // ============================================================

    /** 反例: 让 getCapability 对 DOWN 也返回视图, 并把 extractItem 转发给仓库, 下方漏斗会把石头抽走。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 200)
    public static void hoppersFeedWhitelistedBlocksButNothingCanBeExtracted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DonationBoxBlockEntity box = placeBox(helper, null);
        BlockPos above = BOX.above();
        BlockPos below = BOX.below();
        helper.setBlock(above, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        helper.setBlock(below, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        HopperBlockEntity upper = (HopperBlockEntity) level.getBlockEntity(helper.absolutePos(above));
        HopperBlockEntity lower = (HopperBlockEntity) level.getBlockEntity(helper.absolutePos(below));
        helper.assertTrue(upper != null && lower != null, "both hoppers must exist");
        upper.setItem(0, new ItemStack(Items.DIAMOND, 1));
        upper.setItem(1, new ItemStack(Items.STONE, 3));

        IItemHandler side = box.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).orElse(null);
        helper.assertTrue(side != null, "side input must be exposed");
        helper.assertTrue(side.insertItem(0, new ItemStack(Items.DIAMOND), true).is(Items.DIAMOND),
                "a non-block item is refused even in simulation");
        helper.assertTrue(side.insertItem(0, new ItemStack(Items.OAK_PLANKS, 5), true).isEmpty(),
                "a whitelisted block is accepted in simulation");
        helper.assertTrue(box.storage().usedSlots() == 0 && box.ledger().pendingAutomation().isEmpty(),
                "simulation changes nothing");

        ResourceLocation stoneId = ForgeRegistries.ITEMS.getKey(Items.STONE);
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(box.storage().count(Items.STONE) == 3,
                    "the hopper above must push all 3 stone in, got " + box.storage().count(Items.STONE));
            helper.assertTrue(upper.getItem(0).is(Items.DIAMOND) && upper.getItem(0).getCount() == 1,
                    "the diamond must stay in the upper hopper");
            helper.assertTrue(upper.getItem(1).isEmpty(), "no stone left in the upper hopper");
            helper.assertTrue(lower.isEmpty(), "the hopper below must never pull anything out");
            helper.assertTrue(Integer.valueOf(3).equals(box.ledger().pendingAutomation().get(stoneId)),
                    "the 3 hopper transfers aggregate into one pending row, got " + box.ledger().pendingAutomation());
            long now = level.getGameTime();
            helper.assertFalse(box.flushAutomationIfDue(now), "the aggregation window is still open");
            helper.assertTrue(box.flushAutomationIfDue(now + DonationLedger.AUTOMATION_WINDOW_TICKS), "the window closes");
            DonationLedger.Entry entry = box.ledger().entriesNewestFirst().get(0);
            helper.assertTrue(entry.automation() && entry.itemId().equals(stoneId) && entry.count() == 3
                            && entry.action() == DonationLedger.Action.DEPOSIT,
                    "one automation row for 3 stone, got " + entry);
            helper.assertTrue(box.ledger().automationDeposited() == 3 && box.ledger().pendingAutomation().isEmpty(),
                    "automation total is 3 and nothing is pending");
            helper.succeed();
        });
    }

    // ============================================================
    // 11. 流水聚合、累计与 NBT 往返
    // ============================================================

    /** 反例: 把 DonationSession 的 merge 改成逐次追加 (不聚合), 捐赠者三次 Shift 会落成三条而不是一条。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ledgerAggregatesSessionsTracksTotalsAndSurvivesNbt(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "own", 0);
        ServerPlayer donor = player(helper, "don", 0);
        ServerPlayer co = player(helper, "coa", 0);
        DonationBoxBlockEntity box = placeBox(helper, owner);
        box.addCoAdmin(co.getUUID(), co.getGameProfile().getName());

        donor.getInventory().setItem(0, new ItemStack(Items.STONE, 10));
        donor.getInventory().setItem(1, new ItemStack(Items.STONE, 20));
        donor.getInventory().setItem(2, new ItemStack(Items.STONE, 5));
        DonationDonorMenu donorMenu = (DonationDonorMenu) use(helper, donor);
        for (int i = 0; i < 3; i++) {
            donorMenu.clicked(menuSlotOf(donorMenu, i), 0, ClickType.QUICK_MOVE, donor);
            donorMenu.broadcastChanges();
        }
        int before = box.ledger().size();
        donor.closeContainer();
        helper.assertTrue(box.ledger().size() == before + 1, "three shift-clicks in one session make one row");
        assertEntry(helper, box.ledger().entriesNewestFirst().get(0), donor.getUUID(),
                DonationLedger.Action.DEPOSIT, Items.STONE, 35);

        owner.getInventory().setItem(0, new ItemStack(Items.DIRT, 5));
        DonationManagerMenu manager = (DonationManagerMenu) use(helper, owner);
        manager.clicked(menuSlotOf(manager, 0), 0, ClickType.QUICK_MOVE, owner);
        int stoneSlot = storageSlotOf(box, Items.STONE);
        manager.clicked(stoneSlot, 1, ClickType.PICKUP, owner);
        manager.clicked(menuSlotOf(manager, 5), 0, ClickType.PICKUP, owner);
        manager.clicked(stoneSlot, 0, ClickType.QUICK_MOVE, owner);
        helper.assertTrue(owner.getInventory().countItem(Items.STONE) == 35, "the owner withdrew 35 stone in two clicks");
        owner.closeContainer();

        List<DonationLedger.Entry> newest = box.ledger().entriesNewestFirst();
        assertEntry(helper, newest.get(0), owner.getUUID(), DonationLedger.Action.WITHDRAW, Items.STONE, 35);
        assertEntry(helper, newest.get(1), owner.getUUID(), DonationLedger.Action.DEPOSIT, Items.DIRT, 5);
        assertTotals(helper, box, donor, 35, 0);
        assertTotals(helper, box, owner, 5, 35);

        IItemHandler automation = box.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.UP).orElse(null);
        helper.assertTrue(automation != null, "automation input missing");
        ItemHandlerHelper.insertItem(automation, new ItemStack(Items.COBBLESTONE, 7), false);
        ItemHandlerHelper.insertItem(automation, new ItemStack(Items.COBBLESTONE, 5), false);
        ResourceLocation cobbleId = ForgeRegistries.ITEMS.getKey(Items.COBBLESTONE);
        helper.assertTrue(Integer.valueOf(12).equals(box.ledger().pendingAutomation().get(cobbleId)),
                "two automation inserts aggregate to 12 pending");
        long gameTime = helper.getLevel().getGameTime();
        helper.assertFalse(box.flushAutomationIfDue(gameTime), "the window just opened");

        CompoundTag saved = box.saveWithoutMetadata();
        DonationBoxBlockEntity copy = new DonationBoxBlockEntity(box.getBlockPos(), box.getBlockState());
        copy.load(saved);
        helper.assertTrue(copy.ledger().entriesOldestFirst().equals(box.ledger().entriesOldestFirst()), "entries round-trip");
        helper.assertTrue(copy.ledger().totals().equals(box.ledger().totals()), "totals round-trip");
        helper.assertTrue(copy.ledger().pendingAutomation().equals(box.ledger().pendingAutomation()), "pending automation round-trips");
        helper.assertTrue(owner.getUUID().equals(copy.ownerId()) && box.ownerName().equals(copy.ownerName()), "owner round-trips");
        helper.assertTrue(copy.coAdmins().equals(box.coAdmins()), "co-admins round-trip");
        for (int slot = 0; slot < DonationStorage.SLOTS; slot++) {
            helper.assertTrue(ItemStack.matches(copy.storage().getStackInSlot(slot), box.storage().getStackInSlot(slot)),
                    "storage slot " + slot + " round-trips");
        }

        long closesAt = gameTime + DonationLedger.AUTOMATION_WINDOW_TICKS;
        helper.assertTrue(copy.ledger().automationDue(closesAt) && !copy.ledger().automationDue(closesAt - 1),
                "the persisted window closes at exactly the same tick on the reloaded copy");
        helper.assertTrue(box.flushAutomationIfDue(closesAt), "the window closes");
        DonationLedger.Entry auto = box.ledger().entriesNewestFirst().get(0);
        helper.assertTrue(auto.automation() && auto.itemId().equals(cobbleId) && auto.count() == 12,
                "one automation row for 12 cobblestone, got " + auto);
        helper.assertTrue(box.ledger().automationDeposited() == 12 && box.ledger().pendingAutomation().isEmpty(),
                "automation total is 12 and nothing is pending");

        DonationLedger ring = new DonationLedger();
        UUID actor = UUID.randomUUID();
        ResourceLocation stoneId = ForgeRegistries.ITEMS.getKey(Items.STONE);
        long expected = 0L;
        for (int i = 1; i <= DonationLedger.MAX_ENTRIES + 5; i++) {
            ring.recordSession(actor, "ring", Map.of(stoneId, i), Map.of(), i);
            expected += i;
        }
        helper.assertTrue(ring.size() == DonationLedger.MAX_ENTRIES, "the ring buffer caps at " + DonationLedger.MAX_ENTRIES);
        helper.assertTrue(ring.entriesOldestFirst().get(0).count() == 6, "the five oldest rows were dropped");
        long ringTotal = ring.totalsOf(actor).map(DonationLedger.Totals::deposited).orElse(-1L);
        helper.assertTrue(ringTotal == expected, "totals are not truncated by the ring, expected " + expected + " got " + ringTotal);
        helper.succeed();
    }

    // ============================================================
    // 12. OP 转让箱主
    // ============================================================

    /** 反例: 去掉 transfer 子命令的 requires, 普通箱主也能把箱子转走。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void operatorTransfersOwnershipByCommand(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "own", 0);
        ServerPlayer heir = player(helper, "hei", 0);
        ServerPlayer operator = player(helper, "opr", 2);
        MinecraftServer server = helper.getLevel().getServer();
        Commands commands = server.getCommands();
        ServerPlayer alt = player(helper, "alt", 0);
        DonationBoxBlockEntity box = placeBox(helper, owner);
        box.addCoAdmin(heir.getUUID(), heir.getGameProfile().getName());
        box.addCoAdmin(alt.getUUID(), alt.getGameProfile().getName());
        BlockPos abs = helper.absolutePos(BOX);
        String target = "donationbox transfer " + abs.getX() + " " + abs.getY() + " " + abs.getZ() + " ";

        int denied = commands.performPrefixedCommand(owner.createCommandSourceStack(), target + heir.getGameProfile().getName());
        helper.assertTrue(denied == 0 && owner.getUUID().equals(box.ownerId()), "a non-OP owner cannot transfer the box");

        int done = commands.performPrefixedCommand(operator.createCommandSourceStack(), target + heir.getGameProfile().getName());
        helper.assertTrue(done == 1, "the OP transfer must succeed, result " + done);
        helper.assertTrue(heir.getUUID().equals(box.ownerId()) && heir.getGameProfile().getName().equals(box.ownerName()),
                "the heir owns the box");
        helper.assertFalse(box.isCoAdmin(heir.getUUID()), "the new owner no longer occupies a co-admin slot");
        helper.assertFalse(box.canManage(owner) || box.canBreak(owner), "the previous owner loses all rights");
        // 反例: 转让只换箱主不清协管, 原箱主预先加的小号在交接后仍能整仓取物。
        helper.assertTrue(box.coAdmins().isEmpty(), "the transfer clears every co-admin the previous owner appointed");
        helper.assertFalse(box.canManage(alt), "a co-admin appointed by the previous owner loses access");
        helper.assertTrue(box.canBreak(heir), "the new owner can break the box");
        helper.assertTrue(helper.getLevel().getBlockState(abs).getDestroyProgress(owner, helper.getLevel(), abs) == 0.0F,
                "the previous owner's dig progress is 0");

        int unknown = commands.performPrefixedCommand(operator.createCommandSourceStack(), target + uniqueName("zzz"));
        helper.assertTrue(unknown == 0 && heir.getUUID().equals(box.ownerId()), "an unknown name changes nothing");

        int back = commands.performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                target + owner.getGameProfile().getName());
        helper.assertTrue(back == 1 && owner.getUUID().equals(box.ownerId()), "the console can hand it back");
        helper.succeed();
    }

    // ============================================================
    // 13. 无箱主的箱子只归 OP; 假玩家不算放置者; 区块同步不泄露仓库
    // ============================================================

    /** 反例: setPlacedBy 不排除 FakePlayer, 机器放下的箱子会记一个谁都不是的箱主。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ownerlessBoxIsOperatorOnlyAndClientSyncCarriesOnlyTheOwner(GameTestHelper helper) {
        ServerPlayer player = player(helper, "pla", 0);
        ServerPlayer operator = player(helper, "opr", 2);
        ServerLevel level = helper.getLevel();

        DonationBoxBlockEntity owned = placeBox(helper, player);
        owned.storage().setStackInSlot(0, new ItemStack(Items.STONE, 3));
        owned.addCoAdmin(operator.getUUID(), operator.getGameProfile().getName());
        CompoundTag update = owned.getUpdateTag();
        helper.assertTrue(update.hasUUID("OwnerId"), "clients learn the owner (for dig progress)");
        helper.assertFalse(update.contains("Storage") || update.contains("Ledger") || update.contains("CoAdmins"),
                "clients never receive storage, ledger or co-admins: " + update.getAllKeys());
        owned.storage().setStackInSlot(0, ItemStack.EMPTY);

        helper.setBlock(BOX, Blocks.AIR.defaultBlockState());
        DonationBoxBlockEntity ownerless = placeBox(helper, null);
        helper.assertTrue(ownerless.ownerId() == null, "a non-player placement has no owner");
        helper.assertFalse(ownerless.canManage(player) || ownerless.canBreak(player), "ordinary players have no rights");
        helper.assertTrue(ownerless.canManage(operator) && ownerless.canBreak(operator), "OPs manage ownerless boxes");
        helper.assertTrue(use(helper, operator) instanceof DonationManagerMenu, "the OP gets the manager menu");
        operator.closeContainer();
        helper.assertTrue(use(helper, player) instanceof DonationDonorMenu, "the player only donates");
        player.closeContainer();

        helper.setBlock(BOX, Blocks.AIR.defaultBlockState());
        FakePlayer machine = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "dbx_machine"));
        BlockState state = DonationRegistry.DONATION_BOX.get().defaultBlockState();
        helper.setBlock(BOX, state);
        BlockPos abs = helper.absolutePos(BOX);
        state.getBlock().setPlacedBy(level, abs, state, machine, new ItemStack(DonationRegistry.DONATION_BOX_ITEM.get()));
        DonationBoxBlockEntity machinePlaced = requireBox(helper);
        helper.assertTrue(machinePlaced.ownerId() == null, "a fake player placement has no owner");
        use(helper, machine);
        helper.assertTrue(machine.containerMenu == machine.inventoryMenu, "fake players cannot open either menu");
        helper.succeed();
    }

    // ============================================================
    // 14. 创造模式中键选取不带出仓库
    // ============================================================

    /**
     * 走真实的创造模式物品栏包: 客户端 Ctrl+鼠标中键发来带坐标的 BlockEntityTag, 服务端
     * ServerGamePacketListenerImpl.handleSetCreativeModeSlot 会回调服务端方块实体的 saveToItem。物品里不得出现
     * 仓库、流水与协管; OP 放下这个物品得到的是一个空仓新箱, 原箱不变。
     * 反例: 删掉 DonationBoxBlockEntity.saveToItem 覆写, 物品带上 Storage, 新箱凭空多出 64 石头。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void creativePickBlockNeitherLeaksNorCopiesTheStorage(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "own", 0);
        ServerPlayer operator = player(helper, "opr", 2);
        ServerLevel level = helper.getLevel();
        DonationBoxBlockEntity box = placeBox(helper, owner);
        box.storage().setStackInSlot(0, new ItemStack(Items.STONE, 64));
        box.addCoAdmin(UUID.randomUUID(), "dbxhelper");
        box.ledger().recordSession(owner.getUUID(), owner.getGameProfile().getName(),
                Map.of(ForgeRegistries.ITEMS.getKey(Items.STONE), 64), Map.of(), 1L);
        BlockPos abs = helper.absolutePos(BOX);

        operator.setGameMode(GameType.CREATIVE);
        ItemStack picked = new ItemStack(DonationRegistry.DONATION_BOX_ITEM.get());
        CompoundTag clientTag = new CompoundTag();
        clientTag.putInt("x", abs.getX());
        clientTag.putInt("y", abs.getY());
        clientTag.putInt("z", abs.getZ());
        BlockItem.setBlockEntityData(picked, DonationRegistry.DONATION_BOX_BE.get(), clientTag);
        operator.connection.handleSetCreativeModeSlot(
                new ServerboundSetCreativeModeSlotPacket(InventoryMenu.USE_ROW_SLOT_START, picked));

        ItemStack held = operator.getInventory().getItem(0);
        helper.assertTrue(held.is(DonationRegistry.DONATION_BOX_ITEM.get()), "the creative slot packet must land, got " + held);
        CompoundTag carried = BlockItem.getBlockEntityData(held);
        helper.assertTrue(carried == null
                        || !(carried.contains("Storage") || carried.contains("Ledger") || carried.contains("CoAdmins")),
                "the picked item must not carry storage, ledger or co-admins: " + carried);

        BlockPos floor = BOX.east().below();
        helper.setBlock(floor, Blocks.STONE.defaultBlockState());
        BlockPos floorAbs = helper.absolutePos(floor);
        InteractionResult placed = held.useOn(new UseOnContext(operator, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(floorAbs).add(0.0D, 0.5D, 0.0D), Direction.UP, floorAbs, false)));
        helper.assertTrue(placed.consumesAction(), "the OP places the picked box, got " + placed);
        BlockPos copyPos = helper.absolutePos(BOX.east());
        helper.assertTrue(level.getBlockEntity(copyPos) instanceof DonationBoxBlockEntity copy
                        && copy.storage().usedSlots() == 0 && copy.ledger().size() == 0 && copy.coAdmins().isEmpty(),
                "the placed copy must start empty");
        helper.assertTrue(box.storage().count(Items.STONE) == 64, "the original keeps its storage");
        helper.succeed();
    }

    // ============================================================
    // 15. /clone ... move 是纯移动; /setblock replace 与原版容器一样直接清空
    // ============================================================

    /**
     * /clone ... replace move 先对源方块实体取快照, 再 Clearable.tryClear 源、把源换成屏障, 目标从快照读回。
     * 箱子实现 Clearable 后源位置清空时不散落, 这是一次纯移动。/setblock ... replace 同样先 tryClear, 仓库被清空
     * 而不是散落 (原版容器语义)。
     * 反例: 去掉 DonationBoxBlockEntity 的 Clearable, 源位置掉出 20 石头 + 7 泥土, 目标箱里还有同样一份。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cloneMoveRelocatesTheBoxWithoutDuplicatingItsStorage(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "own", 0);
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        DonationBoxBlockEntity box = placeBox(helper, owner);
        box.storage().setStackInSlot(0, new ItemStack(Items.STONE, 20));
        box.storage().setStackInSlot(5, new ItemStack(Items.DIRT, 7));
        BlockPos src = helper.absolutePos(BOX);
        BlockPos dst = helper.absolutePos(BOX.east());
        String from = src.getX() + " " + src.getY() + " " + src.getZ();
        String to = dst.getX() + " " + dst.getY() + " " + dst.getZ();

        int moved = server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                "clone " + from + " " + from + " " + to + " replace move");
        helper.assertTrue(moved == 1, "clone must move exactly one block, got " + moved);
        helper.assertTrue(level.getBlockState(src).isAir(), "the source position is emptied");
        helper.assertTrue(level.getBlockEntity(dst) instanceof DonationBoxBlockEntity, "the box arrives at the destination");
        DonationBoxBlockEntity target = (DonationBoxBlockEntity) level.getBlockEntity(dst);
        helper.assertTrue(target.storage().count(Items.STONE) == 20 && target.storage().count(Items.DIRT) == 7,
                "the storage moves along exactly once");
        helper.assertTrue(owner.getUUID().equals(target.ownerId()), "the owner moves along");
        helper.assertTrue(droppedCount(helper, src, Items.STONE, 3.0D) == 0 && droppedCount(helper, src, Items.DIRT, 3.0D) == 0,
                "nothing may scatter at the source: that would be a second copy");

        int replaced = server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                "setblock " + to + " minecraft:stone replace");
        helper.assertTrue(replaced == 1 && level.getBlockState(dst).is(Blocks.STONE), "setblock replaces the box");
        helper.assertTrue(droppedCount(helper, dst, Items.STONE, 3.0D) == 0 && droppedCount(helper, dst, Items.DIRT, 3.0D) == 0,
                "setblock replace clears the storage like a vanilla chest instead of scattering it");
        discardDrops(helper, src, 3.0D);
        helper.succeed();
    }

    // ============================================================
    // 16. 信息显示模组读不到仓库
    // ============================================================

    /**
     * Jade (服务端 11.13.2) 的物品存储提示: 对 CapabilityProvider 调 getCapability(ITEM_HANDLER) (即空面),
     * 逐格 getStackInSlot 汇总后发给看向方块的玩家, 排除条件只有战利品箱、上锁容器与末影箱。这里按它的读法
     * 原样读一遍, 必须一件都读不到; 带面的自动化输入照常可用。
     * 反例: getCapability 对 null 面也返回自动化视图, 读出 64 石头。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sidelessQueriesLikeJadeCannotReadTheStorage(GameTestHelper helper) {
        DonationBoxBlockEntity box = placeBox(helper, player(helper, "own", 0));
        box.storage().setStackInSlot(0, new ItemStack(Items.STONE, 64));

        int visible = 0;
        IItemHandler probe = box.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (probe != null) {
            for (int slot = 0; slot < probe.getSlots(); slot++) {
                visible += probe.getStackInSlot(slot).getCount();
            }
        }
        helper.assertTrue(probe == null && visible == 0,
                "a sideless query (Jade, The One Probe) must see no item handler, saw " + visible + " items");

        IItemHandler side = box.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.EAST).orElse(null);
        helper.assertTrue(side != null && side.insertItem(1, new ItemStack(Items.STONE, 1), false).isEmpty(),
                "sided automation input still works");
        helper.assertTrue(box.storage().count(Items.STONE) == 65, "the sided insert landed in storage");
        helper.succeed();
    }

    // ============================================================
    // 17. 拆箱审计: 谁拆的、倒出了什么
    // ============================================================

    /**
     * 拆箱不经过管理界面的逐次记账, 是唯一一次倒出整仓的路径: 审计汇总行必须写明破坏者 (这里是 OP 拆别人的箱子)
     * 与箱主, 并按物品逐行记件数 (同种物品的多组合并); 非玩家移除 (/setblock destroy、其它模组的 destroyBlock)
     * 记为 non-player。断言的是方块实体留档的那几行, 与写进 miningdim/donation 日志的是同一份字符串。
     * 反例: onRemove 恢复成 by=- 且只写一行汇总, 身份与明细断言即挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void removalAuditNamesTheBreakerAndItemizesTheDrops(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "own", 0);
        ServerPlayer operator = player(helper, "opr", 2);
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(BOX);
        String stoneId = String.valueOf(ForgeRegistries.ITEMS.getKey(Items.STONE));
        String dirtId = String.valueOf(ForgeRegistries.ITEMS.getKey(Items.DIRT));

        DonationBoxBlockEntity box = placeBox(helper, owner);
        box.storage().setStackInSlot(0, new ItemStack(Items.STONE, 10));
        box.storage().setStackInSlot(4, new ItemStack(Items.STONE, 5));
        box.storage().setStackInSlot(9, new ItemStack(Items.DIRT, 3));
        helper.assertTrue(operator.gameMode.destroyBlock(abs), "the OP breaks the owner's box");
        List<String> lines = box.removalAudit();
        String opName = operator.getGameProfile().getName();
        String opDescription = DonationAudit.describe(operator.getUUID(), opName);
        String ownerDescription = DonationAudit.describe(owner.getUUID(), owner.getGameProfile().getName());
        helper.assertTrue(lines.size() == 3, "one summary plus one line per item, got " + lines);
        helper.assertTrue(lines.get(0).contains("REMOVED by=" + opDescription + " owner=" + ownerDescription)
                        && lines.get(0).contains("dropped_stacks=3 dropped_items=18"),
                "the summary names the breaker and the owner, got " + lines.get(0));
        helper.assertTrue(lines.stream().anyMatch(line -> line.endsWith("REMOVED_DROP actor=" + opName + " uuid="
                        + operator.getUUID() + " item=" + stoneId + " count=15")),
                "the two stone stacks merge into one itemized line under the breaker, got " + lines);
        helper.assertTrue(lines.stream().anyMatch(line -> line.endsWith("REMOVED_DROP actor=" + opName + " uuid="
                        + operator.getUUID() + " item=" + dirtId + " count=3")),
                "dirt is itemized under the breaker, got " + lines);
        discardDrops(helper, abs, 1.5D);

        DonationBoxBlockEntity second = placeBox(helper, owner);
        second.storage().setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 4));
        helper.assertTrue(level.destroyBlock(abs, true), "a non-player path removes the box");
        List<String> machine = second.removalAudit();
        helper.assertTrue(machine.size() == 2 && machine.get(0).contains("REMOVED by=" + DonationAudit.NON_PLAYER + " owner=")
                        && machine.get(1).endsWith("REMOVED_DROP actor=" + DonationAudit.NON_PLAYER + " uuid=- item="
                        + ForgeRegistries.ITEMS.getKey(Items.COBBLESTONE) + " count=4"),
                "a non-player removal is attributed to non-player, got " + machine);
        discardDrops(helper, abs, 1.5D);
        helper.succeed();
    }

    // ============================================================
    // 18. 账本累计表服务端分页
    // ============================================================

    /**
     * 累计表每个存取过的玩家一行、没有上限, 由服务端分页下发。按放入量降序时, 只取不放的人 (通常是协管) 排在
     * 最后: 41 名捐赠者之外的这一行必须能翻到, 页脚的总人数是真实人数; 越界页码夹回最后一页; 快照经网络编码
     * 往返后不变。
     * 反例: 恢复"只下发前 40 行"的截断, 只取不放的那一行在任何一页都找不到, totalsCount 也对不上。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ledgerTotalsArePagedServerSideSoWithdrawOnlyRowsStayVisible(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "own", 0);
        DonationBoxBlockEntity box = placeBox(helper, owner);
        ResourceLocation stone = ForgeRegistries.ITEMS.getKey(Items.STONE);
        int donors = 41;
        for (int i = 0; i < donors; i++) {
            box.ledger().recordSession(UUID.randomUUID(), "donor" + i, Map.of(stone, 100 + i), Map.of(), i);
        }
        box.ledger().recordSession(UUID.randomUUID(), "withdrawer", Map.of(), Map.of(stone, 2000), 99L);
        int people = donors + 1;
        int pages = (people + DonationView.TOTALS_PAGE_SIZE - 1) / DonationView.TOTALS_PAGE_SIZE;

        DonationView first = DonationView.build(box, owner, 0, 0);
        helper.assertTrue(first.totalsCount() == people && first.totalsPageCount() == pages
                        && first.totals().size() == DonationView.TOTALS_PAGE_SIZE,
                "page 1 of " + pages + " with " + people + " people, got " + first.totalsPage() + "/"
                        + first.totalsPageCount() + " count " + first.totalsCount() + " rows " + first.totals().size());
        List<String> seen = new ArrayList<>();
        for (int p = 0; p < pages; p++) {
            DonationView view = DonationView.build(box, owner, 0, p);
            helper.assertTrue(view.totalsPage() == p, "page " + p + " must be served as requested");
            for (DonationView.TotalsRow row : view.totals()) {
                seen.add(row.name());
            }
        }
        helper.assertTrue(seen.size() == people && new HashSet<>(seen).size() == people && seen.contains("withdrawer"),
                "every person appears exactly once across the pages, got " + seen.size());

        DonationView last = DonationView.build(box, owner, 0, 999);
        helper.assertTrue(last.totalsPage() == pages - 1 && last.totals().stream().anyMatch(row ->
                        row.name().equals("withdrawer") && row.withdrawn() == 2000 && row.deposited() == 0),
                "an out-of-range page clamps to the last one, which holds the withdraw-only row: " + last.totals());

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            last.write(buf);
            DonationView decoded = DonationView.read(buf);
            helper.assertTrue(decoded.equals(last), "the snapshot survives the wire, got " + decoded);
        } finally {
            buf.release();
        }
        helper.succeed();
    }

    // ============================================================
    // 19. 账本与协管网络包按玩家限速
    // ============================================================

    /**
     * 两个 C2S 包的成功分支都由客户端触发: 协管增删每次写一行永久审计并遍历在线玩家, 账本请求每次复制整段流水、
     * 排序累计表再回包。走与包处理相同的入口, 冷却内的第二个包被静默丢弃且不产生任何变更; 关了再开界面不重置
     * 额度; 冷却过后恢复受理。
     * 反例: 去掉 DonationNetwork.tryAcquire, 紧跟着的移除包直接生效, 协管断言即挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ledgerAndCoAdminPacketsAreRateLimitedPerPlayer(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "own", 0);
        ServerPlayer friend = player(helper, "fri", 0);
        DonationBoxBlockEntity box = placeBox(helper, owner);
        String friendName = friend.getGameProfile().getName();
        helper.assertTrue(use(helper, owner) instanceof DonationManagerMenu, "the owner opens the manager menu");

        helper.assertTrue(DonationNetwork.handleViewRequest(owner, 0, 0), "the first ledger request is served");
        helper.assertFalse(DonationNetwork.handleViewRequest(owner, 1, 0), "a ledger request inside the cooldown is dropped");
        helper.assertTrue(DonationNetwork.handleCoAdminEdit(owner, true, friendName) && box.isCoAdmin(friend.getUUID()),
                "the first co-admin edit is served");
        helper.assertFalse(DonationNetwork.handleCoAdminEdit(owner, false, friendName),
                "a co-admin edit inside the cooldown is dropped");
        helper.assertTrue(box.isCoAdmin(friend.getUUID()), "the dropped removal changed nothing");
        owner.closeContainer();
        helper.assertTrue(use(helper, owner) instanceof DonationManagerMenu, "the owner reopens the manager menu");
        helper.assertFalse(DonationNetwork.handleCoAdminEdit(owner, false, friendName),
                "reopening the menu must not reset the cooldown");
        helper.assertTrue(box.isCoAdmin(friend.getUUID()), "still nothing changed");

        helper.runAfterDelay(DonationNetwork.EDIT_COOLDOWN_TICKS + 1, () -> {
            helper.assertTrue(DonationNetwork.handleViewRequest(owner, 0, 0), "after the cooldown ledger requests are served");
            helper.assertTrue(DonationNetwork.handleCoAdminEdit(owner, false, friendName) && !box.isCoAdmin(friend.getUUID()),
                    "after the cooldown the removal goes through");
            owner.closeContainer();
            helper.succeed();
        });
    }

    // ============================================================
    // 20. /donationbox 的失败提示不泄露区块与箱子位置
    // ============================================================

    /**
     * /donationbox 根指令谁都能执行。非 OP 玩家对"别人的箱子 / 已加载的空地 / 未加载的远处"必须得到完全相同的
     * 一句失败提示 (每个子命令都一样), 否则按网格扫坐标就能测出别人的活动范围与所有捐赠箱的位置; 探测本身也不得
     * 加载区块。OP 仍拿到细分的错误, 箱主照常可用。
     * 反例: box() 恢复为 getLoadedBlockPos + NOT_A_BOX + 事后判权限, 三处回复各不相同。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void commandFailuresRevealNothingToNonOperators(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "own", 0);
        ServerPlayer stranger = player(helper, "str", 0);
        ServerPlayer operator = player(helper, "opr", 2);
        ServerLevel level = helper.getLevel();
        placeBox(helper, owner);
        BlockPos boxPos = helper.absolutePos(BOX);
        BlockPos air = helper.absolutePos(BOX.east());
        BlockPos far = boxPos.offset(160_000, 0, 160_000);
        helper.assertTrue(level.getBlockState(air).isAir(), "precondition: the neighbour is loaded empty ground");
        helper.assertFalse(level.isLoaded(far), "precondition: the far chunk is not loaded");
        String strangerName = stranger.getGameProfile().getName();

        for (String sub : List.of("info ", "log ", "coadmin list ", "coadmin add ", "coadmin remove ")) {
            String tail = sub.startsWith("coadmin a") || sub.startsWith("coadmin r") ? " " + strangerName : "";
            List<Component> atBox = runCommand(stranger, "donationbox " + sub + coords(boxPos) + tail);
            List<Component> atAir = runCommand(stranger, "donationbox " + sub + coords(air) + tail);
            List<Component> atFar = runCommand(stranger, "donationbox " + sub + coords(far) + tail);
            helper.assertTrue(atBox.size() == 1 && mentions(atBox.get(0), "message.miningdim.donation_box.no_access"),
                    sub + "on someone else's box must answer no_access only, got " + atBox);
            helper.assertTrue(atBox.equals(atAir) && atBox.equals(atFar),
                    sub + "must answer identically for a box, empty ground and an unloaded chunk: "
                            + atBox + " / " + atAir + " / " + atFar);
        }
        helper.assertFalse(level.isLoaded(far), "probing must not load the far chunk");

        helper.assertTrue(runCommand(operator, "donationbox info " + coords(far)).stream()
                .anyMatch(c -> mentions(c, "argument.pos.unloaded")), "an OP still learns the chunk is unloaded");
        helper.assertTrue(runCommand(operator, "donationbox info " + coords(air)).stream()
                .anyMatch(c -> mentions(c, "message.miningdim.donation_box.not_a_box")), "an OP still learns there is no box");
        helper.assertTrue(runCommand(owner, "donationbox info " + coords(boxPos)).stream()
                .anyMatch(c -> mentions(c, "message.miningdim.donation_box.info")), "the owner still gets the info");
        helper.succeed();
    }

    /**
     * 指令来源的权限等级不等于"是 OP"。原版告示牌的 run_command 以点击者为实体、固定权限等级 2 执行
     * (SignBlockEntity.createCommandSourceStack), FTB Quests 的 elevate_perms 命令奖励同理; 命令方块与数据包函数
     * 没有实体, 默认也是 2。这些来源都不得当作 OP / 控制台: 普通玩家点预制牌查不到别人的账, 无实体的 2 级来源
     * 既查不到账、也不能以"控制台"身份增删协管或转让箱主。真控制台 (权限 4) 与本身是 OP 的玩家照常可用。
     * 这里按原版告示牌的构造方式手工拼来源: 真告示牌的输出落在 CommandSource.NULL, 测试看不到回复。
     * 反例: privilegedSource 恢复为 source.hasPermission(2)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void elevatedSourcesAreNotOperators(GameTestHelper helper) {
        ServerPlayer owner = player(helper, "own", 0);
        ServerPlayer stranger = player(helper, "str", 0);
        ServerPlayer operator = player(helper, "opr", 2);
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        DonationBoxBlockEntity box = placeBox(helper, owner);
        BlockPos boxPos = helper.absolutePos(BOX);
        String strangerName = stranger.getGameProfile().getName();

        CommandSourceStack strangerSign = signSource(level, boxPos.east(), stranger);
        for (String sub : List.of("info ", "log ", "coadmin list ")) {
            List<Component> replies = runCommandAs(strangerSign, "donationbox " + sub + coords(boxPos));
            helper.assertTrue(replies.size() == 1 && mentions(replies.get(0), "message.miningdim.donation_box.no_access"),
                    sub + "through a sign clicked by a non-OP must answer no_access only, got " + replies);
        }
        runCommandAs(strangerSign, "donationbox transfer " + coords(boxPos) + " " + strangerName);
        helper.assertTrue(owner.getUUID().equals(box.ownerId()), "a sign clicked by a non-OP cannot transfer the box");

        CommandSourceStack commandBlockLike = server.createCommandSourceStack()
                .withPermission(DonationBoxBlockEntity.OPERATOR_PERMISSION_LEVEL);
        helper.assertTrue(commandBlockLike.getEntity() == null, "precondition: the level-2 source has no entity");
        List<Component> info = runCommandAs(commandBlockLike, "donationbox info " + coords(boxPos));
        helper.assertTrue(info.size() == 1 && mentions(info.get(0), "message.miningdim.donation_box.no_access"),
                "an entity-less level-2 source must answer no_access, got " + info);
        runCommandAs(commandBlockLike, "donationbox coadmin add " + coords(boxPos) + " " + strangerName);
        helper.assertFalse(box.isCoAdmin(stranger.getUUID()), "an entity-less level-2 source cannot add co-admins");
        runCommandAs(commandBlockLike, "donationbox transfer " + coords(boxPos) + " " + strangerName);
        helper.assertTrue(owner.getUUID().equals(box.ownerId()), "an entity-less level-2 source cannot transfer the box");

        helper.assertTrue(runCommandAs(signSource(level, boxPos.east(), operator), "donationbox info " + coords(boxPos))
                .stream().anyMatch(c -> mentions(c, "message.miningdim.donation_box.info")),
                "a sign clicked by an OP still gets the info");
        helper.assertTrue(runCommandAs(server.createCommandSourceStack(), "donationbox info " + coords(boxPos))
                .stream().anyMatch(c -> mentions(c, "message.miningdim.donation_box.info")),
                "the real console still gets the info");
        helper.succeed();
    }

    /** 与原版 SignBlockEntity.createCommandSourceStack 相同的来源: 点击者为实体, 固定权限等级 2。 */
    private static CommandSourceStack signSource(ServerLevel level, BlockPos signPos, ServerPlayer clicker) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.atCenterOf(signPos), Vec2.ZERO, level, 2,
                "Sign", Component.literal("Sign"), level.getServer(), clicker);
    }

    // ============================================================
    // 21. 协管名字解析不改写 profile cache 的最近使用顺序
    // ============================================================

    /**
     * usercache.json 落盘时只按最近访问序号保留最近 1000 人, 而 GameProfileCache.get(UUID) 命中就会改写这个序号。
     * 协管名字解析由任何箱主都能从界面触发, 必须只读: 按名字 (大小写不敏感) 解析出最久未访问的那个人、再解析一个
     * 查无此人的名字之后, 缓存落盘的顺序原样不变。用独立的真实缓存实例 (空仓库接口, 不联网)。
     * 反例: 恢复"对整张表逐个 get(UUID) 比对名字", 被解析者被挪到最前, 落盘顺序改变。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void coAdminNameLookupLeavesProfileCacheRecencyUntouched(GameTestHelper helper) throws IOException {
        MinecraftServer server = helper.getLevel().getServer();
        File cacheFile = new File(System.getProperty("java.io.tmpdir"), "dbx-usercache-" + UUID.randomUUID() + ".json");
        try {
            GameProfileCache cache = new GameProfileCache((names, agent, callback) -> {
            }, cacheFile);
            List<GameProfile> added = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                GameProfile profile = new GameProfile(UUID.randomUUID(), uniqueName("mru"));
                cache.add(profile);
                added.add(profile);
            }
            List<String> before = savedOrder(cache, cacheFile);
            GameProfile oldest = added.get(0);
            helper.assertTrue(before.size() == added.size() && before.get(before.size() - 1).equals(oldest.getName()),
                    "precondition: the cache saves newest first, got " + before);

            Optional<GameProfile> found = DonationProfiles.resolve(server.getPlayerList(), cache,
                    oldest.getName().toUpperCase(Locale.ROOT));
            helper.assertTrue(found.isPresent() && found.get().getId().equals(oldest.getId()),
                    "the least recently used cached player still resolves by name");
            helper.assertTrue(DonationProfiles.resolve(server.getPlayerList(), cache, uniqueName("zzz")).isEmpty(),
                    "an unknown name resolves to nothing");
            List<String> after = savedOrder(cache, cacheFile);
            helper.assertTrue(after.equals(before), "name lookups must not reorder the cache: " + before + " -> " + after);
        } finally {
            Files.deleteIfExists(cacheFile.toPath());
        }
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /**
     * 与 testutil 的模拟玩家同一套 EmbeddedChannel 手法, 另加唯一名字与固定权限等级; 站在箱子旁 2 格内,
     * 满足菜单的距离校验; 生存模式, 走真实的生存破坏路径。
     */
    private static ServerPlayer player(GameTestHelper helper, String role, int permissionLevel) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), uniqueName(role))) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            protected int getPermissionLevel() {
                return permissionLevel;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, player);
        player.setGameMode(GameType.SURVIVAL);
        BlockPos abs = helper.absolutePos(BOX);
        player.moveTo(abs.getX() + 0.5D, abs.getY(), abs.getZ() + 2.5D);
        return player;
    }

    private static String uniqueName(String role) {
        return "dbx" + role + Integer.toHexString(NAME_SEQUENCE.incrementAndGet())
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x100, 0x1000));
    }

    private static DonationBoxBlockEntity placeBox(GameTestHelper helper, @Nullable ServerPlayer placer) {
        BlockState state = DonationRegistry.DONATION_BOX.get().defaultBlockState();
        helper.setBlock(BOX, state);
        state.getBlock().setPlacedBy(helper.getLevel(), helper.absolutePos(BOX), state, placer,
                new ItemStack(DonationRegistry.DONATION_BOX_ITEM.get()));
        return requireBox(helper);
    }

    private static DonationBoxBlockEntity requireBox(GameTestHelper helper) {
        if (helper.getLevel().getBlockEntity(helper.absolutePos(BOX)) instanceof DonationBoxBlockEntity box) {
            return box;
        }
        throw new IllegalStateException("donation box block entity missing");
    }

    private static AbstractContainerMenu use(GameTestHelper helper, ServerPlayer player) {
        BlockPos abs = helper.absolutePos(BOX);
        BlockState state = helper.getLevel().getBlockState(abs);
        state.use(helper.getLevel(), player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false));
        return player.containerMenu;
    }

    /** 背包下标 (0-8 快捷栏, 9-35 主背包) 在菜单里的槽位号; AbstractMiningMenu 先铺主背包再铺快捷栏。 */
    private static int menuSlotOf(AbstractMiningMenu menu, int inventoryIndex) {
        int base = menu.containerSlotCount();
        return inventoryIndex >= 9 ? base + inventoryIndex - 9 : base + 27 + inventoryIndex;
    }

    private static int storageSlotOf(DonationBoxBlockEntity box, Item item) {
        for (int slot = 0; slot < DonationStorage.SLOTS; slot++) {
            if (box.storage().getStackInSlot(slot).is(item)) {
                return slot;
            }
        }
        throw new IllegalStateException("no " + item + " in storage");
    }

    private static int inputCount(DonationDonorMenu menu, Item item) {
        return menu.input().countItem(item);
    }

    private static ItemStack filledShulker() {
        ItemStack shulker = new ItemStack(Items.WHITE_SHULKER_BOX);
        CompoundTag diamonds = new ItemStack(Items.DIAMOND, 64).save(new CompoundTag());
        diamonds.putByte("Slot", (byte) 0);
        ListTag items = new ListTag();
        items.add(diamonds);
        CompoundTag blockEntityTag = new CompoundTag();
        blockEntityTag.put("Items", items);
        BlockItem.setBlockEntityData(shulker, BlockEntityType.SHULKER_BOX, blockEntityTag);
        return shulker;
    }

    private static List<ItemEntity> drops(GameTestHelper helper, BlockPos center, double radius) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(radius));
    }

    private static int droppedCount(GameTestHelper helper, BlockPos center, Item item, double radius) {
        int total = 0;
        for (ItemEntity entity : drops(helper, center, radius)) {
            if (entity.getItem().is(item)) {
                total += entity.getItem().getCount();
            }
        }
        return total;
    }

    private static void discardDrops(GameTestHelper helper, BlockPos center, double radius) {
        for (ItemEntity entity : drops(helper, center, radius)) {
            entity.discard();
        }
    }

    /** 以该玩家身份执行指令, 收集它收到的全部回复 (成功与失败)。 */
    private static List<Component> runCommand(ServerPlayer player, String command) {
        return runCommandAs(player.createCommandSourceStack(), command);
    }

    /** 以给定的指令来源执行, 收集它收到的全部成功/失败提示。 */
    private static List<Component> runCommandAs(CommandSourceStack stack, String command) {
        List<Component> messages = new ArrayList<>();
        CommandSource recorder = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                messages.add(message);
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        stack.getServer().getCommands().performPrefixedCommand(stack.withSource(recorder), command);
        return messages;
    }

    /** 让缓存落盘并按文件里的先后读回玩家名 (原版按最近访问序号从新到旧写出)。 */
    private static List<String> savedOrder(GameProfileCache cache, File file) throws IOException {
        cache.save();
        JsonArray array = JsonParser.parseString(Files.readString(file.toPath())).getAsJsonArray();
        List<String> names = new ArrayList<>();
        for (JsonElement element : array) {
            names.add(element.getAsJsonObject().get("name").getAsString());
        }
        return names;
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** 组件树里是否有以该键翻译的节点 (失败提示会被包一层红色空组件)。 */
    private static boolean mentions(Component component, String translationKey) {
        if (component.getContents() instanceof TranslatableContents translatable
                && translatable.getKey().equals(translationKey)) {
            return true;
        }
        for (Component sibling : component.getSiblings()) {
            if (mentions(sibling, translationKey)) {
                return true;
            }
        }
        return false;
    }

    private static void assertVerdict(GameTestHelper helper, ItemStack stack, DonationWhitelist.Verdict expected) {
        DonationWhitelist.Verdict actual = DonationWhitelist.judge(stack);
        helper.assertTrue(actual == expected, stack + " must be " + expected + ", got " + actual);
    }

    private static void assertStatus(GameTestHelper helper, DonationBoxService.Outcome outcome,
                                     DonationBoxService.Status expected, String what) {
        helper.assertTrue(outcome.status() == expected, what + " must yield " + expected + ", got " + outcome.status());
    }

    private static void assertEntry(GameTestHelper helper, DonationLedger.Entry entry, UUID actor,
                                    DonationLedger.Action action, Item item, int count) {
        helper.assertTrue(actor.equals(entry.actorId()) && entry.action() == action
                        && entry.itemId().equals(ForgeRegistries.ITEMS.getKey(item)) && entry.count() == count,
                "expected " + action + " " + count + " " + item + " by " + actor + ", got " + entry);
    }

    private static void assertTotals(GameTestHelper helper, DonationBoxBlockEntity box, ServerPlayer player,
                                     long deposited, long withdrawn) {
        DonationLedger.Totals totals = box.ledger().totalsOf(player.getUUID()).orElse(null);
        helper.assertTrue(totals != null && totals.deposited() == deposited && totals.withdrawn() == withdrawn,
                "totals of " + player.getGameProfile().getName() + " must be " + deposited + "/" + withdrawn + ", got " + totals);
    }
}
