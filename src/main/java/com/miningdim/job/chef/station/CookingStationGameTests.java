package com.miningdim.job.chef.station;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.ChefBlocks;
import com.miningdim.job.chef.ChefItems;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 九台烹饪台的方块层契约: 放置朝向、预览开关、炉上油锅的热源与托架 (含计划刻复核)、冷藏备餐台的掀盖避让、
 * 各方案说明里的形状/遮挡/光照、碰撞箱不得是严格整格、粒子出生点、旋转/镜像、掉落、挖掘工具标签、
 * 合成配方 (暂时关着, 但按格子摆得出来) 与中英文名/物品提示。资源 (方块状态/模型/贴图) 的完整性见
 * {@link CookingStationAssetGameTests}。烹饪逻辑接入后另起用例。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class CookingStationGameTests {

    private static final String EMPTY = "empty";
    static final String BATCH = "chef_station";
    private static final BlockPos SUPPORT_REL = new BlockPos(1, 1, 1);
    private static final BlockPos STATION_REL = new BlockPos(1, 2, 1);

    /** 九个烹饪台配方是否已开放; 烹饪逻辑接入前为 false (配方带 forge:false 条件), 见 recipesStayClosedButCraftFromAGrid。 */
    private static final boolean RECIPES_OPEN = false;

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
            helper.assertTrue(item instanceof CookingStationItem stationItem && stationItem.getBlock() == block,
                    id + " 的物品形态必须是放出同一个方块的 CookingStationItem (带物品提示)");
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
     * 热源熄灭或被拆掉时 lit 跟着关; 岩浆块 (无 lit 属性) 一直算热; 漏斗 (heat_conductors) 隔一格传热, 真放一台
     * 上去能开火; 方案默认的摆法"坐在农夫乐事炉灶上"能开火、炉灶熄了油锅跟着熄。
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

            // 营火上开着火时把营火直接拆成空气: 托架收起、lit 跟着关。
            useOn(helper, player, pos);
            helper.assertTrue(isLit(helper, pos), "营火上应能开火");
            helper.setBlock(SUPPORT_REL, Blocks.AIR);
            helper.assertTrue(support(helper, pos) == StovetopFryerBlock.Support.NONE && !isLit(helper, pos),
                    "下方营火被拆掉后应收起托架并退回待机");

            // 岩浆块: 没有 lit 属性的热源一直算热, 不是营火类, 不换托架。
            helper.setBlock(SUPPORT_REL, Blocks.MAGMA_BLOCK);
            helper.assertTrue(support(helper, pos) == StovetopFryerBlock.Support.NONE, "岩浆块上不用托架");
            useOn(helper, player, pos);
            helper.assertTrue(isLit(helper, pos), "岩浆块是热源, 预览开关应能开火");

            // 农夫乐事炉灶 (方案默认的摆法): 不换托架, 点着时能开火, 炉灶熄了油锅跟着熄。
            Block stove = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("farmersdelight", "stove"));
            helper.assertTrue(stove != null && stove != Blocks.AIR
                            && stove.defaultBlockState().hasProperty(BlockStateProperties.LIT),
                    "装了农夫乐事时应能取到带 lit 属性的 farmersdelight:stove");
            BlockState stoveLit = stove.defaultBlockState().setValue(BlockStateProperties.LIT, true);
            helper.setBlock(STATION_REL, Blocks.AIR);
            helper.setBlock(SUPPORT_REL, stoveLit);
            item.place(placeOnTopOf(helper, player, item, SUPPORT_REL));
            helper.assertTrue(support(helper, pos) == StovetopFryerBlock.Support.NONE, "炉灶上不用托架");
            useOn(helper, player, pos);
            helper.assertTrue(isLit(helper, pos), "点着的农夫乐事炉灶是热源, 预览开关应能开火");
            helper.setBlock(SUPPORT_REL, stoveLit.setValue(BlockStateProperties.LIT, false));
            helper.assertFalse(isLit(helper, pos), "炉灶熄火后油锅应退回待机");

            // 漏斗隔一格传热 (heat_conductors), 同农夫乐事厨锅: 真放一台上去, 预览开关能开火。
            helper.setBlock(STATION_REL, Blocks.AIR);
            helper.setBlock(SUPPORT_REL, Blocks.MAGMA_BLOCK);
            helper.setBlock(STATION_REL, Blocks.HOPPER);
            BlockPos above = helper.absolutePos(STATION_REL.above());
            helper.assertTrue(StovetopFryerBlock.isHeated(helper.getLevel(), above),
                    "漏斗下面是岩浆块时, 漏斗上方应算有热源");
            item.place(placeOnTopOf(helper, player, item, STATION_REL));
            helper.assertTrue(helper.getLevel().getBlockState(above).is(item.getBlock())
                            && support(helper, above) == StovetopFryerBlock.Support.NONE,
                    "油锅应能放在漏斗上, 且不换托架");
            useOn(helper, player, above);
            helper.assertTrue(isLit(helper, above), "隔着漏斗有岩浆块时预览开关应能开火");
            helper.setBlock(STATION_REL.above(), Blocks.AIR);
            helper.setBlock(STATION_REL, Blocks.AIR);
        } finally {
            MockGameTestPlayers.logout(player);
        }
        helper.succeed();
    }

    /**
     * 邻居更新覆盖不到的情形由计划刻兜底: 油锅隔着漏斗烤在岩浆块上, 把岩浆块换成石头时漏斗不变、油锅收不到更新,
     * 一个复核间隔之后必须自己熄火 (否则会一直亮着冒粒子)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void stovetopFryerGoesOutWhenAConductedHeatSourceDisappears(GameTestHelper helper) {
        if (!ModList.get().isLoaded("farmersdelight")) {
            helper.succeed(); // 没有热源标签, 根本开不了火, 无从复核。
            return;
        }
        BlockPos fryerRel = STATION_REL.above();
        BlockPos pos = helper.absolutePos(fryerRel);
        helper.setBlock(SUPPORT_REL, Blocks.MAGMA_BLOCK);
        helper.setBlock(STATION_REL, Blocks.HOPPER);
        helper.getLevel().setBlock(pos, ChefBlocks.DEEP_FRYER_RUSTIC.get().defaultBlockState(), Block.UPDATE_ALL);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            player.setShiftKeyDown(true);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            useOn(helper, player, pos);
        } finally {
            MockGameTestPlayers.logout(player);
        }
        helper.assertTrue(isLit(helper, pos), "隔着漏斗有岩浆块时应能开火");

        helper.setBlock(SUPPORT_REL, Blocks.STONE);
        helper.assertTrue(isLit(helper, pos),
                "热源隔着漏斗被换掉时漏斗本身不变, 邻居更新传不到油锅 (这正是计划刻要兜底的情形)");
        helper.runAfterDelay(StovetopFryerBlock.HEAT_RECHECK_TICKS + 5, () -> {
            helper.assertFalse(isLit(helper, pos), "一个复核间隔之后, 失去热源的油锅应自己熄火");
            helper.setBlock(fryerRel, Blocks.AIR);
            helper.setBlock(STATION_REL, Blocks.AIR);
            helper.succeed();
        });
    }

    /**
     * 冷藏备餐台工作中翻开的掀盖会伸进上方一格: 上方有东西 (选择框不为空, 含火把这种没有碰撞箱的) 时
     * lid_blocked=true, blockstate 换用掀盖合着的模型; 放置时就判, 之后随上方变化更新, 切换 lit 不丢这个属性。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void refrigeratedPrepClosesItsLidUnderABlock(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            BlockItem item = (BlockItem) ChefItems.PREP_COUNTER_STEEL.get();
            BlockPos pos = helper.absolutePos(STATION_REL);
            BlockPos aboveRel = STATION_REL.above();
            helper.setBlock(SUPPORT_REL, Blocks.STONE);

            item.place(placeOnTopOf(helper, player, item, SUPPORT_REL));
            helper.assertFalse(lidBlocked(helper, pos), "上方是空气时掀盖可以翻开");
            helper.setBlock(aboveRel, Blocks.STONE);
            helper.assertTrue(lidBlocked(helper, pos), "上方放了方块后应改为掀盖合着");
            helper.setBlock(aboveRel, Blocks.AIR);
            helper.assertFalse(lidBlocked(helper, pos), "上方方块拆掉后掀盖又能翻开");
            helper.setBlock(aboveRel, Blocks.TORCH);
            helper.assertTrue(lidBlocked(helper, pos), "火把没有碰撞箱但看得见, 同样会和掀盖穿模");
            helper.setBlock(aboveRel, Blocks.AIR);

            helper.setBlock(STATION_REL, Blocks.AIR);
            helper.setBlock(aboveRel, Blocks.STONE);
            item.place(placeOnTopOf(helper, player, item, SUPPORT_REL));
            helper.assertTrue(lidBlocked(helper, pos), "放下时上方已有方块, 应直接判为掀盖合着");

            player.setShiftKeyDown(true);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            useOn(helper, player, pos);
            helper.assertTrue(isLit(helper, pos) && lidBlocked(helper, pos), "上方有方块时照样能开工, 只是掀盖合着");
            helper.setBlock(aboveRel, Blocks.AIR);
            helper.assertTrue(isLit(helper, pos) && !lidBlocked(helper, pos), "工作中拆掉上方方块, 掀盖随即翻开");
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
                // 1.20.1 遇到严格整格的碰撞箱时, 模型所有轴对齐的面都去取朝向那一侧邻格的光: 台面摆件、内凹面在旁边
                // 贴着不透光整格时发黑。九台一律不许是严格整格, 以后有人顺手改回整格这里会变红。
                helper.assertFalse(state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
                        entry.getId() + " 的碰撞箱不能是严格整格 (模型面会去取邻格的光而发黑): " + state);
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

        // 铸铁烤箱灶: 选择框贴着灶面 (y12) 和背板 (z13..16); 碰撞箱同选择框 (不照说明的整格, 理由见 CookingStations)。
        BlockState ovenB = ChefBlocks.BAKING_OVEN_STEEL.get().defaultBlockState();
        assertInside(helper, ovenB, Direction.NORTH, 8, 14, 4, false, "铸铁烤箱灶炉架上方");
        assertInside(helper, ovenB, Direction.NORTH, 8, 14, 14.5, true, "铸铁烤箱灶背板");
        assertInside(helper, ovenB, Direction.WEST, 14.5, 14, 8, true, "铸铁烤箱灶朝西时背板在东侧");
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState turned = ovenB.setValue(CookingStationBlock.FACING, facing);
            assertSame(helper, outline(turned), collision(turned), "铸铁烤箱灶朝 " + facing + " 的选择框与碰撞箱");
        }

        // 吊炉: 台基 + 炉身 + 两级炉肩 + 烟口的阶梯, 四向共用。
        BlockState ovenC = ChefBlocks.BAKING_OVEN_CHINESE.get().defaultBlockState();
        assertBounds(helper, outline(ovenC), 0, 0, 0, 16, 16, 16, "吊炉外包");
        assertInside(helper, ovenC, Direction.NORTH, 8, 15, 8, true, "吊炉烟口");
        assertInside(helper, ovenC, Direction.NORTH, 3, 15, 3, false, "吊炉圆顶外侧");
        assertInside(helper, ovenC, Direction.NORTH, 0.5, 10, 0.5, false, "吊炉炉身四周留 1 像素");
        assertSame(helper, outline(ovenC), collision(ovenC), "吊炉选择框与碰撞箱");

        // 木质备餐台、中式案台: 选择框按说明的整格; 碰撞箱整格再并上一块高出台面的摆件 (调料架 / 蒸笼),
        // 不是严格整格, 但台顶承重面仍是整面 (火把、红石照常能放), 天光也不从柜子里漏下去。
        assertFullOutlineWithRaisedDecor(helper, ChefBlocks.PREP_COUNTER_RUSTIC, 8, 18, 13, 3, 18, 8, "调料架");
        assertFullOutlineWithRaisedDecor(helper, ChefBlocks.PREP_COUNTER_CHINESE, 12, 19, 12, 4, 19, 12, "蒸笼");

        // 冷藏备餐台: 前半台面 y12 + 后半冷藏槽 (z7..16) 到顶, 碰撞箱同选择框。
        BlockState prepB = ChefBlocks.PREP_COUNTER_STEEL.get().defaultBlockState();
        assertInside(helper, prepB, Direction.NORTH, 8, 14, 3, false, "冷藏备餐台砧板上方");
        assertInside(helper, prepB, Direction.NORTH, 8, 14, 12, true, "冷藏备餐台冷藏槽");
        assertInside(helper, prepB, Direction.WEST, 3, 14, 8, false, "冷藏备餐台朝西时砧板在西侧");
        assertSame(helper, outline(prepB), collision(prepB), "冷藏备餐台选择框与碰撞箱");
        helper.succeed();
    }

    /** 遮挡、视线、窒息、刷怪、材质强度与发光等级逐台对照方案说明。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void occlusionAndLightMatchEachDesignsNotes(GameTestHelper helper) {
        // 炸锅 C 明确要求不调 noOcclusion; 炸锅 A 照农夫乐事厨锅也不调; 其余七台必须调。
        BlockPos pos = helper.absolutePos(STATION_REL);
        for (RegistryObject<Block> entry : ChefBlocks.COOKING_STATIONS) {
            boolean expectOcclusion = entry == ChefBlocks.DEEP_FRYER_CHINESE || entry == ChefBlocks.DEEP_FRYER_RUSTIC;
            BlockState state = entry.get().defaultBlockState();
            helper.assertTrue(state.canOcclude() == expectOcclusion,
                    entry.getId() + (expectOcclusion ? " 不得调 noOcclusion" : " 必须 noOcclusion"));
            helper.assertFalse(state.isValidSpawn(helper.getLevel(), pos, EntityType.ZOMBIE),
                    entry.getId() + " 是家具, 怪物不得在台面上生成");
        }
        BlockState ovenB = ChefBlocks.BAKING_OVEN_STEEL.get().defaultBlockState();
        BlockState prepB = ChefBlocks.PREP_COUNTER_STEEL.get().defaultBlockState();
        helper.assertFalse(ovenB.isViewBlocking(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), "铸铁烤箱灶不挡视线");
        helper.assertFalse(prepB.isViewBlocking(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), "冷藏备餐台不挡视线");
        helper.assertFalse(prepB.isSuffocating(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), "冷藏备餐台不使实体窒息");

        // 金属三台: 硬度与抗爆都同原版熔炉 (3.5 / 3.5)。
        for (RegistryObject<Block> metal : List.of(ChefBlocks.DEEP_FRYER_STEEL, ChefBlocks.BAKING_OVEN_STEEL,
                ChefBlocks.PREP_COUNTER_STEEL)) {
            float hardness = metal.get().defaultBlockState().getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            helper.assertTrue(hardness == Blocks.FURNACE.defaultBlockState()
                            .getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                            && metal.get().getExplosionResistance() == Blocks.FURNACE.getExplosionResistance(),
                    metal.getId() + " 的硬度/抗爆应同原版熔炉, 实为 " + hardness + " / "
                            + metal.get().getExplosionResistance());
        }

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
        Vec3 drift = StationParticles.rotateVector(Direction.EAST, 0.0D, -0.005D, -0.03D);
        helper.assertTrue(drift.x == 0.03D && drift.z == 0.0D, "朝东时往前 (-z) 的漂移应转成往东 (+x), 实为 " + drift);
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
     * 各台发射器的实际出生点 (用收集用的 Sink 跑几百次): 除木质备餐台外每台工作中都出粒子; 出生点不远离方块;
     * 会往下落的冷气 (SNOWFLAKE) 不得出生在碰撞箱里, 否则一落就被下一层盒子卡住、在模型里看不见。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void emittersSpawnWhereTheParticlesCanBeSeen(GameTestHelper helper) {
        for (RegistryObject<Block> entry : ChefBlocks.COOKING_STATIONS) {
            CookingStationBlock block = station(entry);
            BlockState state = block.defaultBlockState().setValue(CookingStationBlock.LIT, true);
            VoxelShape collision = collision(state);
            List<double[]> spawned = new ArrayList<>();
            List<double[]> falling = new ArrayList<>();
            RandomSource random = RandomSource.create(20261010L);
            for (int i = 0; i < 400; i++) {
                block.particles().animate((particle, px, py, pz, vx, vy, vz) -> {
                    spawned.add(new double[]{px, py, pz});
                    if (particle == ParticleTypes.SNOWFLAKE) {
                        falling.add(new double[]{px, py, pz});
                    }
                }, random);
            }
            boolean silent = entry == ChefBlocks.PREP_COUNTER_RUSTIC;
            helper.assertTrue(spawned.isEmpty() == silent,
                    entry.getId() + (silent ? " 方案没要求粒子, 不该出粒子" : " 工作中应出粒子"));
            for (double[] at : spawned) {
                helper.assertTrue(at[0] >= -1.0D && at[0] <= 17.0D && at[2] >= -1.0D && at[2] <= 17.0D
                                && at[1] >= -1.0D && at[1] <= 25.0D,
                        entry.getId() + " 的出生点离方块太远: (" + at[0] + ", " + at[1] + ", " + at[2] + ")");
            }
            for (double[] at : falling) {
                boolean inside = false;
                for (AABB box : collision.toAabbs()) {
                    inside |= box.contains(at[0] / 16.0D, at[1] / 16.0D, at[2] / 16.0D);
                }
                helper.assertFalse(inside, entry.getId() + " 往下落的冷气出生在碰撞箱里, 会被卡住看不见: ("
                        + at[0] + ", " + at[1] + ", " + at[2] + ")");
            }
        }
        helper.succeed();
    }

    /**
     * 结构方块、投影类工具会旋转 / 镜像方块: 只转 FACING, 其余属性 (lit、托架、掀盖) 原样保留。
     * 镜像口径: LEFT_RIGHT 翻转南北 (Z 轴), FRONT_BACK 翻转东西 (X 轴)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rotationAndMirrorOnlyTurnTheFacing(GameTestHelper helper) {
        for (RegistryObject<Block> entry : ChefBlocks.COOKING_STATIONS) {
            BlockState north = entry.get().defaultBlockState()
                    .setValue(CookingStationBlock.FACING, Direction.NORTH)
                    .setValue(CookingStationBlock.LIT, true);
            if (north.hasProperty(StovetopFryerBlock.SUPPORT)) {
                north = north.setValue(StovetopFryerBlock.SUPPORT, StovetopFryerBlock.Support.TRAY);
            }
            if (north.hasProperty(RefrigeratedPrepBlock.LID_BLOCKED)) {
                north = north.setValue(RefrigeratedPrepBlock.LID_BLOCKED, true);
            }
            BlockState east = north.setValue(CookingStationBlock.FACING, Direction.EAST);
            assertTurned(helper, entry, north.rotate(Rotation.CLOCKWISE_90), north, Direction.EAST, "顺时针转 90 度");
            assertTurned(helper, entry, north.rotate(Rotation.CLOCKWISE_180), north, Direction.SOUTH, "转 180 度");
            assertTurned(helper, entry, north.rotate(Rotation.COUNTERCLOCKWISE_90), north, Direction.WEST, "逆时针转 90 度");
            assertTurned(helper, entry, north.mirror(Mirror.LEFT_RIGHT), north, Direction.SOUTH, "LEFT_RIGHT 镜像");
            assertTurned(helper, entry, north.mirror(Mirror.FRONT_BACK), north, Direction.NORTH, "FRONT_BACK 镜像 (朝北不变)");
            assertTurned(helper, entry, east.mirror(Mirror.FRONT_BACK), north, Direction.WEST, "FRONT_BACK 镜像 (朝东变朝西)");
        }
        helper.succeed();
    }

    /**
     * 掉落与挖掘工具。九台都掉自己 (不要求正确工具); 砖/金属类进 mineable/pickaxe, 木质备餐台进 mineable/axe。
     * 两份都是 src/main/resources 下手工维护的共享文件 (登记在 module-registry.json 的 sharedResources)。
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
                    entry.getId() + (wooden ? " 应进 mineable/axe" : " 不进 mineable/axe"));
            helper.assertFalse(state.requiresCorrectToolForDrops(), entry.getId() + " 不要求正确工具才掉落");
        }
        helper.succeed();
    }

    /**
     * 配方: 烹饪逻辑接入前九个配方都用 forge:false 条件关着 (台子还没有功能, 生存玩家不该白花材料合出来),
     * 所以 RecipeManager 里找不到它们; 但配方文件本身要能用: 只引用原版材料, 解析后按格子摆上材料能合出自己,
     * 而且这一格不被任何已加载的配方抢走 (开放时不会和别的配方冲突)。
     * 接入烹饪逻辑时: 删掉九个配方的 conditions、去掉 CookingStationItem 的"尚未开放"提示, 并把下面的
     * {@link #RECIPES_OPEN} 改成 true, 本用例转为核对"RecipeManager 能找到、这一格合出的就是它"。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void recipesStayClosedButCraftFromAGrid(GameTestHelper helper) {
        AbstractContainerMenu menu = new AbstractContainerMenu(null, -1) {
            @Override
            public ItemStack quickMoveStack(Player player, int index) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean stillValid(Player player) {
                return true;
            }
        };
        for (RegistryObject<Item> entry : ChefItems.COOKING_STATIONS) {
            String id = entry.getId().getPath();
            ResourceLocation recipeId = new ResourceLocation(MiningConstants.MODID, "chef/" + id);
            JsonObject json = loadJsonResource("/data/miningdim/recipes/chef/" + id + ".json");

            helper.assertTrue(hasFalseCondition(json) != RECIPES_OPEN, recipeId
                    + (RECIPES_OPEN ? " 已开放, 不该再带 forge:false 条件" : " 在烹饪逻辑接入前必须带 forge:false 条件关着"));
            helper.assertTrue(helper.getLevel().getRecipeManager().byKey(recipeId).isPresent() == RECIPES_OPEN,
                    recipeId + (RECIPES_OPEN ? " 应已加载" : " 关着时 RecipeManager 里不该找得到"));

            JsonObject key = json.getAsJsonObject("key");
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

            ShapedRecipe recipe = RecipeSerializer.SHAPED_RECIPE.fromJson(recipeId, json);
            ItemStack result = recipe.getResultItem(helper.getLevel().registryAccess());
            helper.assertTrue(result.is(entry.get()) && result.getCount() == 1, recipeId + " 应产出一个 " + id);

            // 按配方文件的 pattern 往 3x3 格子里逐格摆材料 (标签取第一个成员); 每个非空格的材料都必须解析得出物品,
            // 否则这一格在游戏里永远摆不对 (例如标签写错时配方照样加载, 却合不出来)。
            JsonArray pattern = json.getAsJsonArray("pattern");
            helper.assertTrue(pattern.size() == 3, recipeId + " 的 pattern 应是 3 行");
            CraftingContainer grid = new TransientCraftingContainer(menu, 3, 3);
            for (int row = 0; row < 3; row++) {
                String line = pattern.get(row).getAsString();
                helper.assertTrue(line.length() == 3, recipeId + " 的 pattern 每行应是 3 格: \"" + line + "\"");
                for (int col = 0; col < 3; col++) {
                    char c = line.charAt(col);
                    if (c == ' ') {
                        continue;
                    }
                    ItemStack[] options = Ingredient.fromJson(key.get(String.valueOf(c))).getItems();
                    helper.assertTrue(options.length > 0, recipeId + " 的材料 '" + c + "' 解析不出任何物品");
                    grid.setItem(col + row * 3, options[0].copy());
                }
            }
            helper.assertTrue(recipe.matches(grid, helper.getLevel()), recipeId + " 按自己的 pattern 摆好后应能匹配");
            helper.assertTrue(recipe.assemble(grid, helper.getLevel().registryAccess()).is(entry.get()),
                    recipeId + " 合出来的应是 " + id);

            Optional<CraftingRecipe> claimed = helper.getLevel().getRecipeManager()
                    .getRecipeFor(RecipeType.CRAFTING, grid, helper.getLevel());
            if (RECIPES_OPEN) {
                helper.assertTrue(claimed.isPresent() && claimed.get().getId().equals(recipeId),
                        recipeId + " 的这一格应由它自己合出, 实为 " + claimed.map(CraftingRecipe::getId));
            } else {
                helper.assertTrue(claimed.isEmpty(), recipeId + " 的这一格已被别的配方占用, 开放时会冲突: "
                        + claimed.map(CraftingRecipe::getId));
            }
        }
        helper.succeed();
    }

    /** 中英文名按已拍板的名字表逐字核对; 物品提示的文案键两种语言都要有, 且提示内容符合各台的摆放要求。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyStationHasChineseAndEnglishNamesAndTooltips(GameTestHelper helper) {
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

        for (RegistryObject<Item> entry : ChefItems.COOKING_STATIONS) {
            CookingStationItem item = (CookingStationItem) entry.get();
            CookingStationBlock block = (CookingStationBlock) item.getBlock();
            List<String> keys = item.tooltipKeys();
            for (String tooltipKey : keys) {
                helper.assertTrue(zh.has(tooltipKey) && en.has(tooltipKey), entry.getId() + " 的提示键缺中文或英文: " + tooltipKey);
            }
            helper.assertTrue(keys.get(0).equals(CookingStationItem.kindKey(block.kind())),
                    entry.getId() + " 的第一行提示应写功能种类");
            helper.assertTrue(keys.contains(CookingStationItem.WIP_KEY), entry.getId() + " 应提示烹饪功能尚未开放");
            helper.assertTrue(keys.contains(CookingStationItem.NEEDS_HEAT_KEY) == (entry == ChefItems.DEEP_FRYER_RUSTIC),
                    entry.getId() + " 的\"要热源\"提示只属于炉上油锅");
            boolean risesAbove = entry == ChefItems.PREP_COUNTER_RUSTIC || entry == ChefItems.PREP_COUNTER_CHINESE
                    || entry == ChefItems.BAKING_OVEN_RUSTIC;
            helper.assertTrue(keys.contains(CookingStationItem.KEEP_ABOVE_CLEAR_KEY) == risesAbove,
                    entry.getId() + (risesAbove ? " 的摆件/烟囱高出方块顶, 应提示上方留空"
                            : " 没有高出方块顶的部分 (或会自动收起), 不该提示上方留空"));
        }
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

    private static boolean lidBlocked(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getBlockState(pos).getValue(RefrigeratedPrepBlock.LID_BLOCKED);
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
        assertInShape(helper, outline(state.setValue(CookingStationBlock.FACING, facing)), px, py, pz, expected,
                what + " (选择框)");
    }

    private static void assertInShape(GameTestHelper helper, VoxelShape shape, double px, double py, double pz,
                                      boolean expected, String what) {
        boolean inside = false;
        for (AABB box : shape.toAabbs()) {
            inside |= box.contains(px / 16.0D, py / 16.0D, pz / 16.0D);
        }
        helper.assertTrue(inside == expected, what + (expected ? " 应在" : " 不应在") + "形状内 ("
                + px + ", " + py + ", " + pz + ")");
    }

    /**
     * 整格观感的备餐台: 选择框整格、天光不下漏、台顶是承重整面, 碰撞箱在 north 朝向的 (nx, ny, nz) 处多出一块
     * 高出方块顶的摆件, 朝东时这块摆件转到 (ex, ey, ez)。
     */
    private static void assertFullOutlineWithRaisedDecor(GameTestHelper helper, RegistryObject<Block> entry,
                                                         double nx, double ny, double nz,
                                                         double ex, double ey, double ez, String decor) {
        BlockState state = entry.get().defaultBlockState();
        helper.assertTrue(Block.isShapeFullBlock(outline(state)), entry.getId() + " 的选择框应为整格");
        helper.assertFalse(state.propagatesSkylightDown(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
                entry.getId() + " 是实心柜子, 天光不能从它里面漏下去");
        helper.assertTrue(state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP),
                entry.getId() + " 的台顶仍应是承重整面");
        assertInShape(helper, collision(state), 8, 8, 8, true, entry.getId() + " 碰撞箱的柜体");
        assertInShape(helper, collision(state), nx, ny, nz, true, entry.getId() + " 碰撞箱朝北时的" + decor);
        assertInShape(helper, collision(state.setValue(CookingStationBlock.FACING, Direction.EAST)), ex, ey, ez, true,
                entry.getId() + " 碰撞箱朝东时的" + decor);
        assertInShape(helper, collision(state.setValue(CookingStationBlock.FACING, Direction.EAST)), nx, ny, nz, false,
                entry.getId() + " 碰撞箱朝东时, 朝北那块" + decor + "的位置应已空出");
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

    private static void assertTurned(GameTestHelper helper, RegistryObject<Block> entry, BlockState turned,
                                     BlockState original, Direction expectedFacing, String how) {
        helper.assertTrue(turned.getValue(CookingStationBlock.FACING) == expectedFacing
                        && turned.setValue(CookingStationBlock.FACING, Direction.NORTH) == original,
                entry.getId() + " " + how + "后应朝 " + expectedFacing + " 且其余属性不变, 实为 " + turned);
    }

    private static void assertLight(GameTestHelper helper, RegistryObject<Block> entry, int lit, int idle) {
        BlockState state = entry.get().defaultBlockState();
        int litLight = state.setValue(CookingStationBlock.LIT, true).getLightEmission();
        int idleLight = state.setValue(CookingStationBlock.LIT, false).getLightEmission();
        helper.assertTrue(litLight == lit && idleLight == idle,
                entry.getId() + " 发光应为 工作中 " + lit + " / 待机 " + idle + ", 实为 " + litLight + " / " + idleLight);
    }

    private static boolean hasFalseCondition(JsonObject recipe) {
        JsonArray conditions = recipe.getAsJsonArray("conditions");
        if (conditions == null) {
            return false;
        }
        for (JsonElement condition : conditions) {
            if ("forge:false".equals(condition.getAsJsonObject().get("type").getAsString())) {
                return true;
            }
        }
        return false;
    }

    static JsonObject loadJsonResource(String path) {
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
