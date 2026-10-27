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
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ZhangshaoPracticeGameTests {
    private ZhangshaoPracticeGameTests() {}

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_zhangshao")
    public static void stationEntryOpensPracticeWithoutChangingInventoryOrXp(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        try {
            for (int index = 0; index < ChefBlocks.COOKING_STATIONS.size(); index++) {
                helper.assertTrue(ZhangshaoPracticeEvents.practiceStation(
                        ChefBlocks.COOKING_STATIONS.get(index).get().defaultBlockState(), player) == index / 3 + 1,
                        "Nine station appearances must route to their cooking kind");
            }
            helper.assertTrue(ZhangshaoPracticeEvents.practiceStation(Blocks.STONE.defaultBlockState(), player) == -1,
                    "Unrelated blocks must keep their interaction");
            int experience = player.totalExperience;
            BlockPos position = helper.absolutePos(new BlockPos(1, 1, 1));
            helper.getLevel().setBlockAndUpdate(position, ChefBlocks.BAKING_OVEN_STEEL.get().defaultBlockState());
            // 这些都是玩家正常右键时会继续触发使用动作的物品。
            for (ItemStack held : new ItemStack[]{new ItemStack(Items.BREAD, 7),
                    new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.ENDER_PEARL, 3)}) {
                player.setItemInHand(InteractionHand.MAIN_HAND, held.copy());
                InteractionResult interaction = player.gameMode.useItemOn(player, helper.getLevel(),
                        player.getMainHandItem(), InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false));
                helper.assertTrue(interaction == InteractionResult.SUCCESS,
                        "Station menu entry must consume the real Forge interaction with " + held);
                helper.assertTrue(ItemStack.matches(player.getMainHandItem(), held)
                                && !player.isUsingItem() && player.totalExperience == experience,
                        "Practice entry must preserve held items, avoid item use, and grant no experience");
            }
            player.setShiftKeyDown(true);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 2));
            BlockPos placed = position.relative(Direction.EAST);
            helper.getLevel().setBlockAndUpdate(placed, Blocks.AIR.defaultBlockState());
            InteractionResult placement = player.gameMode.useItemOn(player, helper.getLevel(),
                    player.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(position).add(0.5, 0, 0), Direction.EAST, position, false));
            helper.assertTrue(placement.consumesAction() && helper.getLevel().getBlockState(placed).is(Blocks.STONE)
                            && player.getMainHandItem().getCount() == 1,
                    "Sneaking with a block must place it beside the station and consume one in survival");
            if (net.minecraftforge.fml.ModList.get().isLoaded("farmersdelight")) {
                var pot = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(
                        new net.minecraft.resources.ResourceLocation("farmersdelight", "cooking_pot"));
                player.setShiftKeyDown(true);
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.BOWL));
                helper.assertTrue(ZhangshaoPracticeEvents.practiceStation(pot.defaultBlockState(), player) == -1,
                        "A held off-hand container must keep the Farmer's Delight interaction");
                player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                helper.assertTrue(ZhangshaoPracticeEvents.practiceStation(pot.defaultBlockState(), player) == -1,
                        "Farmer's Delight always keeps its own interaction; practice is a screen button");
            }
            ServerPlayer creative = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            try {
                creative.setShiftKeyDown(true);
                helper.assertTrue(ZhangshaoPracticeEvents.practiceStation(
                        ChefBlocks.BAKING_OVEN_STEEL.get().defaultBlockState(), creative) == -1,
                        "Creative empty-hand sneak must preserve the appearance preview");
            } finally {
                MockGameTestPlayers.logout(creative);
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
