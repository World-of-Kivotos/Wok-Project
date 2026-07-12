package com.miningdim.job.munitions.gunsmith;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.MunitionsAmmoFactory;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * TACZ 枪械物化隔离层。正式服有 TACZ 时生成真枪，dev/GameTest 未加载 TACZ 时返回 EMPTY。
 */
public final class GunsmithGunFactory {

    public static final ResourceLocation M4A1_ID = new ResourceLocation(MiningConstants.MODID, "m4a1_gunsmith");

    private static final ResourceLocation M4_DEFAULT_STOCK =
            new ResourceLocation(MunitionsAmmoFactory.TACZ_MODID, "stock_m4ss");

    private GunsmithGunFactory() {
    }

    public static ItemStack materializeM4A1() {
        if (!MunitionsAmmoFactory.isTaczLoaded()) {
            return ItemStack.EMPTY;
        }
        return buildM4A1();
    }

    private static ItemStack buildM4A1() {
        return GunItemBuilder.create()
                .setId(M4A1_ID)
                .setCount(1)
                .putAttachment(AttachmentType.STOCK, M4_DEFAULT_STOCK)
                .build();
    }
}
