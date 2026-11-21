package com.miningdim.job.farmer.granary;

import com.miningdim.freshness.FreshnessCategories;
import com.miningdim.freshness.FreshnessCategory;
import com.miningdim.freshness.FreshnessConfig;
import com.miningdim.freshness.FreshnessData;
import com.miningdim.job.chef.ChefQualityNbt;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Server-side admission shared by the bin menu and one-click deposit. */
public final class GranaryAdmission {

    public enum Rejection {
        NONE,
        NOT_ALLOWED,
        NOT_ACTIVE,
        GRANARY_DOWN,
        DAILY_LIMIT,
        NOT_PERISHABLE,
        EXEMPT,
        CHEF_QUALITY,
        ROTTEN,
        MIGRATED,
        EXHAUSTED,
        FRESH
    }

    public record Context(GranaryTier tier, boolean publicDonor, Rejection rejection) {

        public boolean accepted() {
            return rejection == Rejection.NONE;
        }
    }

    private GranaryAdmission() {
    }

    public static Context context(ServerLevel level, GranaryBinBlockEntity bin, Player player, long nowMinute) {
        boolean trusted = GranaryAccess.mayStore(bin.owner(), bin.access(), player.getUUID());
        boolean operator = isOperator(player);
        boolean publicDonor = false;
        if (!trusted && !operator) {
            if (!(player instanceof ServerPlayer serverPlayer)
                    || !GranaryPublicService.donationOpen(level, bin)) {
                return new Context(null, false, Rejection.NOT_ALLOWED);
            }
            if (!GranaryPublicService.mayDonate(level, bin, serverPlayer, nowMinute)) {
                long day = GranaryPublic.dayOf(nowMinute);
                if (GranaryPublic.remainingDonations(bin.publicSettings(),
                        bin.publicUsage(player.getUUID(), day)) == 0) {
                    return new Context(null, false, Rejection.DAILY_LIMIT);
                }
                return new Context(null, false, Rejection.NOT_ALLOWED);
            }
            publicDonor = true;
        }

        GranarySavedData data = GranarySavedData.get(level.getServer().overworld());
        ResourceLocation dimension = level.dimension().location();
        if (!data.binState(dimension, bin.getBlockPos()).acceptsDeposits()) {
            return new Context(null, publicDonor, Rejection.NOT_ACTIVE);
        }
        GranaryRecord record = data.granary(data.granaryOfBin(dimension, bin.getBlockPos()));
        if (record == null || !record.acceptsDeposits()) {
            return new Context(null, publicDonor, Rejection.GRANARY_DOWN);
        }
        return new Context(record.tier(), publicDonor, Rejection.NONE);
    }

    public static Rejection itemRejection(GranaryTier tier, ItemStack stack, long nowMinute) {
        if (stack.isEmpty() || !FreshnessCategories.spoils(stack)) {
            return Rejection.NOT_PERISHABLE;
        }
        if (FreshnessCategories.isExempt(stack)) {
            return Rejection.EXEMPT;
        }
        if (ChefQualityNbt.hasQuality(stack)) {
            return Rejection.CHEF_QUALITY;
        }
        if (FreshnessConfig.replacementEnabled()) {
            if (FreshnessData.isRotten(stack, FreshnessCategories.shelfLifeHours(stack), nowMinute)) {
                return Rejection.ROTTEN;
            }
            if (FreshnessData.isMigrated(stack)) {
                return Rejection.MIGRATED;
            }
            if (GranarySettle.capExhausted(stack)) {
                return Rejection.EXHAUSTED;
            }
        }
        if (!tier.acceptsFresh()
                && FreshnessCategories.categoryOf(stack).orElse(null) == FreshnessCategory.FRESH) {
            return Rejection.FRESH;
        }
        return Rejection.NONE;
    }

    public static boolean accepts(ServerLevel level, GranaryBinBlockEntity bin, Player player,
                                  ItemStack stack, long nowMinute) {
        Context context = context(level, bin, player, nowMinute);
        return context.accepted() && itemRejection(context.tier(), stack, nowMinute) == Rejection.NONE;
    }

    public static boolean isOperator(Player player) {
        return player instanceof ServerPlayer serverPlayer && serverPlayer.hasPermissions(2);
    }
}
