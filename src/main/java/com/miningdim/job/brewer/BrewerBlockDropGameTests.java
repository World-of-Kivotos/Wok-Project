package com.miningdim.job.brewer;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.brewer.cellar.WineCellarBlockEntity;
import com.miningdim.job.brewer.cellar.WineCellarRegistry;
import com.miningdim.job.brewer.station.BrewingStationBlockEntity;
import com.miningdim.job.brewer.station.BrewingStationRegistry;
import com.miningdim.job.farmer.item.FarmerItems;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.ItemStackHandler;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class BrewerBlockDropGameTests {
    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "brewer")
    public static void survivalBreakingDropsBothDevicesAndTheirExactContents(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        BlockPos rel = new BlockPos(1, 1, 1);
        BlockPos pos = helper.absolutePos(rel);
        AABB area = new AABB(pos).inflate(0.5D);
        try {
            for (boolean cellar : new boolean[]{true, false}) {
                Set<UUID> prior = helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
                        .map(ItemEntity::getUUID).collect(Collectors.toSet());
                try {
                    Block block = cellar ? WineCellarRegistry.WINE_CELLAR.get() : BrewingStationRegistry.STATION_BLOCK.get();
                    helper.setBlock(rel, block.defaultBlockState());
                    ItemStackHandler inventory = cellar
                            ? ((WineCellarBlockEntity) helper.getLevel().getBlockEntity(pos)).inventory()
                            : ((BrewingStationBlockEntity) helper.getLevel().getBlockEntity(pos)).inventory();
                    ItemStack wine = new ItemStack(BrewerItems.itemFor(WineType.VODKA), 3);
                    WineNbt.stamp(wine, WineQuality.LOW, player.getUUID());
                    ItemStack first = cellar ? wine.copy() : new ItemStack(FarmerItems.FARMER_WHEAT.get(), 11);
                    ItemStack last = cellar ? new ItemStack(BrewerItems.DRIED_WHEAT.get(), 7) : wine.copy();
                    inventory.setStackInSlot(0, first.copy());
                    inventory.setStackInSlot(inventory.getSlots() - 1, last.copy());
                    helper.assertTrue(player.gameMode.destroyBlock(pos), "survival player breaks " + block);
                    List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
                            .filter(entity -> !prior.contains(entity.getUUID())).toList();
                    helper.assertTrue(count(drops, first) == first.getCount(), "all inputs/wine drop with their NBT");
                    helper.assertTrue(count(drops, last) == last.getCount(), "all fuel/output drops exactly once");
                    helper.assertTrue(count(drops, new ItemStack(block)) == 1, "the device itself drops exactly once: " + block);
                } finally {
                    helper.getLevel().removeBlock(pos, false);
                    helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
                            .filter(entity -> !prior.contains(entity.getUUID())).forEach(ItemEntity::discard);
                }
            }
        } finally {
            MockGameTestPlayers.logout(player);
        }
        helper.succeed();
    }

    private static int count(List<ItemEntity> drops, ItemStack expected) {
        return drops.stream().map(ItemEntity::getItem).filter(stack -> ItemStack.isSameItemSameTags(stack, expected))
                .mapToInt(ItemStack::getCount).sum();
    }
}
