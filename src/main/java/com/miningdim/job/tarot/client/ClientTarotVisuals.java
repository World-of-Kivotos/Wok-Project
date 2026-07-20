package com.miningdim.job.tarot.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.miningdim.job.tarot.TarotCardItem;
import com.miningdim.job.tarot.TarotCastTiming;
import com.miningdim.job.tarot.TarotQuality;
import com.miningdim.job.tarot.TarotRegistry;
import com.miningdim.job.tarot.TarotSounds;
import com.miningdim.job.tarot.network.TarotCastVisualS2C;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 原创星术塔罗演出: 旋转双星环、倾斜星轨、占卜卡光点与收束星爆。视觉语言参考占星术士的星盘和卡环,
 * 不使用或复制任何外部游戏素材。单次网络包只启动本地 16 tick 动画。
 */
public final class ClientTarotVisuals {

    private static final int ORBIT_TICKS = TarotCastTiming.CARD_REVEAL_TICKS;
    private static final int RESOLVE_TICKS = TarotCastTiming.EFFECT_RESOLVE_TICKS;
    private static final int HOLD_END_TICK = 124;
    private static final int DURATION_TICKS = 136;
    private static final int MAX_ACTIVE_CASTS = 32;
    private static final List<Cast> ACTIVE = new ArrayList<>();

    private record Cast(int entityId, int cardId, TarotQuality quality, boolean upright, int age) {
        Cast nextFrame() {
            return new Cast(entityId, cardId, quality, upright, age + 1);
        }
    }

    private record Palette(Vector3f primary, Vector3f secondary) {
    }

    private ClientTarotVisuals() {
    }

    public static void register(IEventBus forgeBus) {
        forgeBus.addListener(ClientTarotVisuals::onClientTick);
        forgeBus.addListener(ClientTarotVisuals::onRenderLevel);
    }

    public static void accept(TarotCastVisualS2C message) {
        if (ACTIVE.size() >= MAX_ACTIVE_CASTS) {
            ACTIVE.remove(0);
        }
        ACTIVE.add(new Cast(message.casterEntityId(), message.cardId(), message.quality(), message.upright(), 0));
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            ACTIVE.clear();
            return;
        }

        Iterator<Cast> iterator = ACTIVE.iterator();
        List<Cast> next = new ArrayList<>(ACTIVE.size());
        while (iterator.hasNext()) {
            Cast cast = iterator.next();
            Entity caster = level.getEntity(cast.entityId());
            if (caster == null || cast.age() >= DURATION_TICKS) {
                continue;
            }
            renderFrame(level, caster, cast);
            next.add(cast.nextFrame());
        }
        ACTIVE.clear();
        ACTIVE.addAll(next);
    }

    private static void renderFrame(ClientLevel level, Entity caster, Cast cast) {
        Palette palette = palette(cast.quality(), cast.upright());
        DustParticleOptions primary = new DustParticleOptions(palette.primary(), cast.quality() == TarotQuality.SHINY ? 1.25F : 0.9F);
        DustParticleOptions secondary = new DustParticleOptions(palette.secondary(), cast.quality() == TarotQuality.SHINY ? 1.1F : 0.8F);
        double phase = cast.age() * 0.24D + cast.cardId() * 0.31D;
        double x = caster.getX();
        double y = caster.getY();
        double z = caster.getZ();

        if (cast.age() < ORBIT_TICKS + 10) {
            renderGroundAstrolabe(level, primary, secondary, x, y + 0.08D, z, phase);
            renderCelestialGlobe(level, primary, secondary, x, y + 1.25D, z, phase);
        }
        if ((cast.age() & 1) == 0) {
            renderHeadHalo(level, primary, secondary, x, y + caster.getBbHeight() + 0.95D, z, phase);
        }

        playSoundCues(level, caster, cast);
        if (cast.age() == ORBIT_TICKS) {
            renderStarburst(level, palette, x, y + caster.getBbHeight() + 0.95D, z,
                    cast.quality() == TarotQuality.SHINY ? 36 : 24);
        }
        if (cast.age() == RESOLVE_TICKS) {
            renderStarburst(level, palette, x, y + caster.getBbHeight() + 0.95D, z,
                    cast.quality() == TarotQuality.SHINY ? 24 : 16);
        }
    }

    private static void playSoundCues(ClientLevel level, Entity caster, Cast cast) {
        if (cast.age() == 0) {
            level.playLocalSound(caster.getX(), caster.getY() + caster.getBbHeight() + 0.8D, caster.getZ(),
                    TarotSounds.CAST_START.get(), SoundSource.PLAYERS, 0.85F, 1.0F, false);
            return;
        }
        if (cast.age() == RESOLVE_TICKS) {
            float pitch = 0.88F + cast.quality().ordinal() * 0.055F;
            level.playLocalSound(caster.getX(), caster.getY() + caster.getBbHeight() + 0.8D, caster.getZ(),
                    SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.9F, pitch, false);
        }
    }

    /** 头顶中心完整卡面: 读取本次 cardId/quality/upright, 使用现有卡牌模型与品质边框全亮度渲染。 */
    private static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ACTIVE.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        float partialTick = event.getPartialTick();

        for (Cast cast : ACTIVE) {
            Entity caster = level.getEntity(cast.entityId());
            if (caster == null) {
                continue;
            }
            float age = cast.age() + partialTick;
            float orbitProgress = Mth.clamp(age / ORBIT_TICKS, 0.0F, 1.0F);
            float revealProgress = Mth.clamp((age - ORBIT_TICKS) / 8.0F, 0.0F, 1.0F);
            float fade = age <= HOLD_END_TICK
                    ? 1.0F
                    : 1.0F - Mth.clamp((age - HOLD_END_TICK) / (DURATION_TICKS - HOLD_END_TICK), 0.0F, 1.0F);
            float scale = (0.42F + orbitProgress * 0.18F + revealProgress * 0.34F) * fade;
            if (scale <= 0.02F) {
                continue;
            }
            double x = net.minecraft.util.Mth.lerp(partialTick, caster.xOld, caster.getX());
            double y = net.minecraft.util.Mth.lerp(partialTick, caster.yOld, caster.getY())
                    + caster.getBbHeight() + 0.95D;
            double z = net.minecraft.util.Mth.lerp(partialTick, caster.zOld, caster.getZ());
            if (age < ORBIT_TICKS) {
                double angle = -Math.PI / 2.0D + Math.PI * 2.0D * orbitProgress;
                double radius = 0.92D * (1.0D - 0.18D * orbitProgress);
                x += Math.cos(angle) * radius;
                z += Math.sin(angle) * radius;
                y += Math.sin(angle * 2.0D) * 0.12D;
            } else {
                y += Math.sin((age - ORBIT_TICKS) * 0.11D) * 0.045D;
            }
            ItemStack card = TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), cast.cardId(),
                    cast.quality(), cast.upright(), caster.getUUID());

            pose.pushPose();
            pose.translate(x - camera.x, y - camera.y, z - camera.z);
            pose.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
            pose.mulPose(Axis.YP.rotationDegrees(180.0F));
            if (age < ORBIT_TICKS) {
                pose.mulPose(Axis.ZP.rotationDegrees(orbitProgress * 360.0F));
                pose.mulPose(Axis.YP.rotationDegrees(orbitProgress * 720.0F));
            } else {
                pose.mulPose(Axis.ZP.rotationDegrees((float) Math.sin((age - ORBIT_TICKS) * 0.08D) * 2.5F));
            }
            pose.scale(scale, scale, scale);
            minecraft.getItemRenderer().renderStatic(card, ItemDisplayContext.FIXED, 0x00F000F0,
                    OverlayTexture.NO_OVERLAY, pose, buffers, level, cast.cardId());
            pose.popPose();
        }
        buffers.endBatch();
    }

    /** 卡面周围的小型占卜星环, 与地面星盘共享配色但围绕头顶中心旋转。 */
    private static void renderHeadHalo(ClientLevel level, DustParticleOptions primary,
                                       DustParticleOptions secondary,
                                       double x, double y, double z, double phase) {
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * 2.0D * i / 8.0D - phase * 1.25D;
            double radius = (i & 1) == 0 ? 0.62D : 0.48D;
            add(level, (i & 1) == 0 ? primary : secondary,
                    x + Math.cos(angle) * radius,
                    y + Math.sin(angle) * radius,
                    z);
        }
    }

    private static void renderGroundAstrolabe(ClientLevel level, DustParticleOptions primary,
                                               DustParticleOptions secondary,
                                               double x, double y, double z, double phase) {
        for (int i = 0; i < 12; i++) {
            double angle = Math.PI * 2.0D * i / 12.0D + phase;
            add(level, primary, x + Math.cos(angle) * 1.35D, y, z + Math.sin(angle) * 1.35D);
            if ((i & 1) == 0) {
                double inner = -angle * 1.4D;
                add(level, secondary, x + Math.cos(inner) * 0.72D, y + 0.03D, z + Math.sin(inner) * 0.72D);
            }
        }
    }

    private static void renderCelestialGlobe(ClientLevel level, DustParticleOptions primary,
                                              DustParticleOptions secondary,
                                              double x, double y, double z, double phase) {
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * 2.0D * i / 8.0D + phase * 1.35D;
            double cosine = Math.cos(angle);
            double sine = Math.sin(angle);
            add(level, primary, x + cosine * 0.88D, y + sine * 0.48D, z + sine * 0.28D);
            add(level, secondary, x + sine * 0.30D, y + cosine * 0.50D, z + sine * 0.90D);
        }
        add(level, ParticleTypes.END_ROD, x, y, z, 0.0D, 0.015D, 0.0D);
    }

    private static void renderStarburst(ClientLevel level, Palette palette,
                                        double x, double y, double z, int rays) {
        level.addParticle(ParticleTypes.FLASH, x, y, z, 0.0D, 0.0D, 0.0D);
        DustParticleOptions dust = new DustParticleOptions(palette.secondary(), 1.35F);
        for (int i = 0; i < rays; i++) {
            double azimuth = Math.PI * 2.0D * i / rays;
            double elevation = ((i % 5) - 2) * 0.16D;
            double horizontal = Math.sqrt(1.0D - elevation * elevation);
            double vx = Math.cos(azimuth) * horizontal * 0.16D;
            double vz = Math.sin(azimuth) * horizontal * 0.16D;
            add(level, (i & 1) == 0 ? ParticleTypes.END_ROD : dust,
                    x, y, z, vx, elevation * 0.12D, vz);
        }
    }

    private static Palette palette(TarotQuality quality, boolean upright) {
        Vector3f primary = switch (quality) {
            case R -> new Vector3f(0.72F, 0.86F, 1.0F);
            case SR -> new Vector3f(0.18F, 0.78F, 1.0F);
            case SSR -> new Vector3f(0.58F, 0.36F, 1.0F);
            case UR -> new Vector3f(1.0F, 0.65F, 0.16F);
            case SHINY -> new Vector3f(0.42F, 0.95F, 1.0F);
        };
        Vector3f secondary = upright
                ? new Vector3f(1.0F, 0.82F, 0.34F)
                : new Vector3f(0.96F, 0.26F, 0.72F);
        return new Palette(primary, secondary);
    }

    private static void add(ClientLevel level, net.minecraft.core.particles.ParticleOptions particle,
                            double x, double y, double z) {
        add(level, particle, x, y, z, 0.0D, 0.0D, 0.0D);
    }

    private static void add(ClientLevel level, net.minecraft.core.particles.ParticleOptions particle,
                            double x, double y, double z, double vx, double vy, double vz) {
        level.addParticle(particle, x, y, z, vx, vy, vz);
    }
}
