package com.miningdim.job.brewer;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.brewer.cellar.WineCellarBlockEntity;
import com.miningdim.job.brewer.cellar.WineCellarRegistry;
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
    public static void survivalBreakingReturnsCellarAndAllFuel(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        BlockPos rel = new BlockPos(1, 1, 1);
        BlockPos pos = helper.absolutePos(rel);
        AABB area = new AABB(pos).inflate(0.5D);
        Set<UUID> prior = helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
                .map(ItemEntity::getUUID).collect(Collectors.toSet());
        try {
            Block block = WineCellarRegistry.WINE_CELLAR.get();
            helper.setBlock(rel, block.defaultBlockState());
            var cellar = (WineCellarBlockEntity) helper.getLevel().getBlockEntity(pos);
            ItemStackHandler inventory = cellar.inventory();
            ItemStack wine = new ItemStack(BrewerItems.itemFor(WineType.VODKA), 3);
            WineNbt.stamp(wine, WineQuality.LOW, player.getUUID());
            inventory.setStackInSlot(0, wine.copy());
            for (int i = 0; i < 3; i++) {
                inventory.setStackInSlot(WineCellarBlockEntity.FUEL_SLOT, new ItemStack(BrewerItems.DRIED_WHEAT.get(), 64));
                cellar.depositFuel();
            }
            ItemStack wheat = new ItemStack(BrewerItems.DRIED_WHEAT.get(), 7);
            inventory.setStackInSlot(WineCellarBlockEntity.FUEL_SLOT, wheat.copy());
            helper.assertTrue(player.gameMode.destroyBlock(pos), "生存玩家能正常拆除酒窖");
            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
                    .filter(entity -> !prior.contains(entity.getUUID())).toList();
            helper.assertTrue(count(drops, wine) == 3 && count(drops, wheat) == 199, "酒、交互槽和储备应足数返还");
            helper.assertTrue(count(drops, new ItemStack(block)) == 1, "返还酒窖方块");
            helper.assertTrue(drops.stream().allMatch(drop -> drop.getItem().getCount() <= 64), "返还物遵守堆叠上限");
        } finally {
            helper.getLevel().removeBlock(pos, false);
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
                    .filter(entity -> !prior.contains(entity.getUUID())).forEach(ItemEntity::discard);
            MockGameTestPlayers.logout(player);
        }
        helper.succeed();
    }

    private static int count(List<ItemEntity> drops, ItemStack expected) {
        return drops.stream().map(ItemEntity::getItem).filter(stack -> ItemStack.isSameItemSameTags(stack, expected))
                .mapToInt(ItemStack::getCount).sum();
    }
}
