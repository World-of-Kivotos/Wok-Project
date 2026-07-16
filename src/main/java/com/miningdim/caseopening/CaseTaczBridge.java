package com.miningdim.caseopening;

import com.miningdim.caseopening.store.CaseDao;
import com.miningdim.caseopening.store.SkinAssetRow;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import java.util.function.Predicate;

/** TaCZ boundary: applies an owned display to the main hand and strips displays whose owner no longer matches. */
public final class CaseTaczBridge {

    private static final String TAG_ASSET_ID = "MiningDimCaseAssetId";
    private static final String TAG_OWNER_ID = "MiningDimCaseOwnerId";
    private static final String TACZ_DISPLAY_TAG = "GunDisplayId";
    private static final String CASE_DISPLAY_PREFIX = "case_";
    private static final String CASE_DISPLAY_SUFFIX = "_display";

    private CaseTaczBridge() {
    }

    public static void apply(ServerPlayer player, SkinAssetRow asset) {
        ItemStack stack = player.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) {
            throw new IllegalStateException("主手物品不是 TaCZ 枪械");
        }
        ResourceLocation actualGun = gun.getGunId(stack);
        ResourceLocation expectedGun = requireLocation(asset.gunId(), "asset gun id");
        if (!expectedGun.equals(actualGun)) {
            throw new IllegalArgumentException("该皮肤仅适用于 " + expectedGun + "，当前为 " + actualGun);
        }
        ResourceLocation display = requireLocation(asset.displayId(), "asset display id");
        gun.setGunDisplayId(stack, display);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(TAG_ASSET_ID, asset.assetId().toString());
        tag.putString(TAG_OWNER_ID, asset.ownerId().toString());
        sync(player);
    }

    /** @return true when an unauthorized display was stripped. */
    public static boolean enforce(ServerPlayer player, ItemStack stack, CaseDao dao,
                                  Predicate<SkinAssetRow> settledOwnership) {
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) {
            return false;
        }
        ResourceLocation display = gun.getGunDisplayId(stack);
        if (!isCaseDisplay(display)) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        UUID assetId = parseUuid(tag == null ? null : tag.getString(TAG_ASSET_ID));
        SkinAssetRow asset = assetId == null ? null : dao.findOwnedAsset(player.getUUID(), assetId);
        ResourceLocation gunId = gun.getGunId(stack);
        boolean authorized = asset != null
                && asset.ownerId().equals(player.getUUID())
                && asset.displayId().equals(display.toString())
                && asset.gunId().equals(gunId.toString())
                && settledOwnership.test(asset);
        if (authorized) {
            return false;
        }
        resetToDefault(stack, gun, gunId);
        sync(player);
        return true;
    }

    private static void resetToDefault(ItemStack stack, IGun gun, ResourceLocation gunId) {
        ResourceLocation defaultDisplay = TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getPojo().getDisplay())
                .orElse(null);
        if (defaultDisplay != null) {
            gun.setGunDisplayId(stack, defaultDisplay);
        } else {
            CompoundTag tag = stack.getTag();
            if (tag != null) {
                tag.remove(TACZ_DISPLAY_TAG);
            }
        }
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(TAG_ASSET_ID);
            tag.remove(TAG_OWNER_ID);
        }
    }

    static boolean isCaseDisplay(ResourceLocation display) {
        return display != null
                && "miningdim".equals(display.getNamespace())
                && display.getPath().startsWith(CASE_DISPLAY_PREFIX)
                && display.getPath().endsWith(CASE_DISPLAY_SUFFIX);
    }

    private static ResourceLocation requireLocation(String raw, String label) {
        ResourceLocation location = ResourceLocation.tryParse(raw);
        if (location == null) {
            throw new IllegalStateException(label + " is invalid: " + raw);
        }
        return location;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void sync(ServerPlayer player) {
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }
}
