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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

public final class WebUiClientSubsystem implements Subsystem {
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/webui/client");
    private static final String WEBUI_CLIENT_CLASS = "com.miningdim.client.webui.WebUiClient";

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> initClientIfAvailable());
        forgeBus.addListener(this::onRegisterClientCommands);
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("miningdim-webui-dev").executes(this::runDevCommand));
    }

    private int runDevCommand(CommandContext<CommandSourceStack> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> openDevScreenIfAvailable(ctx));
        return Command.SINGLE_SUCCESS;
    }

    private static void initClientIfAvailable() {
        if (!isMcefRuntimeAvailable()) {
            LOGGER.info("[miningdim] MCEF/JCEF not available; skipping WebUI client bootstrap");
            return;
        }
        invokeWebUiClient("initClient");
    }

    private static void openDevScreenIfAvailable(CommandContext<CommandSourceStack> ctx) {
        if (!isMcefRuntimeAvailable()) {
            ctx.getSource().sendFailure(Component.literal("MCEF is not installed; WebUI dev screen is unavailable."));
            return;
        }
        invokeWebUiClient("openDevScreen");
    }

    private static boolean isMcefRuntimeAvailable() {
        return ModList.get().isLoaded("mcef")
                && hasClass("com.cinemamod.mcef.MCEF")
                && hasClass("org.cef.handler.CefMessageRouterHandler");
    }

    private static boolean hasClass(String className) {
        try {
            Class.forName(className, false, WebUiClientSubsystem.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ex) {
            return false;
        }
    }

    private static void invokeWebUiClient(String methodName) {
        try {
            Class<?> clientClass = Class.forName(WEBUI_CLIENT_CLASS);
            Method method = clientClass.getMethod(methodName);
            method.invoke(null);
        } catch (ReflectiveOperationException | LinkageError ex) {
            LOGGER.warn("[miningdim] WebUI client call {} failed; WebUI disabled for this session", methodName, ex);
        }
    }
}
