package com.miningdim.district.guard.create;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.guard.GuardActors;
import com.miningdim.district.guard.GuardEvents;
import com.miningdim.district.guard.GuardSettings;
import com.miningdim.district.guard.GuardTestZones;
import com.miningdim.district.guard.WorldGuardGameTests;
import com.miningdim.district.guard.create.CreateGuardGameTests.TestCreateFakePlayer;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 放置禁令、机械动力的假玩家、denyUse 的 Forge 事件 (设计文档 22.5、22.6 ①、22.8、22.14), 同步。测试设置里 minecraft 算
 * "机械动力", 熔炉是机器、石头是装饰、箱子放行; 测试区是结构里的 (5, 5) ~ (10, 10), 外围 8 格到 x = −3 为止。放置走真的
 * {@code ItemStack.useOn} (Forge 的 onPlaceItemIntoWorld 发 EntityPlaceEvent, 取消时恢复方块快照、还回物品), 别的事件
 * 直接往 Forge 总线上发。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class CreateEventGameTests {

    private static final String BATCH = "district_create_events";
    private static final int Y = 2;
    /** 测试登记的"扳手"。不用木棍: Flan 的查看领地工具默认是木棍, 右键方块时 Flan 自己会取消事件。 */
    private static final ResourceLocation TEST_WRENCH = new ResourceLocation("minecraft", "bone");

    private CreateEventGameTests() {
    }

    @BeforeBatch(batch = BATCH)
    public static void beforeBatch(ServerLevel level) {
        GuardTestZones.begin(CreateGuardGameTests.testSettings());
    }

    @AfterBatch(batch = BATCH)
    public static void afterBatch(ServerLevel level) {
        GuardTestZones.end();
    }

    private static void zone(GameTestHelper helper, String key) {
        GuardTestZones.put(helper, key, 5, 5, 10, 10);
    }

    /**
     * mock 真玩家对着方块 useOn 放熔炉: 区内、minX − 8 处都被拒, 方块恢复、物品数不变、收到动作栏提示; minX − 9 处照常。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = WorldGuardGameTests.TEMPLATE, batch = BATCH)
    public static void nonOpCannotPlaceAMachineInDistrictOrBuffer(GameTestHelper helper) {
        String key = "placeMachine";
        zone(helper, key);
        ServerPlayer player = null;
        try {
            player = DistrictTestEnv.onlinePlayer(helper, "Gt_Place_Real");
            actionBarKeys(player);
            GuardEvents.clearActionBarThrottle();
            Placed inside = place(helper, player, new BlockPos(7, Y, 7), Items.FURNACE);
            helper.assertTrue(inside.result() == InteractionResult.FAIL && inside.state().isAir()
                    && inside.countAfter() == 3, "区内放机器被拒: 方块恢复、物品数不变, 实为 " + inside);
            helper.assertTrue(actionBarKeys(player).equals(List.of(GuardEvents.KEY_PLACE_DENIED)),
                    "区内被拒的动作栏提示");
            GuardEvents.clearActionBarThrottle();
            Placed buffer = place(helper, player, new BlockPos(-3, Y, 7), Items.FURNACE);
            helper.assertTrue(buffer.result() == InteractionResult.FAIL && buffer.state().isAir()
                    && buffer.countAfter() == 3, "minX − 8 处放机器被拒, 实为 " + buffer);
            helper.assertTrue(actionBarKeys(player).equals(List.of(GuardEvents.KEY_PLACE_DENIED_BUFFER)),
                    "外围 8 格被拒的动作栏提示");
            GuardEvents.clearActionBarThrottle();
            Placed beyond = place(helper, player, new BlockPos(-4, Y, 7), Items.FURNACE);
            helper.assertTrue(beyond.state().is(Blocks.FURNACE) && beyond.result().consumesAction(),
                    "minX − 9 处照常放下, 实为 " + beyond);
            helper.assertTrue(actionBarKeys(player).isEmpty(), "照常放下时没有提示");
        } finally {
            DistrictTestEnv.removePlayer(helper, player);
            GuardTestZones.remove(key);
        }
        helper.succeed();
    }

    /** 同一位置放石头 (装饰) 照常。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = WorldGuardGameTests.TEMPLATE, batch = BATCH)
    public static void decorativeBlocksStayPlaceable(GameTestHelper helper) {
        String key = "placeDeco";
        zone(helper, key);
        ServerPlayer player = null;
        try {
            player = DistrictTestEnv.onlinePlayer(helper, "Gt_Place_Deco");
            Placed inside = place(helper, player, new BlockPos(7, Y, 7), Items.STONE);
            Placed buffer = place(helper, player, new BlockPos(-3, Y, 7), Items.CHEST);
            helper.assertTrue(inside.state().is(Blocks.STONE) && buffer.state().is(Blocks.CHEST),
                    "装饰方块与 allowBlocks 里的方块照常放, 实为 " + inside + " / " + buffer);
        } finally {
            DistrictTestEnv.removePlayer(helper, player);
            GuardTestZones.remove(key);
        }
        helper.succeed();
    }

    /** 2 级 OP: 区内、外围照常放机器。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = WorldGuardGameTests.TEMPLATE, batch = BATCH)
    public static void opPlacesMachinesAnywhere(GameTestHelper helper) {
        String key = "placeOp";
        zone(helper, key);
        ServerPlayer player = null;
        try {
            player = DistrictTestEnv.onlinePlayer(helper, "Gt_Place_Op");
            helper.getLevel().getServer().getPlayerList().getOps()
                    .add(new ServerOpListEntry(player.getGameProfile(), 2, false));
            helper.assertTrue(player.hasPermissions(2), "前提: 2 级 OP");
            Placed inside = place(helper, player, new BlockPos(7, Y, 7), Items.FURNACE);
            Placed buffer = place(helper, player, new BlockPos(-3, Y, 7), Items.FURNACE);
            helper.assertTrue(inside.state().is(Blocks.FURNACE) && buffer.state().is(Blocks.FURNACE),
                    "OP 亲手放置不受禁令约束, 实为 " + inside + " / " + buffer);
        } finally {
            if (player != null) {
                helper.getLevel().getServer().getPlayerList().getOps().remove(player.getGameProfile());
            }
            DistrictTestEnv.removePlayer(helper, player);
            GuardTestZones.remove(key);
        }
        helper.succeed();
    }

    /**
     * 登记成"机械动力的假玩家"的 FakePlayer 子类: 对区内格子发 BreakEvent、EntityPlaceEvent、BlockToolModificationEvent、
     * RightClickBlock (useBlock、useItem 都是 DENY)、LeftClickBlock、FillBucketEvent、EntityInteract、AttackEntityEvent,
     * 全部取消; 区外全部不取消; UUID 在 OP 名单里也照样取消。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = WorldGuardGameTests.TEMPLATE, batch = BATCH)
    public static void createFakePlayerCannotTouchDistrictBlocks(GameTestHelper helper) throws Exception {
        String key = "createFake";
        zone(helper, key);
        ServerLevel level = helper.getLevel();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "Deployer");
        List<ArmorStand> stands = new ArrayList<>();
        try (AutoCloseable registered = GuardActors.registerTestCreateFakePlayer(TestCreateFakePlayer.class)) {
            FakePlayer fake = new TestCreateFakePlayer(level, profile);
            List<String> inside = firedAndCanceled(helper, fake, new BlockPos(7, Y, 7), stands);
            helper.assertTrue(inside.size() == 8, "区内 8 个事件全部取消, 实为 " + inside);
            List<String> outside = firedAndCanceled(helper, fake, new BlockPos(-3, Y, 7), stands);
            helper.assertTrue(outside.isEmpty(), "区外 (含外围 8 格, 装饰方块) 全部不取消, 实为 " + outside);
            level.getServer().getPlayerList().getOps().add(new ServerOpListEntry(profile, 2, false));
            helper.assertTrue(fake.hasPermissions(2), "前提: 主人的 UUID 在 OP 名单里");
            BlockPos at = helper.absolutePos(new BlockPos(7, Y, 7));
            helper.assertTrue(MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, at,
                    level.getBlockState(at), fake)), "OP 放的机械手照样拆不了区内方块");
        } finally {
            level.getServer().getPlayerList().getOps().remove(profile);
            stands.forEach(ArmorStand::discard);
            GuardTestZones.remove(key);
        }
        helper.succeed();
    }

    /** 没登记的 FakePlayer 子类 (别的 mod 的假玩家): 这些事件都不取消 (交给 Flan)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = WorldGuardGameTests.TEMPLATE, batch = BATCH)
    public static void otherFakePlayersAreLeftToFlan(GameTestHelper helper) {
        String key = "otherFake";
        zone(helper, key);
        List<ArmorStand> stands = new ArrayList<>();
        try {
            FakePlayer fake = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "OtherMod"));
            List<String> canceled = firedAndCanceled(helper, fake, new BlockPos(7, Y, 7), stands);
            helper.assertTrue(canceled.isEmpty(), "别的假玩家不归本章管, 实为 " + canceled);
        } finally {
            stands.forEach(ArmorStand::discard);
            GuardTestZones.remove(key);
        }
        helper.succeed();
    }

    /**
     * 熔炉 (测试分类里是机器) 在区内: 非 OP 右键被拒 (两个 Result 都 DENY, 有提示); 潜行 + 测试登记的"扳手"放行;
     * BreakEvent 不受影响; OP 放行; denyUse = false 时放行; 外围与放行名单里的方块不管。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = WorldGuardGameTests.TEMPLATE, batch = BATCH)
    public static void denyUseBlocksRightClickButNotWrenchSneakOrBreaking(GameTestHelper helper) throws Exception {
        String key = "denyUse";
        zone(helper, key);
        ServerLevel level = helper.getLevel();
        ServerPlayer player = null;
        ServerPlayer op = null;
        GuardSettings base = GuardTestZones.settings();
        try (AutoCloseable wrench = GuardEvents.registerTestWrench(TEST_WRENCH)) {
            player = DistrictTestEnv.onlinePlayer(helper, "Gt_Use_Real");
            op = DistrictTestEnv.onlinePlayer(helper, "Gt_Use_Op");
            level.getServer().getPlayerList().getOps().add(new ServerOpListEntry(op.getGameProfile(), 2, false));
            BlockPos furnace = new BlockPos(7, Y, 7);
            helper.setBlock(furnace, Blocks.FURNACE);
            helper.setBlock(new BlockPos(8, Y, 7), Blocks.CHEST);
            helper.setBlock(new BlockPos(-3, Y, 7), Blocks.FURNACE);
            actionBarKeys(player);
            GuardEvents.clearActionBarThrottle();

            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            PlayerInteractEvent.RightClickBlock denied = rightClick(helper, player, furnace);
            helper.assertTrue(denied.isCanceled() && denied.getUseBlock() == Event.Result.DENY
                    && denied.getUseItem() == Event.Result.DENY, "非 OP 右键区内的机器被拒, 两个 Result 都 DENY");
            helper.assertTrue(actionBarKeys(player).equals(List.of(GuardEvents.KEY_USE_DENIED)), "被拒的动作栏提示");

            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BONE));
            helper.assertTrue(rightClick(helper, player, furnace).isCanceled(), "不潜行的扳手右键 (转向、调整) 算操作, 拦");
            player.setShiftKeyDown(true);
            helper.assertTrue(!rightClick(helper, player, furnace).isCanceled(), "潜行 + 扳手 (拆下机器的正路) 放行");
            player.setShiftKeyDown(false);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

            BlockPos at = helper.absolutePos(furnace);
            helper.assertTrue(!MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, at,
                    level.getBlockState(at), player)), "徒手拆 (BreakEvent) 不受 denyUse 影响");
            helper.assertTrue(!rightClick(helper, op, furnace).isCanceled(), "OP 放行");
            helper.assertTrue(!rightClick(helper, player, new BlockPos(8, Y, 7)).isCanceled(),
                    "放行名单里的方块 (箱子) 照常用");
            helper.assertTrue(!rightClick(helper, player, new BlockPos(-3, Y, 7)).isCanceled(),
                    "外围 8 格里的机器不管 (外围不是受保护的地)");
            GuardTestZones.useSettings(base.withDenyUse(false));
            helper.assertTrue(!rightClick(helper, player, furnace).isCanceled(), "denyUse = false 时放行");
        } finally {
            GuardTestZones.useSettings(base);
            if (op != null) {
                level.getServer().getPlayerList().getOps().remove(op.getGameProfile());
            }
            DistrictTestEnv.removePlayer(helper, player);
            DistrictTestEnv.removePlayer(helper, op);
            GuardTestZones.remove(key);
        }
        helper.succeed();
    }

    /** 门面是 OFF (功能关闭): 以上全部放行。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = WorldGuardGameTests.TEMPLATE, batch = BATCH)
    public static void guardsIdleWhenFeatureOff(GameTestHelper helper) throws Exception {
        String key = "featureOff";
        zone(helper, key);
        ServerLevel level = helper.getLevel();
        ServerPlayer player = null;
        List<ArmorStand> stands = new ArrayList<>();
        try (AutoCloseable registered = GuardActors.registerTestCreateFakePlayer(TestCreateFakePlayer.class)) {
            player = DistrictTestEnv.onlinePlayer(helper, "Gt_Off_Real");
            try (AutoCloseable off = GuardTestZones.suspend()) {
                Placed inside = place(helper, player, new BlockPos(7, Y, 7), Items.FURNACE);
                helper.assertTrue(inside.state().is(Blocks.FURNACE), "放置禁令闲着");
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                helper.assertTrue(!rightClick(helper, player, new BlockPos(7, Y, 7)).isCanceled(), "denyUse 闲着");
                FakePlayer fake = new TestCreateFakePlayer(level, new GameProfile(UUID.randomUUID(), "Deployer"));
                List<String> canceled = firedAndCanceled(helper, fake, new BlockPos(8, Y, 8), stands);
                helper.assertTrue(canceled.isEmpty(), "机械动力的假玩家的事件闲着, 实为 " + canceled);
            }
        } finally {
            stands.forEach(ArmorStand::discard);
            DistrictTestEnv.removePlayer(helper, player);
            GuardTestZones.remove(key);
        }
        helper.succeed();
    }

    // ================================================================
    // 工具
    // ================================================================

    /** 一次放置: 结果、放置之后那一格的方块、手里剩几个。 */
    private record Placed(InteractionResult result, BlockState state, int countAfter) {
    }

    /** 真的放一次 (手里 3 个): 下面垫一块石头, 对着它的顶面 useOn。 */
    private static Placed place(GameTestHelper helper, ServerPlayer player, BlockPos relative, Item item) {
        helper.setBlock(relative.below(), Blocks.STONE);
        helper.setBlock(relative, Blocks.AIR);
        ItemStack stack = new ItemStack(item, 3);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos support = helper.absolutePos(relative.below());
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support,
                false);
        InteractionResult result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        return new Placed(result, helper.getBlockState(relative), player.getItemInHand(InteractionHand.MAIN_HAND)
                .getCount());
    }

    private static PlayerInteractEvent.RightClickBlock rightClick(GameTestHelper helper, Player player,
                                                                  BlockPos relative) {
        BlockPos at = helper.absolutePos(relative);
        PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(player,
                InteractionHand.MAIN_HAND, at, new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
        MinecraftForge.EVENT_BUS.post(event);
        return event;
    }

    /**
     * 对一格发 8 个事件 (放、拆、改、右键、左键、装倒桶、对实体用物品、攻击), 返回被取消的那些的名字。放置用石头 (装饰):
     * 外围 8 格里机械动力的假玩家只拦"放机器"。
     */
    private static List<String> firedAndCanceled(GameTestHelper helper, Player player, BlockPos relative,
                                                 List<ArmorStand> stands) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(relative.below(), Blocks.STONE);
        helper.setBlock(relative, Blocks.STONE);
        BlockPos at = helper.absolutePos(relative);
        BlockState state = level.getBlockState(at);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false);
        ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, relative.above());
        stands.add(stand);
        List<String> canceled = new ArrayList<>();
        if (MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, at, state, player))) {
            canceled.add("break");
        }
        if (MinecraftForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(
                BlockSnapshot.create(level.dimension(), level, at), level.getBlockState(at.below()), player))) {
            canceled.add("place");
        }
        if (MinecraftForge.EVENT_BUS.post(new BlockEvent.BlockToolModificationEvent(state,
                new UseOnContext(player, InteractionHand.MAIN_HAND, hit), ToolActions.HOE_TILL, false))) {
            canceled.add("toolModification");
        }
        PlayerInteractEvent.RightClickBlock right = new PlayerInteractEvent.RightClickBlock(player,
                InteractionHand.MAIN_HAND, at, hit);
        if (MinecraftForge.EVENT_BUS.post(right)) {
            canceled.add(right.getUseBlock() == Event.Result.DENY && right.getUseItem() == Event.Result.DENY
                    ? "rightClick" : "rightClick(without DENY)");
        }
        if (MinecraftForge.EVENT_BUS.post(new PlayerInteractEvent.LeftClickBlock(player, at, Direction.UP,
                PlayerInteractEvent.LeftClickBlock.Action.START))) {
            canceled.add("leftClick");
        }
        if (MinecraftForge.EVENT_BUS.post(new FillBucketEvent(player, new ItemStack(Items.BUCKET), level, hit))) {
            canceled.add("fillBucket");
        }
        if (MinecraftForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND,
                stand))) {
            canceled.add("entityInteract");
        }
        if (MinecraftForge.EVENT_BUS.post(new AttackEntityEvent(player, stand))) {
            canceled.add("attack");
        }
        return canceled;
    }

    /** 读空 mock 连接的出站队列, 返回其中动作栏 (overlay) 系统消息的翻译键。 */
    private static List<String> actionBarKeys(ServerPlayer player) {
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.connection.channel();
        List<String> keys = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            try {
                if (outbound instanceof ClientboundSystemChatPacket packet && packet.overlay()
                        && packet.content().getContents() instanceof TranslatableContents translatable) {
                    keys.add(translatable.getKey());
                }
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        }
        return keys;
    }
}
