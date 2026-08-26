package com.miningdim.job.munitions.gunsmith;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.MunitionsAmmoFactory;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

/**
 * TACZ 枪械物化隔离层。正式服有 TACZ 时生成真枪，dev/GameTest 未加载 TACZ 时返回 EMPTY。
 */
public final class GunsmithGunFactory {

    public static final ResourceLocation M4A1_ID = new ResourceLocation(MiningConstants.MODID, "m4a1_gunsmith");

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/gunsmith_gun_factory");
    private static final ResourceLocation M4_DEFAULT_STOCK =
            new ResourceLocation(MunitionsAmmoFactory.TACZ_MODID, "stock_m4ss");

    private GunsmithGunFactory() {
    }

    public static ItemStack materialize(ItemStack blueprintStack) {
        GunsmithBlueprint blueprint = GunsmithAssemblyRecipe.blueprint(blueprintStack);
        ResourceLocation gunId = GunsmithAssemblyRecipe.assembledGunId(blueprintStack);
        return materialize(blueprint, gunId, false);
    }

    public static ItemStack materialize(ItemStack blueprintStack,
                                        Map<GunsmithPressPart, ItemStack> parts) {
        GunsmithBlueprint blueprint = GunsmithAssemblyRecipe.blueprint(blueprintStack);
        ResourceLocation gunId = GunsmithAssemblyRecipe.assembledGunId(blueprintStack, parts);
        boolean forceThreeRoundBurst = blueprint.platform() == GunsmithPlatform.AR
                && gunId.equals(burstGunId(blueprint));
        return materialize(blueprint, gunId, forceThreeRoundBurst);
    }

    public static ResourceLocation burstGunId(GunsmithBlueprint blueprint) {
        if (blueprint.platform() != GunsmithPlatform.AR) {
            throw new IllegalArgumentException("Three-round-burst bolt only supports AR blueprints");
        }
        return new ResourceLocation(MiningConstants.MODID,
                blueprint.templateId() + "_gunsmith_burst");
    }

    private static ItemStack materialize(GunsmithBlueprint blueprint, ResourceLocation gunId,
                                         boolean forceThreeRoundBurst) {
        if (!MunitionsAmmoFactory.isTaczLoaded()) {
            return ItemStack.EMPTY;
        }
        Optional<GunsmithTaczBridge.FireModeProfile> resolvedSourceProfile =
                GunsmithTaczBridge.findFireModeProfile(blueprint.gunId());
        if (resolvedSourceProfile.isEmpty()) {
            return ItemStack.EMPTY;
        }
        GunsmithTaczBridge.FireModeProfile sourceProfile = resolvedSourceProfile.get();
        GunsmithTaczBridge.FireModeProfile assembledProfile;
        if (gunId.equals(blueprint.gunId())) {
            assembledProfile = sourceProfile;
        } else {
            Optional<GunsmithTaczBridge.FireModeProfile> resolvedAssembledProfile =
                    GunsmithTaczBridge.findFireModeProfile(gunId);
            if (resolvedAssembledProfile.isEmpty()) {
                return ItemStack.EMPTY;
            }
            assembledProfile = resolvedAssembledProfile.get();
        }
        FireMode initialFireMode;
        try {
            initialFireMode = forceThreeRoundBurst
                    ? GunsmithFireModePolicy.forceThreeRoundBurst(
                            sourceProfile.fireModes(), assembledProfile.fireModes(), FireMode.BURST,
                            assembledProfile.burstCount(), assembledProfile.continuousBurst())
                    : GunsmithFireModePolicy.preserveAndSelectFirst(
                            sourceProfile.fireModes(), assembledProfile.fireModes());
        } catch (IllegalArgumentException exception) {
            LOGGER.error("Gunsmith blueprint gun {} fire-mode profile {} is incompatible with assembled gun {} profile {}; refusing assembly",
                    blueprint.gunId(), sourceProfile, gunId, assembledProfile, exception);
            return ItemStack.EMPTY;
        }
        return build(gunId, blueprint, initialFireMode);
    }

    private static ItemStack build(ResourceLocation gunId, GunsmithBlueprint blueprint, FireMode initialFireMode) {
        GunItemBuilder builder = GunItemBuilder.create()
                .setId(gunId)
                .setFireMode(initialFireMode)
                .setCount(1);
        if (blueprint == GunsmithBlueprint.M4A1) {
            builder.putAttachment(AttachmentType.STOCK, M4_DEFAULT_STOCK);
        }
        return builder.build();
    }
}
