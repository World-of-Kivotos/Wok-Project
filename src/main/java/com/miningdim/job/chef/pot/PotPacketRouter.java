package com.miningdim.job.chef.pot;

import com.miningdim.core.auth.PlayerLoginGate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import vectorwing.farmersdelight.common.block.entity.container.CookingPotMenu;

/** Loaded only after the runtime FD mod presence check in FryerNetwork. */
public final class PotPacketRouter {
    private PotPacketRouter() {}
    private static CookingPotBlockEntity current(ServerPlayer player, BlockPos pos) {
        if (!PlayerLoginGate.allows(player) || !(player.containerMenu instanceof CookingPotMenu menu) || !menu.stillValid(player)
                || !menu.blockEntity.getBlockPos().equals(pos)) return null;
        return menu.blockEntity;
    }
    public static void start(ServerPlayer player, BlockPos pos) {
        CookingPotBlockEntity pot = current(player, pos);
        if (pot != null) PotCraftService.start(player, pot);
    }
    public static void input(ServerPlayer player, BlockPos pos, long round, int sequence, int firstTick,
                             byte[] inputs, int hash, boolean last) {
        CookingPotBlockEntity pot = current(player, pos);
        if (pot != null) PotCraftService.acceptInput(player, pot, round, sequence, firstTick, inputs, hash, last);
    }
    public static void abort(ServerPlayer player, BlockPos pos, long round) {
        CookingPotBlockEntity pot = current(player, pos);
        if (pot != null) PotCraftService.abort(player, pot, round, "主动撂勺");
    }
}
