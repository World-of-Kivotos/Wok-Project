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

import java.util.List;
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
        if (!MunitionsAmmoFactory.isTaczLoaded()) {
            return ItemStack.EMPTY;
        }
        Optional<List<FireMode>> resolvedSourceFireModes = GunsmithTaczBridge.findFireModes(blueprint.gunId());
        if (resolvedSourceFireModes.isEmpty()) {
            return ItemStack.EMPTY;
        }
        List<FireMode> sourceFireModes = resolvedSourceFireModes.get();
        List<FireMode> assembledFireModes;
        if (gunId.equals(blueprint.gunId())) {
            assembledFireModes = sourceFireModes;
        } else {
            Optional<List<FireMode>> resolvedAssembledFireModes = GunsmithTaczBridge.findFireModes(gunId);
            if (resolvedAssembledFireModes.isEmpty()) {
                return ItemStack.EMPTY;
            }
            assembledFireModes = resolvedAssembledFireModes.get();
        }
        FireMode initialFireMode;
        try {
            initialFireMode = GunsmithFireModePolicy.preserveAndSelectFirst(sourceFireModes, assembledFireModes);
        } catch (IllegalArgumentException exception) {
            LOGGER.error("Gunsmith blueprint gun {} fire modes {} do not match assembled gun {} fire modes {}; refusing assembly",
                    blueprint.gunId(), sourceFireModes, gunId, assembledFireModes, exception);
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
