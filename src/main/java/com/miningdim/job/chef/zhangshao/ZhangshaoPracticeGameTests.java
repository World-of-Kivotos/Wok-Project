package com.miningdim.job.chef.zhangshao;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.ChefBlocks;
import com.miningdim.testutil.MockGameTestPlayers;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ZhangshaoPracticeGameTests {
    private ZhangshaoPracticeGameTests() {}

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_zhangshao")
    public static void stationEntryOpensPracticeWithoutChangingInventoryOrXp(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            for (int index = 0; index < ChefBlocks.COOKING_STATIONS.size(); index++) {
                helper.assertTrue(ZhangshaoPracticeEvents.practiceStation(
                        ChefBlocks.COOKING_STATIONS.get(index).get().defaultBlockState(), player) == index / 3 + 1,
                        "Nine station appearances must route to their cooking kind");
            }
            helper.assertTrue(ZhangshaoPracticeEvents.practiceStation(Blocks.STONE.defaultBlockState(), player) == -1,
                    "Unrelated blocks must keep their interaction");
            player.setShiftKeyDown(true);
            helper.assertTrue(ZhangshaoPracticeEvents.practiceStation(
                    ChefBlocks.BAKING_OVEN_STEEL.get().defaultBlockState(), player) == -1,
                    "Creative empty-hand sneak must preserve the appearance preview");
            player.setShiftKeyDown(false);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD, 7));
            int experience = player.totalExperience;
            BlockPos position = helper.absolutePos(new BlockPos(1, 1, 1));
            helper.getLevel().setBlockAndUpdate(position, ChefBlocks.BAKING_OVEN_STEEL.get().defaultBlockState());
            PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(player,
                    InteractionHand.MAIN_HAND, position,
                    new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false));
            ZhangshaoPracticeEvents.rightClick(event);
            helper.assertTrue(event.isCanceled() && event.getCancellationResult() == InteractionResult.SUCCESS,
                    "Normal right-click must open the practice and consume the block interaction");
            helper.assertTrue(player.getMainHandItem().is(Items.BREAD)
                            && player.getMainHandItem().getCount() == 7 && player.totalExperience == experience,
                    "Practice entry must neither consume food nor grant experience");
            if (net.minecraftforge.fml.ModList.get().isLoaded("farmersdelight")) {
                var pot = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(
                        new net.minecraft.resources.ResourceLocation("farmersdelight", "cooking_pot"));
                player.setShiftKeyDown(true);
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.BOWL));
                helper.assertTrue(ZhangshaoPracticeEvents.practiceStation(pot.defaultBlockState(), player) == -1,
                        "A held off-hand container must keep the Farmer's Delight interaction");
                player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                helper.assertTrue(ZhangshaoPracticeEvents.practiceStation(pot.defaultBlockState(), player) == 0,
                        "Both empty hands and sneak must open pot practice");
            }
            helper.succeed();
        } finally {
            MockGameTestPlayers.logout(player);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_zhangshao")
    public static void openPacketPreservesFourStationsAndRejectsInvalidStation(GameTestHelper helper) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (int station = 0; station < 4; station++) {
                buffer.clear();
                ZhangshaoPracticeS2C.encode(new ZhangshaoPracticeS2C(station), buffer);
                helper.assertTrue(ZhangshaoPracticeS2C.decode(buffer).station() == station,
                        "Practice open packet must preserve station " + station);
            }
            buffer.clear();
            buffer.writeByte(255);
            boolean rejected = false;
            try {
                ZhangshaoPracticeS2C.decode(buffer);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "Unknown network station must be rejected instead of opening another kind");
            helper.succeed();
        } finally {
            buffer.release();
        }
    }
}
