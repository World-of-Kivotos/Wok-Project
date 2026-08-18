package com.miningdim.power.rubber;

import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** 橡胶树干绝对时间冷却与物品化重放回归。 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class RubberGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "power_rubber";

    private RubberGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rubberRegistrationsExposeExpectedRuntimeContracts(GameTestHelper helper) {
        helper.assertTrue(BuiltInRegistries.BLOCK.getKey(PowerRubberRegistry.RUBBER_LOG.get())
                        .equals(id("rubber_log")),
                "rubber_log 必须以固定 ID 注册");
        helper.assertTrue(BuiltInRegistries.BLOCK.getKey(PowerRubberRegistry.RUBBER_SAPLING.get())
                        .equals(id("rubber_tree_sapling")),
                "橡胶树苗必须使用 rubber_tree_sapling 注册 ID");
        helper.assertTrue(PowerRubberRegistry.RUBBER_LOG_ITEM.get() instanceof BlockItem blockItem
                        && blockItem.getBlock() == PowerRubberRegistry.RUBBER_LOG.get(),
                "rubber_log 物品必须绑定橡胶原木方块");
        helper.assertTrue(PowerRubberRegistry.RUBBER_TAPPING_KNIFE.get().getMaxDamage() == 128,
                "割胶刀耐久必须精确为 128");
        helper.assertTrue(PowerRubberRegistry.RUBBER_LOG_BE.get().create(BlockPos.ZERO,
                        PowerRubberRegistry.RUBBER_LOG.get().defaultBlockState()) instanceof RubberLogBlockEntity,
                "rubber_log 方块实体类型必须构造 RubberLogBlockEntity");
        helper.assertTrue(PowerRubberRegistry.RUBBER_LOG.get().getTicker(helper.getLevel(),
                        PowerRubberRegistry.RUBBER_LOG.get().defaultBlockState(), PowerRubberRegistry.RUBBER_LOG_BE.get()) == null,
                "割胶原木不得注册逐 tick 方块实体 ticker");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tappingUsesExactAbsoluteTimeCooldownAndOnlyDamagesOnSuccess(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerLevelData levelData = (ServerLevelData) level.getLevelData();
        long originalGameTime = level.getGameTime();
        BlockPos relative = new BlockPos(2, 2, 2);
        BlockPos pos = helper.absolutePos(relative);
        RubberLogBlock logBlock = PowerRubberRegistry.RUBBER_LOG.get();
        ItemStack knife = new ItemStack(PowerRubberRegistry.RUBBER_TAPPING_KNIFE.get());
        net.minecraft.server.level.ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        player.setItemInHand(InteractionHand.MAIN_HAND, knife);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        long firstTapTime = 12_345L;

        try {
            levelData.setGameTime(firstTapTime);
            helper.setBlock(relative, logBlock.defaultBlockState());
            logBlock.use(level.getBlockState(pos), level, pos, player, InteractionHand.MAIN_HAND, hit);
            RubberLogBlockEntity log = rubberLogAt(level, pos);
            helper.assertTrue(log.wasTapped() && log.nextTapGameTime()
                            == firstTapTime + RubberLogBlockEntity.TAP_COOLDOWN_TICKS,
                    "首次割胶必须写入精确的绝对下次时间");
            helper.assertTrue(level.getBlockState(pos).getValue(RubberLogBlock.TAPPED),
                    "首次割胶后方块外观必须切为 tapped=true");
            helper.assertTrue(knife.getDamageValue() == 1 && latexDrops(level, pos) == 1,
                    "首次割胶必须精确产出 1 胶乳且只损耗 1 点耐久");

            levelData.setGameTime(log.nextTapGameTime() - 1L);
            logBlock.use(level.getBlockState(pos), level, pos, player, InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(knife.getDamageValue() == 1 && latexDrops(level, pos) == 1,
                    "冷却最后一 tick 必须拒绝割胶，不产物也不损刀");

            levelData.setGameTime(log.nextTapGameTime());
            logBlock.use(level.getBlockState(pos), level, pos, player, InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(knife.getDamageValue() == 2 && latexDrops(level, pos) == 2,
                    "绝对时间恰到 nextTapGameTime 时必须恢复一次割胶");
            helper.assertTrue(rubberLogAt(level, pos).nextTapGameTime()
                            == firstTapTime + 2L * RubberLogBlockEntity.TAP_COOLDOWN_TICKS,
                    "第二次成功后下次绝对时间必须从边界时刻重新计算");
        } finally {
            levelData.setGameTime(originalGameTime);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nonKnifeInteractionLeavesUntappedLogUntouched(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos relative = new BlockPos(2, 2, 2);
        BlockPos pos = helper.absolutePos(relative);
        RubberLogBlock logBlock = PowerRubberRegistry.RUBBER_LOG.get();
        net.minecraft.server.level.ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);

        helper.setBlock(relative, logBlock.defaultBlockState());
        helper.assertTrue(logBlock.use(level.getBlockState(pos), level, pos, player, InteractionHand.MAIN_HAND, hit)
                        == net.minecraft.world.InteractionResult.PASS,
                "非割胶刀右键必须交还交互，不得吞掉其他物品行为");
        helper.assertTrue(!rubberLogAt(level, pos).wasTapped() && latexDrops(level, pos) == 0,
                "非割胶刀右键不得改变冷却或产生胶乳");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tappedLogLootReplaysBlockEntityTagWithoutResettingCooldown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerLevelData levelData = (ServerLevelData) level.getLevelData();
        long originalGameTime = level.getGameTime();
        BlockPos sourceRelative = new BlockPos(2, 2, 2);
        BlockPos targetRelative = new BlockPos(4, 2, 2);
        BlockPos source = helper.absolutePos(sourceRelative);
        BlockPos target = helper.absolutePos(targetRelative);
        RubberLogBlock logBlock = PowerRubberRegistry.RUBBER_LOG.get();
        net.minecraft.server.level.ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        long tapTime = 54_321L;

        try {
            levelData.setGameTime(tapTime);
            helper.setBlock(sourceRelative, logBlock.defaultBlockState());
            RubberLogBlockEntity sourceLog = rubberLogAt(level, source);
            helper.assertTrue(sourceLog.tryTap(tapTime), "测试前置割胶必须成功");
            List<ItemStack> drops = Block.getDrops(level.getBlockState(source), level, source, sourceLog);
            ItemStack droppedLog = drops.stream()
                    .filter(stack -> stack.is(PowerRubberRegistry.RUBBER_LOG_ITEM.get()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("rubber_log 真实掉落缺少方块物品"));
            CompoundTag blockEntityTag = BlockItem.getBlockEntityData(droppedLog);
            helper.assertTrue(blockEntityTag != null
                            && blockEntityTag.getLong("NextTapGameTime") == sourceLog.nextTapGameTime()
                            && blockEntityTag.getBoolean("WasTapped"),
                    "被割胶原木掉落必须携带完整 BlockEntityTag 冷却状态");

            helper.setBlock(targetRelative, logBlock.defaultBlockState());
            boolean restored = BlockItem.updateCustomBlockEntityTag(level, player, target, droppedLog);
            helper.assertTrue(restored, "BlockItem 放置重放必须将 BlockEntityTag 写入新原木");
            logBlock.setPlacedBy(level, target, level.getBlockState(target), player, droppedLog);
            RubberLogBlockEntity restoredLog = rubberLogAt(level, target);
            helper.assertTrue(restoredLog.wasTapped()
                            && restoredLog.nextTapGameTime() == sourceLog.nextTapGameTime()
                            && level.getBlockState(target).getValue(RubberLogBlock.TAPPED),
                    "拆放后的原木必须保留冷却与 tapped 外观，不能重置为可立即割胶");
            helper.assertTrue(!restoredLog.tryTap(tapTime),
                    "拆放后的原木在原冷却截止前必须继续拒绝割胶");
        } finally {
            levelData.setGameTime(originalGameTime);
        }
        helper.succeed();
    }

    private static RubberLogBlockEntity rubberLogAt(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof RubberLogBlockEntity log) {
            return log;
        }
        throw new IllegalStateException("missing rubber log block entity at " + pos);
    }

    private static int latexDrops(ServerLevel level, BlockPos pos) {
        return level.getEntitiesOfClass(ItemEntity.class, AABB.ofSize(Vec3.atCenterOf(pos), 3.0D, 3.0D, 3.0D),
                item -> item.getItem().is(PowerRubberRegistry.LATEX.get())).stream()
                .mapToInt(item -> item.getItem().getCount())
                .sum();
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(MiningConstants.MODID, path);
    }
}
