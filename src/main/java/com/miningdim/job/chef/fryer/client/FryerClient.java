package com.miningdim.job.chef.fryer.client;

import com.miningdim.job.chef.fryer.FryerRegistry;
import com.miningdim.job.chef.zhangshao.ZhangshaoKernel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/** 网络入口只路由到当前原生炸锅容器，正式对局不会替换为普通 Screen。 */
public final class FryerClient {
    private FryerClient() {}

    public static void registerScreens() {
        MenuScreens.register(FryerRegistry.menuType(), FryerScreen::new);
    }

    public static void session(BlockPos pos, long roundId, int seed, int[] params, ItemStack dish) {
        if (Minecraft.getInstance().screen instanceof FryerScreen screen && screen.getMenu().pos().equals(pos)) {
            screen.startSession(roundId, seed, params, dish);
        }
    }

    public static void bottle(long roundId, boolean gold) {
        if (Minecraft.getInstance().screen instanceof FryerScreen screen) screen.revealBottle(roundId, gold);
    }

    public static void result(long roundId, ZhangshaoKernel.Result result, String message) {
        if (Minecraft.getInstance().screen instanceof FryerScreen screen) screen.serverResult(roundId, result, message);
    }
}
