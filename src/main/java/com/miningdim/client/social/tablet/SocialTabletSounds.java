package com.miningdim.client.social.tablet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 掏平板动画的音效（沿用步战 0.5.0-beta.4 的 12 个 ogg，素材已随本模块分发）。
 *
 * <p>文件名即 {@code assets/miningdim/sounds/tablet/<name>.ogg}，声音事件为
 * {@code miningdim:tablet.<name>}，在 {@code sounds.json} 里登记。这里只做“名字 → 播放”，
 * 时间轴由 {@link TabletMotion} 的 cue 事件决定。</p>
 */
public final class SocialTabletSounds {
    private static final Map<String, SoundEvent> EVENTS = new HashMap<>();

    private SocialTabletSounds() {
    }

    /** 播放一个 cue；未知名字静默忽略（换包/缺资源不该让动画崩）。 */
    public static void play(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        SoundEvent event = EVENTS.computeIfAbsent(name,
                key -> SoundEvent.createVariableRangeEvent(
                        new ResourceLocation("miningdim", "tablet." + key)));
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSoundManager() == null) {
            return;
        }
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(event, 1.0F, 0.85F));
    }
}
