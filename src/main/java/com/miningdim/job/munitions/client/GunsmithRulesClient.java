package com.miningdim.job.munitions.client;

import com.miningdim.job.munitions.MunitionsAmmoFactory;
import com.miningdim.job.munitions.gunsmith.GunsmithTaczStatsHandler;
import net.minecraft.client.Minecraft;

/** 客户端收到规则快照后刷新本地持枪属性缓存。 */
public final class GunsmithRulesClient {

    private GunsmithRulesClient() {
    }

    public static void refreshHeldGun() {
        if (!MunitionsAmmoFactory.isTaczLoaded() || Minecraft.getInstance().player == null) {
            return;
        }
        GunsmithTaczStatsHandler.refreshHeldGun(Minecraft.getInstance().player);
    }
}
