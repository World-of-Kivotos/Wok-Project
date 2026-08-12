package com.miningdim.client.webui;

import com.miningdim.core.MiningConstants;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import org.lwjgl.glfw.GLFW;

/**
 * 游戏内 Web UI 的键位入口 (决策 J3: 平板 hub 走键位绑定, 不做平板物品 —— 物品形态要额外处理掉落/死亡/复制,
 * 而 hub 是纯只读入口不该有获取门槛)。仅客户端加载。
 *
 * 事件总线拆分同 {@link com.miningdim.job.miner.client.MinerKeyMappings}: {@link RegisterKeyMappingsEvent} 是
 * mod 总线事件 (内部静态 {@link ModBus}), 客户端 tick 在 forge 总线 (本类自身, 默认 FORGE 订阅)。
 *
 * 默认键位刻意与矿工技能键不同 —— 矿工 8 个技能键全部默认未绑定以避冲突, 但本键是整套 UI 的**唯一常规入口**,
 * 未绑定等于功能对玩家不可见。故给一个原版未占用的默认键 G; 万一与其它 mod 撞键, 原版控制设置界面会标出冲突,
 * 玩家可自行改绑, 代价远小于"默认没有入口"。
 *
 * MCEF 缺失守卫 (同 {@link WebUiClientSubsystem} 的既有纪律): 本类自身不引用 org.cef, 对 {@link WebUiClient}
 * 的调用一律先过 {@code ModList.isLoaded("mcef")} —— WebUiClient 体内引用 MCEF, 未装前置 mod 时触链即
 * NoClassDefFoundError 崩客户端。
 */
@Mod.EventBusSubscriber(modid = MiningConstants.MODID, value = Dist.CLIENT)
public final class WebUiKeyMappings {

    private WebUiKeyMappings() {
    }

    /** RegisterKeyMappingsEvent 是 mod 总线事件, 单独挂 MOD 总线订阅器 (与 tick 的 forge 订阅分开)。 */
    @Mod.EventBusSubscriber(modid = MiningConstants.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {

        private ModBus() {
        }

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(OPEN_WEB_UI);
        }
    }

    private static final String CATEGORY = "category." + MiningConstants.MODID + ".ui";

    /** 打开平板 hub。默认 G (原版未占用), 玩家可在控制设置改绑。 */
    public static final KeyMapping OPEN_WEB_UI = new KeyMapping(
            "key." + MiningConstants.MODID + ".ui.open",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            CATEGORY);

    /**
     * forgeBus 客户端 tick: 轮询 consumeClick, 命中即打开 UI。
     *
     * consumeClick 在 while 内排空按下队列后只开一次 —— 一次 tick 内连按多下不该叠开多个 Screen。
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        boolean fired = false;
        while (OPEN_WEB_UI.consumeClick()) {
            fired = true;
        }
        if (!fired) {
            return;
        }
        if (!ModList.get().isLoaded("mcef")) {
            hintMcefMissing();
            return;
        }
        WebUiClient.openWebUi();
    }

    /** 未装 MCEF 时的优雅降级提示 (同 WebUiClient.hint 范式: 经本地玩家 actionbar, 不崩不弹异常)。 */
    private static void hintMcefMissing() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("[MiningDim] Web UI 不可用: 未安装前置 mod MCEF。"), false);
        }
    }
}
