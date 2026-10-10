package com.miningdim.donation.client;

import com.miningdim.donation.DonationView;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 服务端账本快照的客户端落点 (只经 DistExecutor 从网络包 handler 调入)。账本页没开着就丢弃:
 * 快照只是显示用, 过期了下次打开会重新请求。
 */
public final class DonationClientHooks {

    private DonationClientHooks() {
    }

    public static void acceptView(DonationView view, @Nullable Component notice) {
        if (Minecraft.getInstance().screen instanceof DonationLedgerScreen screen) {
            screen.accept(view, notice);
        }
    }
}
