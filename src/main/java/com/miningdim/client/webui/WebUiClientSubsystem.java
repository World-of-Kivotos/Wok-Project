package com.miningdim.client.webui;

import com.miningdim.core.Subsystem;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;

/**
 * Web UI 客户端子系统 (模块化铁律 3; 加入主类 List, 但 register 内 client-only 逻辑全部经 DistExecutor 关进
 * Dist.CLIENT lambda)。
 *
 * Dist 隔离 (架构铁律 1, 决定 GameTest 不崩): 本类被主类无条件实例化并 register, 但:
 *   - 必须用【unsafeRunWhenOn + 双箭头】DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> WebUiClient.initClient()):
 *     (a) 双箭头把对 WebUiClient (引用 MCEF/Minecraft/Screen) 的解析推迟进【内层】lambda 体, 内层只在客户端执行时才 classload;
 *     (b) 必须用 unsafe 变体: safe* 变体会跑 Forge SafeReferent 校验, 扫到 lambda 引用了 dist 专属类即抛
 *         "Unsafe Referent usage found in safe referent method" —— 本就是要引用客户端专属类, 故由 unsafe 变体 + 双箭头
 *         自行担保 classload 安全。两个反例 (均曾崩 CONSTRUCT): 单箭头 () -> WebUiClient::initClient 急切 bootstrap
 *         (invalid dist DEDICATED_SERVER); safeRunWhenOn 触 SafeReferent 校验 (Unsafe Referent usage)。
 *   - 开发命令注册监听器订阅在 forgeBus 上, 但 RegisterClientCommandsEvent 本身只在客户端逻辑端触发;
 *     命令执行体内对 WebUiClient 的引用同样经双箭头 DistExecutor 二次兜底, 杜绝任何路径在服务端触链 MCEF。
 *
 * 本子系统不下发 S2C / 不写世界, 纯客户端宿主装配。
 */
public final class WebUiClientSubsystem implements Subsystem {

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // MCEF 缺失优雅降级 (前置 mod 没装也不崩): ModList 守卫必须在【调用 initClient 之前】。WebUiClient.initClient 体
        // 引用 org.cef.*(CefMessageRouter) 与 WebUiBridge(extends CefMessageRouterHandlerAdapter), JVM 首次调用即验证/
        // 加载这些类, initClient 内部的 ModList 守卫为时已晚 (缺 MCEF -> NoClassDefFoundError 崩客户端, dev runClient 实测)。
        // 本类不引用 org.cef, 故在此守卫安全; 装了 MCEF 才进 Dist.CLIENT 内层 lambda 初始化浏览器宿主。服务端 dist 本就
        // 经 unsafeRunWhenOn(CLIENT) 跳过, 与本守卫叠加无副作用。
        if (ModList.get().isLoaded("mcef")) {
            // 客户端初始化关进 client-only 内层 lambda: unsafe 变体(safe* 会触 SafeReferent 校验拒 dist 专属引用) + 双箭头(WebUiClient 仅客户端执行内层体时 classload)。
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> WebUiClient.initClient());
        }
        // 客户端命令注册 (RegisterClientCommandsEvent 仅客户端逻辑端触发, 服务端不调本监听器体)。
        forgeBus.addListener(this::onRegisterClientCommands);
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("miningdim-webui-dev").executes(this::runDevCommand));
    }

    private int runDevCommand(CommandContext<CommandSourceStack> ctx) {
        // 同 register: 缺 MCEF 不触 openDevScreen (引用 org.cef), 给提示而非崩。
        if (!ModList.get().isLoaded("mcef")) {
            ctx.getSource().sendFailure(Component.literal("[miningdim] Web UI unavailable: MCEF mod is not installed."));
            return Command.SINGLE_SUCCESS;
        }
        // 二次 Dist 兜底 (双箭头): 即便此监听器体在非预期端被触发, openDevScreen 也只在客户端 classload/执行。
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> WebUiClient.openDevScreen());
        return Command.SINGLE_SUCCESS;
    }
}
