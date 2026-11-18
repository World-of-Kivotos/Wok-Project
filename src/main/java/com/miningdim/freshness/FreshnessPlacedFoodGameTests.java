package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** 放置食物记录的区块存读测试；事件与掉落测试随运行入口接入。 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessPlacedFoodGameTests {
    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_placed";
    private static final int SLOT = 16;
    private static final BlockPos CAKE_REL = new BlockPos(1, 1, 1);

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void recordsFollowTheChunkData(GameTestHelper helper) {
        FreshnessConfig.Mode previousMode = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.DRY_RUN);
        FreshnessPlacedFood.resetForTest();
        try {
            int slot = 3;
            ServerLevel level = helper.getLevel();
            long nowMinute = FreshnessClockData.nowMinute(level);
            BlockPos pos = helper.absolutePos(cake(slot, CAKE_REL));
            ChunkPos chunkPos = new ChunkPos(pos);

            ItemStack cake = new ItemStack(Items.CAKE, 1);
            FreshnessData.stampAt(cake, nowMinute - 600L, 3);
            FreshnessPlacedFood.record(level, pos, cake, Blocks.CAKE.defaultBlockState());
            helper.assertTrue(FreshnessPlacedFood.at(level.dimension(), pos).isPresent(), "先记上");

            // 写进区块附加数据 → 清空内存 → 读回来 (区块卸载再开就是这个过程)。
            CompoundTag data = new CompoundTag();
            FreshnessPlacedFood.saveTo(level.dimension(), chunkPos, data);
            helper.assertTrue(data.contains(FreshnessPlacedFood.KEY_PLACED), "写进了区块附加数据");
            FreshnessPlacedFood.resetForTest();
            helper.assertTrue(FreshnessPlacedFood.at(level.dimension(), pos).isEmpty(), "内存清空了");
            FreshnessPlacedFood.loadFrom(level.dimension(), chunkPos, data);
            FreshnessPlacedFood.Placed back = FreshnessPlacedFood.at(level.dimension(), pos).orElse(null);
            helper.assertTrue(back != null, "读回来了");
            helper.assertTrue(back.batchMinute() == nowMinute - 600L && back.stoppedHours() == 3,
                    "批次时刻与停表小时原样: " + back);

            // 区块卸载: 内存那份丢掉 (存盘已经做过)。
            FreshnessPlacedFood.unload(level.dimension(), chunkPos);
            helper.assertTrue(FreshnessPlacedFood.at(level.dimension(), pos).isEmpty(), "卸载之后内存里不再留着");

            // 空记录不该写键。
            CompoundTag empty = new CompoundTag();
            FreshnessPlacedFood.saveTo(level.dimension(), chunkPos, empty);
            helper.assertTrue(!empty.contains(FreshnessPlacedFood.KEY_PLACED), "没有记录就不写这个键");
            helper.succeed();
        } finally {
            FreshnessPlacedFood.resetForTest();
            FreshnessConfig.MODE.set(previousMode);
        }
    }

    private static BlockPos cake(int slot, BlockPos relative) {
        return new BlockPos(slot * SLOT + relative.getX(), relative.getY(), relative.getZ());
    }
}
