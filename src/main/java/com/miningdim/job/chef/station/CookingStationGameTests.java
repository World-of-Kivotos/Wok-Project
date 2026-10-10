package com.miningdim.job.chef.station;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.ChefBlocks;
import com.miningdim.job.chef.ChefItems;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 九台烹饪台的方块层契约: 放置朝向、预览开关、炉上油锅的热源与托架、各方案说明里的形状/遮挡/光照、
 * 粒子出生点随朝向旋转、掉落、挖掘工具标签、合成配方与中英文名。烹饪逻辑接入后另起用例。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class CookingStationGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "chef_station";
    private static final BlockPos SUPPORT_REL = new BlockPos(1, 1, 1);
    private static final BlockPos STATION_REL = new BlockPos(1, 2, 1);

    /** 方块 id -> {中文名, 英文名}: 产品已拍板的名字, 写死在这里, 改名要连同本表一起改。 */
    private static final Map<String, String[]> NAMES = Map.of(
            "deep_fryer_rustic", new String[]{"炉上油锅", "Stovetop Fryer"},
            "deep_fryer_steel", new String[]{"商用炸炉", "Commercial Fryer"},
            "deep_fryer_chinese", new String[]{"中式炸灶", "Chinese Frying Stove"},
            "baking_oven_rustic", new String[]{"砖砌烤炉", "Brick Oven"},
            "baking_oven_steel", new String[]{"铸铁烤箱灶", "Cast-Iron Range"},
            "baking_oven_chinese", new String[]{"吊炉", "Hanging Oven"},
            "prep_counter_rustic", new String[]{"木质备餐台", "Wooden Prep Counter"},
            "prep_counter_steel", new String[]{"冷藏备餐台", "Refrigerated Prep Table"},
            "prep_counter_chinese", new String[]{"中式案台", "Chinese Prep Table"});

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyStationIsRegisteredAsOneKindInOneStyle(GameTestHelper helper) {
        helper.assertTrue(ChefBlocks.COOKING_STATIONS.size() == 9 && ChefItems.COOKING_STATIONS.size() == 9,
                "炸锅/烤炉/备餐台各三种外观, 共 9 台");
        for (int i = 0; i < 9; i++) {
            CookingStationBlock block = station(ChefBlocks.COOKING_STATIONS.get(i));
            String id = ChefBlocks.COOKING_STATIONS.get(i).getId().getPath();
            helper.assertTrue(id.equals(block.kind().id() + "_" + block.style().id()),
                    id + " 的种类/风格与注册 id 对不上: " + block.kind() + "/" + block.style());
            helper.assertTrue(block.kind() == CookingStationBlock.Kind.values()[i / 3]
                            && block.style() == CookingStationBlock.Style.values()[i % 3],
                    "COOKING_STATIONS 必须按 种类 x 风格 排序, 第 " + i + " 个是 " + id);
            Item item = ChefItems.COOKING_STATIONS.get(i).get();
            helper.assertTrue(item instanceof BlockItem blockItem && blockItem.getBlock() == block,
                    id + " 的物品形态必须放出同一个方块");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void placementFacesThePlayerInAllFourDirections(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            helper.setBlock(SUPPORT_REL, Blocks.STONE);
            BlockPos stationPos = helper.absolutePos(STATION_REL);
            for (RegistryObject<Item> entry : ChefItems.COOKING_STATIONS) {
                BlockItem item = (BlockItem) entry.get();
                for (Direction looking : Direction.Plane.HORIZONTAL) {
                    player.setYRot(looking.toYRot());
                    InteractionResult result = item.place(placeOnTopOf(helper, player, item, SUPPORT_REL));
                    helper.assertTrue(result.consumesAction(), entry.getId() + " 放置失败, 玩家朝 " + looking);
                    BlockState placed = helper.getLevel().getBlockState(stationPos);
                    helper.assertTrue(placed.is(item.getBlock()), entry.getId() + " 没有放到支撑方块上方");
                    helper.assertTrue(placed.getValue(CookingStationBlock.FACING) == looking.getOpposite(),
                            entry.getId() + " 正面应朝向玩家: 玩家朝 " + looking + " 时 facing 应为 "
                                    + looking.getOpposite() + ", 实为 " + placed.getValue(CookingStationBlock.FACING));
                    helper.assertFalse(placed.getValue(CookingStationBlock.LIT), entry.getId() + " 放下时应为待机");
                    helper.setBlock(STATION_REL, Blocks.AIR);
                }
            }
        } finally {
            MockGameTestPlayers.logout(player);
        }
        helper.succeed();
    }

    /** 临时预览开关: 只有创造模式、潜行、双手都空时才切 lit; 生存玩家、站立、手里拿着东西一律不动。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void previewToggleIsCreativeSneakingEmptyHandedOnly(GameTestHelper helper) {
        ServerPlayer creative = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer survival = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        try {
            helper.setBlock(SUPPORT_REL, Blocks.STONE);
            BlockPos pos = helper.absolutePos(STATION_REL);
            for (RegistryObject<Block> entry : ChefBlocks.COOKING_STATIONS) {
                if (entry.get() instanceof StovetopFryerBlock) {
                    continue; // 炉上油锅要热源, 单独一条用例。
                }
                helper.getLevel().setBlock(pos, entry.get().defaultBlockState(), Block.UPDATE_ALL);
                String id = entry.getId().getPath();

                creative.setShiftKeyDown(true);
                creative.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                creative.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                helper.assertTrue(useOn(helper, creative, pos).consumesAction() && isLit(helper, pos),
                        id + ": 创造潜行空手右键应切到工作中");
                helper.assertTrue(useOn(helper, creative, pos).consumesAction() && !isLit(helper, pos),
                        id + ": 再按一次应切回待机");

                creative.setShiftKeyDown(false);
                helper.assertTrue(useOn(helper, creative, pos) == InteractionResult.PASS && !isLit(helper, pos),
                        id + ": 没潜行时不得切换");
                creative.setShiftKeyDown(true);
                creative.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD));
                helper.assertTrue(useOn(helper, creative, pos) == InteractionResult.PASS && !isLit(helper, pos),
                        id + ": 手里拿着东西时不得切换");
                creative.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

                survival.setShiftKeyDown(true);
                survival.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                survival.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                helper.assertTrue(useOn(helper, survival, pos) == InteractionResult.PASS && !isLit(helper, pos),
                        id + ": 生存玩家不得切换");
            }
            helper.setBlock(STATION_REL, Blocks.AIR);
        } finally {
            MockGameTestPlayers.logout(creative);
            MockGameTestPlayers.logout(survival);
        }
        helper.succeed();
    }

    /**
     * 炉上油锅按名字读农夫乐事的热源标签: 营火上换托架 (support=tray), 普通方块上 none; 没有热源时预览开关拒绝开火,
     * 热源熄灭时 lit 跟着关; 岩浆块 (无 lit 属性) 一直算热; 漏斗 (heat_conductors) 隔一格传热。
     * 农夫乐事是 libs/ 在才加载的可选开发依赖: 没装时三个标签都是空的, 只核对"找不到热源"这一降级结果。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void stovetopFryerNeedsFarmersDelightHeatAndSitsOnCampfireTray(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            BlockItem item = (BlockItem) ChefItems.DEEP_FRYER_RUSTIC.get();
            BlockPos pos = helper.absolutePos(STATION_REL);
            player.setShiftKeyDown(true);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);

            helper.setBlock(SUPPORT_REL, Blocks.STONE);
            item.place(placeOnTopOf(helper, player, item, SUPPORT_REL));
            helper.assertTrue(support(helper, pos) == StovetopFryerBlock.Support.NONE,
                    "放在石头上 support 应为 none");
            useOn(helper, player, pos);
            helper.assertFalse(isLit(helper, pos), "下方没有热源时预览开关不得开火");

            if (!ModList.get().isLoaded("farmersdelight")) {
                helper.setBlock(SUPPORT_REL, Blocks.CAMPFIRE);
                helper.assertTrue(support(helper, pos) == StovetopFryerBlock.Support.NONE
                                && !StovetopFryerBlock.isHeated(helper.getLevel(), pos),
                        "没装农夫乐事时热源标签为空, 营火上也只能是 none 且不算热");
                helper.setBlock(STATION_REL, Blocks.AIR);
                helper.succeed();
                return;
            }

            // 换成点着的营火: 邻居更新把托架换上, 预览开关可以开火。
            helper.setBlock(SUPPORT_REL, Blocks.CAMPFIRE);
            helper.assertTrue(support(helper, pos) == StovetopFryerBlock.Support.TRAY,
                    "下方换成营火 (tray_heat_sources) 后 support 应随邻居更新变成 tray");
            useOn(helper, player, pos);
            helper.assertTrue(isLit(helper, pos), "点着的营火是热源, 预览开关应能开火");

            // 营火熄灭: lit 跟着关, 托架留着 (熄灭的营火仍在 tray_heat_sources 里)。
            helper.getLevel().setBlock(helper.absolutePos(SUPPORT_REL),
                    Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false), Block.UPDATE_ALL);
            helper.assertFalse(isLit(helper, pos), "营火熄灭后油锅应退回待机");
            helper.assertTrue(support(helper, pos) == StovetopFryerBlock.Support.TRAY, "熄灭的营火上仍架着托架");
            useOn(helper, player, pos);
            helper.assertFalse(isLit(helper, pos), "熄灭的营火不算热源");

            // 直接放到营火上: 放置时就判出托架。
            helper.setBlock(STATION_REL, Blocks.AIR);
            helper.setBlock(SUPPORT_REL, Blocks.CAMPFIRE);
            item.place(placeOnTopOf(helper, player, item, SUPPORT_REL));
            helper.assertTrue(support(helper, pos) == StovetopFryerBlock.Support.TRAY, "放在营火上时 support 应为 tray");
            VoxelShape trayShape = helper.getLevel().getBlockState(pos).getShape(helper.getLevel(), pos);
            helper.assertTrue(trayShape.min(Direction.Axis.Y) < 0.0D,
                    "托架变体的形状应像农夫乐事厨锅那样向下多出托架板");

            // 岩浆块: 没有 lit 属性的热源一直算热, 不是营火类, 不换托架。
            helper.setBlock(SUPPORT_REL, Blocks.MAGMA_BLOCK);
            helper.assertTrue(support(helper, pos) == StovetopFryerBlock.Support.NONE, "岩浆块上不用托架");
            useOn(helper, player, pos);
            helper.assertTrue(isLit(helper, pos), "岩浆块是热源, 预览开关应能开火");

            // 漏斗隔一格传热 (heat_conductors), 同农夫乐事厨锅。
            helper.setBlock(STATION_REL, Blocks.AIR);
            helper.setBlock(SUPPORT_REL, Blocks.MAGMA_BLOCK);
            helper.setBlock(STATION_REL, Blocks.HOPPER);
            BlockPos above = helper.absolutePos(STATION_REL.above());
            helper.assertTrue(StovetopFryerBlock.isHeated(helper.getLevel(), above),
                    "漏斗下面是岩浆块时, 漏斗上方应算有热源");
            helper.setBlock(STATION_REL.above(), Blocks.AIR);
            helper.setBlock(STATION_REL, Blocks.AIR);
        } finally {
            MockGameTestPlayers.logout(player);
        }
        helper.succeed();
    }

    /** 形状逐台对照方案说明的关键尺寸 (像素坐标, 按 north 书写; 有朝向差异的再抽一个别的朝向核对)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shapesMatchEachDesignsNotes(GameTestHelper helper) {
        for (RegistryObject<Block> entry : ChefBlocks.COOKING_STATIONS) {
            for (BlockState state : entry.get().getStateDefinition().getPossibleStates()) {
                helper.assertFalse(outline(state).isEmpty() || collision(state).isEmpty(),
                        entry.getId() + " 的形状不能为空: " + state);
            }
        }

        // 炉上油锅: 锅身 14x8x14, 托架变体向下多 1 像素整格宽的托架板。
        BlockState fryerA = ChefBlocks.DEEP_FRYER_RUSTIC.get().defaultBlockState();
        assertBounds(helper, outline(fryerA), 1, 0, 1, 15, 8, 15, "炉上油锅锅身");
        assertBounds(helper, outline(fryerA.setValue(StovetopFryerBlock.SUPPORT, StovetopFryerBlock.Support.TRAY)),
                0, -1, 0, 16, 8, 16, "炉上油锅托架变体");
        assertSame(helper, outline(fryerA), collision(fryerA), "炉上油锅选择框与碰撞箱");

        // 商用炸炉: 台面 y11 + 背板到顶 (z12..16), 随朝向旋转; 碰撞箱同选择框。
        BlockState fryerB = ChefBlocks.DEEP_FRYER_STEEL.get().defaultBlockState();
        assertInside(helper, fryerB, Direction.NORTH, 8, 14, 14, true, "商用炸炉背板");
        assertInside(helper, fryerB, Direction.NORTH, 8, 14, 4, false, "商用炸炉台面上方");
        assertInside(helper, fryerB, Direction.NORTH, 8, 10.5, 4, true, "商用炸炉台面");
        assertInside(helper, fryerB, Direction.EAST, 2, 14, 8, true, "商用炸炉朝东时背板在西侧");
        assertInside(helper, fryerB, Direction.EAST, 14, 14, 8, false, "商用炸炉朝东时东侧是台面上方");
        assertSame(helper, outline(fryerB), collision(fryerB), "商用炸炉选择框与碰撞箱");

        // 中式炸灶: 灶身 y10 + 铁锅 2..14 到 y15, 四向共用。
        BlockState fryerC = ChefBlocks.DEEP_FRYER_CHINESE.get().defaultBlockState();
        assertBounds(helper, outline(fryerC), 0, 0, 0, 16, 15, 16, "中式炸灶");
        assertInside(helper, fryerC, Direction.NORTH, 8, 12, 8, true, "中式炸灶铁锅");
        assertInside(helper, fryerC, Direction.NORTH, 1, 12, 1, false, "中式炸灶灶面角上");
        assertSame(helper, outline(fryerC), outline(fryerC.setValue(CookingStationBlock.FACING, Direction.WEST)),
                "中式炸灶四个朝向形状相同");

        // 砖砌烤炉: 整格挖空炉口 x4..12, y5..12, 深 11 像素, 随朝向旋转; 碰撞箱同选择框。
        BlockState ovenA = ChefBlocks.BAKING_OVEN_RUSTIC.get().defaultBlockState();
        assertInside(helper, ovenA, Direction.NORTH, 8, 8, 2, false, "砖砌烤炉炉口");
        assertInside(helper, ovenA, Direction.NORTH, 8, 8, 13, true, "砖砌烤炉后墙");
        assertInside(helper, ovenA, Direction.NORTH, 2, 8, 2, true, "砖砌烤炉炉口两侧砖墩");
        assertInside(helper, ovenA, Direction.SOUTH, 8, 8, 14, false, "砖砌烤炉朝南时炉口在南侧");
        assertInside(helper, ovenA, Direction.SOUTH, 8, 8, 2, true, "砖砌烤炉朝南时北侧是后墙");
        assertSame(helper, outline(ovenA), collision(ovenA), "砖砌烤炉选择框与碰撞箱");

        // 铸铁烤箱灶: 碰撞箱整格; 选择框贴着灶面 (y12) 和背板 (z13..16)。
        BlockState ovenB = ChefBlocks.BAKING_OVEN_STEEL.get().defaultBlockState();
        helper.assertTrue(Block.isShapeFullBlock(collision(ovenB)), "铸铁烤箱灶碰撞箱应为整格");
        assertInside(helper, ovenB, Direction.NORTH, 8, 14, 4, false, "铸铁烤箱灶炉架上方");
        assertInside(helper, ovenB, Direction.NORTH, 8, 14, 14.5, true, "铸铁烤箱灶背板");
        assertInside(helper, ovenB, Direction.WEST, 14.5, 14, 8, true, "铸铁烤箱灶朝西时背板在东侧");

        // 吊炉: 台基 + 炉身 + 两级炉肩 + 烟口的阶梯, 四向共用。
        BlockState ovenC = ChefBlocks.BAKING_OVEN_CHINESE.get().defaultBlockState();
        assertBounds(helper, outline(ovenC), 0, 0, 0, 16, 16, 16, "吊炉外包");
        assertInside(helper, ovenC, Direction.NORTH, 8, 15, 8, true, "吊炉烟口");
        assertInside(helper, ovenC, Direction.NORTH, 3, 15, 3, false, "吊炉圆顶外侧");
        assertInside(helper, ovenC, Direction.NORTH, 0.5, 10, 0.5, false, "吊炉炉身四周留 1 像素");
        assertSame(helper, outline(ovenC), collision(ovenC), "吊炉选择框与碰撞箱");

        // 木质备餐台、中式案台: 说明按整格。
        for (RegistryObject<Block> full : List.of(ChefBlocks.PREP_COUNTER_RUSTIC, ChefBlocks.PREP_COUNTER_CHINESE)) {
            BlockState state = full.get().defaultBlockState();
            helper.assertTrue(Block.isShapeFullBlock(outline(state)) && Block.isShapeFullBlock(collision(state)),
                    full.getId() + " 的选择框与碰撞箱应为整格");
        }

        // 冷藏备餐台: 前半台面 y12 + 后半冷藏槽 (z7..16) 到顶, 碰撞箱同选择框。
        BlockState prepB = ChefBlocks.PREP_COUNTER_STEEL.get().defaultBlockState();
        assertInside(helper, prepB, Direction.NORTH, 8, 14, 3, false, "冷藏备餐台砧板上方");
        assertInside(helper, prepB, Direction.NORTH, 8, 14, 12, true, "冷藏备餐台冷藏槽");
        assertInside(helper, prepB, Direction.WEST, 3, 14, 8, false, "冷藏备餐台朝西时砧板在西侧");
        assertSame(helper, outline(prepB), collision(prepB), "冷藏备餐台选择框与碰撞箱");
        helper.succeed();
    }

    /** 遮挡、视线、窒息与发光等级逐台对照方案说明。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void occlusionAndLightMatchEachDesignsNotes(GameTestHelper helper) {
        // 炸锅 C 明确要求不调 noOcclusion; 炸锅 A 照农夫乐事厨锅也不调; 其余七台必须调。
        for (RegistryObject<Block> entry : ChefBlocks.COOKING_STATIONS) {
            boolean expectOcclusion = entry == ChefBlocks.DEEP_FRYER_CHINESE || entry == ChefBlocks.DEEP_FRYER_RUSTIC;
            helper.assertTrue(entry.get().defaultBlockState().canOcclude() == expectOcclusion,
                    entry.getId() + (expectOcclusion ? " 不得调 noOcclusion" : " 必须 noOcclusion"));
        }
        BlockState ovenB = ChefBlocks.BAKING_OVEN_STEEL.get().defaultBlockState();
        BlockState prepB = ChefBlocks.PREP_COUNTER_STEEL.get().defaultBlockState();
        helper.assertFalse(ovenB.isViewBlocking(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), "铸铁烤箱灶不挡视线");
        helper.assertFalse(prepB.isViewBlocking(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), "冷藏备餐台不挡视线");
        helper.assertFalse(prepB.isSuffocating(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), "冷藏备餐台不使实体窒息");

        // {工作中, 待机} 的发光等级。
        assertLight(helper, ChefBlocks.DEEP_FRYER_RUSTIC, 0, 0);
        assertLight(helper, ChefBlocks.DEEP_FRYER_STEEL, 0, 0);
        assertLight(helper, ChefBlocks.DEEP_FRYER_CHINESE, 13, 3);
        assertLight(helper, ChefBlocks.BAKING_OVEN_RUSTIC, 13, 0);
        assertLight(helper, ChefBlocks.BAKING_OVEN_STEEL, 13, 0);
        assertLight(helper, ChefBlocks.BAKING_OVEN_CHINESE, 13, 3);
        assertLight(helper, ChefBlocks.PREP_COUNTER_RUSTIC, 0, 0);
        assertLight(helper, ChefBlocks.PREP_COUNTER_STEEL, 0, 0);
        assertLight(helper, ChefBlocks.PREP_COUNTER_CHINESE, 0, 0);
        helper.succeed();
    }

    /**
     * 粒子出生点与形状、blockstate 同一个旋转口径: 商用炸炉背板顶的排烟格栅 (8, 16, 14.5) 转到任意朝向后
     * 都必须落在该朝向的背板上方, 台面前沿的点不能跑到背板里。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void particleSpawnPointsRotateWithTheModel(GameTestHelper helper) {
        Vec3 east = StationParticles.rotate(Direction.EAST, 4.0D, 10.2D, 7.5D);
        helper.assertTrue(east.x == 8.5D && east.y == 10.2D && east.z == 4.0D,
                "朝东时 (4, 10.2, 7.5) 应转到 (8.5, 10.2, 4), 实为 " + east);
        Vec3 south = StationParticles.rotate(Direction.SOUTH, 4.0D, 10.2D, 7.5D);
        helper.assertTrue(south.x == 12.0D && south.z == 8.5D, "朝南时应转到 (12, 10.2, 8.5), 实为 " + south);
        BlockState fryerB = ChefBlocks.DEEP_FRYER_STEEL.get().defaultBlockState();
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Vec3 vent = StationParticles.rotate(facing, 8.0D, 15.5D, 14.5D);
            Vec3 front = StationParticles.rotate(facing, 8.0D, 15.5D, 3.0D);
            assertInside(helper, fryerB, facing, vent.x, vent.y, vent.z, true, "朝 " + facing + " 时排烟格栅出生点");
            assertInside(helper, fryerB, facing, front.x, front.y, front.z, false, "朝 " + facing + " 时台面前沿上方");
        }
        helper.succeed();
    }

    /**
     * 掉落与挖掘工具。九台都掉自己 (不要求正确工具); 砖/金属类进 mineable/pickaxe, 木质备餐台进 mineable/axe。
     * mineable/axe 由电力模块的数据生成器 (PowerRubberTagsProvider) 写在 src/generated 下, 木质备餐台那一条是手工
     * 追加的: 重跑 runData 会把它冲掉, 本用例随即变红, 届时要把它补回去 (或把 axe 改成像 pickaxe 那样的共享文件)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void stationsDropThemselvesAndUseTheirMaterialsTool(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(STATION_REL);
        for (RegistryObject<Block> entry : ChefBlocks.COOKING_STATIONS) {
            BlockState state = entry.get().defaultBlockState().setValue(CookingStationBlock.LIT, true);
            List<ItemStack> drops = Block.getDrops(state, helper.getLevel(), pos, null);
            helper.assertTrue(drops.size() == 1 && drops.get(0).is(entry.get().asItem())
                            && drops.get(0).getCount() == 1,
                    entry.getId() + " 应徒手拆下就掉自己一个, 实为 " + drops);
            boolean wooden = entry == ChefBlocks.PREP_COUNTER_RUSTIC;
            helper.assertTrue(state.is(BlockTags.MINEABLE_WITH_PICKAXE) != wooden,
                    entry.getId() + (wooden ? " 是木质柜台, 不进 mineable/pickaxe" : " 应进 mineable/pickaxe"));
            helper.assertTrue(state.is(BlockTags.MINEABLE_WITH_AXE) == wooden,
                    entry.getId() + (wooden
                            ? " 应进 mineable/axe (src/generated 里手工追加的那条可能被 runData 冲掉了)"
                            : " 不进 mineable/axe"));
            helper.assertFalse(state.requiresCorrectToolForDrops(), entry.getId() + " 不要求正确工具才掉落");
        }
        helper.succeed();
    }

    /** 九个配方都能被 RecipeManager 找到、产出自己, 且配方文件里只引用原版物品与原版物品标签。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void recipesAreLoadedAndUseOnlyVanillaMaterials(GameTestHelper helper) {
        for (RegistryObject<Item> entry : ChefItems.COOKING_STATIONS) {
            String id = entry.getId().getPath();
            ResourceLocation recipeId = new ResourceLocation(MiningConstants.MODID, "chef/" + id);
            Optional<? extends Recipe<?>> recipe = helper.getLevel().getRecipeManager().byKey(recipeId);
            helper.assertTrue(recipe.isPresent(), "RecipeManager 找不到配方 " + recipeId);
            helper.assertTrue(recipe.get().getType() == RecipeType.CRAFTING, recipeId + " 应是工作台配方");
            ItemStack result = recipe.get().getResultItem(helper.getLevel().registryAccess());
            helper.assertTrue(result.is(entry.get()) && result.getCount() == 1, recipeId + " 应产出一个 " + id);

            JsonObject key = loadJsonResource("/data/miningdim/recipes/chef/" + id + ".json").getAsJsonObject("key");
            for (Map.Entry<String, JsonElement> symbol : key.entrySet()) {
                JsonObject ingredient = symbol.getValue().getAsJsonObject();
                String ref = ingredient.has("item") ? ingredient.get("item").getAsString()
                        : ingredient.get("tag").getAsString();
                helper.assertTrue(ref.startsWith("minecraft:"), recipeId + " 只能用原版材料, 发现 " + ref);
                if (ingredient.has("item")) {
                    helper.assertTrue(ForgeRegistries.ITEMS.containsKey(new ResourceLocation(ref)),
                            recipeId + " 引用了不存在的物品 " + ref);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyStationHasChineseAndEnglishNames(GameTestHelper helper) {
        JsonObject zh = loadJsonResource("/assets/miningdim/lang/zh_cn.json");
        JsonObject en = loadJsonResource("/assets/miningdim/lang/en_us.json");
        helper.assertTrue(NAMES.size() == ChefBlocks.COOKING_STATIONS.size(), "名字表应覆盖全部 9 台");
        for (RegistryObject<Block> entry : ChefBlocks.COOKING_STATIONS) {
            String id = entry.getId().getPath();
            String[] expected = NAMES.get(id);
            String key = entry.get().getDescriptionId();
            helper.assertTrue(expected != null, id + " 不在名字表里");
            helper.assertTrue(zh.has(key) && expected[0].equals(zh.get(key).getAsString()),
                    key + " 的中文名应为 " + expected[0]);
            helper.assertTrue(en.has(key) && expected[1].equals(en.get(key).getAsString()),
                    key + " 的英文名应为 " + expected[1]);
        }
        helper.assertTrue(zh.has("chef.station.needs_heat") && en.has("chef.station.needs_heat"),
                "炉上油锅缺热源的提示要有中英文");
        helper.succeed();
    }

    private static CookingStationBlock station(RegistryObject<Block> entry) {
        return (CookingStationBlock) entry.get();
    }

    private static BlockPlaceContext placeOnTopOf(GameTestHelper helper, ServerPlayer player, BlockItem item,
                                                  BlockPos supportRel) {
        BlockPos support = helper.absolutePos(supportRel);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support).add(0.0D, 0.5D, 0.0D),
                Direction.UP, support, false);
        return new BlockPlaceContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, new ItemStack(item), hit);
    }

    private static InteractionResult useOn(GameTestHelper helper, ServerPlayer player, BlockPos pos) {
        BlockState state = helper.getLevel().getBlockState(pos);
        return state.use(helper.getLevel(), player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private static boolean isLit(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getBlockState(pos).getValue(CookingStationBlock.LIT);
    }

    private static StovetopFryerBlock.Support support(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getBlockState(pos).getValue(StovetopFryerBlock.SUPPORT);
    }

    private static VoxelShape outline(BlockState state) {
        return state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private static VoxelShape collision(BlockState state) {
        return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    /** 像素坐标 (px, py, pz) 是否落在 facing 朝向下的选择框里。 */
    private static void assertInside(GameTestHelper helper, BlockState state, Direction facing,
                                     double px, double py, double pz, boolean expected, String what) {
        VoxelShape shape = outline(state.setValue(CookingStationBlock.FACING, facing));
        boolean inside = false;
        for (AABB box : shape.toAabbs()) {
            inside |= box.contains(px / 16.0D, py / 16.0D, pz / 16.0D);
        }
        helper.assertTrue(inside == expected, what + (expected ? " 应在" : " 不应在") + "形状内 ("
                + px + ", " + py + ", " + pz + ")");
    }

    private static void assertBounds(GameTestHelper helper, VoxelShape shape, double minX, double minY, double minZ,
                                     double maxX, double maxY, double maxZ, String what) {
        AABB bounds = shape.bounds();
        AABB expected = new AABB(minX / 16.0D, minY / 16.0D, minZ / 16.0D, maxX / 16.0D, maxY / 16.0D, maxZ / 16.0D);
        helper.assertTrue(bounds.equals(expected), what + " 的外包应为 " + expected + ", 实为 " + bounds);
    }

    private static void assertSame(GameTestHelper helper, VoxelShape a, VoxelShape b, String what) {
        helper.assertFalse(Shapes.joinIsNotEmpty(a, b, BooleanOp.NOT_SAME), what + " 应相同");
    }

    private static void assertLight(GameTestHelper helper, RegistryObject<Block> entry, int lit, int idle) {
        BlockState state = entry.get().defaultBlockState();
        int litLight = state.setValue(CookingStationBlock.LIT, true).getLightEmission();
        int idleLight = state.setValue(CookingStationBlock.LIT, false).getLightEmission();
        helper.assertTrue(litLight == lit && idleLight == idle,
                entry.getId() + " 发光应为 工作中 " + lit + " / 待机 " + idle + ", 实为 " + litLight + " / " + idleLight);
    }

    private static JsonObject loadJsonResource(String path) {
        try (InputStream in = CookingStationGameTests.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("JSON resource not found on classpath: " + path);
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("failed reading JSON resource: " + path, exception);
        }
    }
}
