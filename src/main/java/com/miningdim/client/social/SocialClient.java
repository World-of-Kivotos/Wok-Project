package com.miningdim.client.social;

import com.miningdim.client.social.tablet.SocialTabletAnimation;
import com.miningdim.client.social.tablet.TabletHandStyle;
import com.miningdim.client.webui.WebUiClient;
import com.miningdim.core.MiningConstants;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.lwjgl.glfw.GLFW;

/**
 * 社交平板的客户端入口：~ 键与 /social 客户端命令。掏平板动画由页面自己播 (与预览定稿同一套
 * 时间轴), 因此这里只做 MCEF 前置守卫, 然后把界面交给 {@link WebUiClient}。
 */
public final class SocialClient {

    private static final String CATEGORY = "category." + MiningConstants.MODID + ".ui";

    private static KeyMapping openKey;
    private static KeyMapping shutterKey;

    private SocialClient() {
    }

    public static void init(IEventBus modBus, IEventBus forgeBus) {
        openKey = new KeyMapping(
                "key." + MiningConstants.MODID + ".social.open",
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_GRAVE_ACCENT,
                CATEGORY);
        shutterKey = new KeyMapping(
                "key." + MiningConstants.MODID + ".social.shutter",
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_ENTER,
                CATEGORY);
        modBus.addListener(SocialClient::onRegisterKeyMappings);
        forgeBus.addListener(SocialClient::onClientTick);
        forgeBus.addListener(SocialClient::onRegisterClientCommands);
        SocialCameraMode.init(forgeBus);
        WebUiClient.setSocialCameraOpener(SocialCameraMode::enter);
        SocialTabletAnimation.init(forgeBus);
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(openKey);
        event.register(shutterKey);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        boolean fired = false;
        while (openKey.consumeClick()) {
            fired = true;
        }
        if (fired && !SocialCameraMode.isActive()) {
            open();
        }
        while (shutterKey.consumeClick()) {
            if (SocialCameraMode.isActive()) {
                SocialCameraMode.requestShoot();
            }
        }
    }

    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("social").executes(context -> {
            open();
            return 1;
        }));
        // 验收辅助：把掏平板动画冻结在某个进度，方便固定姿态截图。
        event.getDispatcher().register(Commands.literal("socialanim")
                .then(Commands.literal("freeze")
                        .then(Commands.argument("p", DoubleArgumentType.doubleArg(0.0D, 1.0D))
                                .executes(context -> {
                                    SocialTabletAnimation.freezeAt(
                                            DoubleArgumentType.getDouble(context, "p"));
                                    return 1;
                                })))
                .then(Commands.literal("resume").executes(context -> {
                    SocialTabletAnimation.unfreeze();
                    return 1;
                }))
                .then(Commands.literal("reset").executes(context -> {
                    SocialTabletAnimation.reset();
                    return 1;
                }))
                .then(Commands.literal("hands")
                        .then(Commands.argument("style", StringArgumentType.word())
                                .executes(context -> {
                                    String value = StringArgumentType.getString(context, "style");
                                    TabletHandStyle style = TabletHandStyle.parse(value);
                                    if (!style.id().equalsIgnoreCase(value.trim())) {
                                        context.getSource().sendFailure(Component.literal(
                                                "用法: /socialanim hands glove|skin"));
                                        return 0;
                                    }
                                    SocialTabletAnimation.setHandStyle(style);
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "[MiningDim] 掏平板手部: " + style.id()), false);
                                    return 1;
                                }))));
    }

    private static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (SocialCameraMode.isActive() || mc.screen != null) {
            return;
        }
        // 开场动画期间再按 ~：跳到结尾（步战的 KEY finish，100ms 收尾），不重复开界面。
        if (SocialTabletAnimation.isOpening()) {
            SocialTabletAnimation.finishOpening();
            return;
        }
        // 先播掏出动画（页面在后台同时加载）；关闭动画或浏览器未就绪时退回直接开界面。
        if (SocialTabletAnimation.openFromWorld()) {
            return;
        }
        WebUiClient.openSocialScreen();
    }

}
