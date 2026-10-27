package com.miningdim.job.chef.zhangshao.client;

import net.minecraft.client.Minecraft;

/** 将 Screen 类型签名留在客户端类中，避免网络包注册时由 JVM 提前解析。 */
public final class ZhangshaoPracticeClient {
    private ZhangshaoPracticeClient() {}

    public static void open(int station) {
        Minecraft.getInstance().setScreen(new ZhangshaoStationScreen(station));
    }
}
