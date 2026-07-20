package com.miningdim.job.tarot.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.tarot.TarotSounds;
import com.miningdim.job.tarot.TarotQuality;
import com.miningdim.job.tarot.craft.TarotCraftMenu;
import com.miningdim.job.tarot.craft.TarotCraftService;
import com.miningdim.menu.AbstractMiningScreen;
import com.mojang.math.Axis;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

import java.util.Optional;

/** Celestial two-card synthesis screen with a live astrolabe and exact outcome odds. */
public final class TarotCraftScreen extends AbstractMiningScreen<TarotCraftMenu> {

    private static final ResourceLocation BG =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/container/tarot_craft.png");
    private static final ResourceLocation GLYPHS =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/container/tarot_craft_glyphs.png");

    private static final int W = 218;
    private static final int H = 222;
    private static final int CORE_X = 109;
    private static final int CORE_Y = 59;
    private static final int BUTTON_X = 94;
    private static final int BUTTON_Y = 89;
    private static final int BUTTON_W = 31;
    private static final int BUTTON_H = 23;
    private static final int[] RESULT_X = {17, 67, 117, 167};
    private static final int RESULT_Y = 116;
    private static final int RESULT_W = 46;
    private static final int RESULT_H = 12;
    private static final int[] RESULT_COLORS = {0xFF8BEBFF, 0xFFBE70F6, 0xFFE0BE68, 0xFFEE5C70};
    private static final float CARD_REVEAL_TICKS = 68.0F;
    private static final float TOTAL_SEQUENCE_TICKS =
            TarotCraftStarfieldEffect.DURATION_TICKS + CARD_REVEAL_TICKS;
    private static final int CARD_WIDTH = 54;
    private static final int CARD_HEIGHT = 96;
    private static final int CARD_TEXTURE_WIDTH = 184;
    private static final int CARD_TEXTURE_HEIGHT = 326;

    private int observedOutcomeSequence = -1;
    private float resultEffectStart = Float.NEGATIVE_INFINITY;
    private long resultEffectSeed;
    private TarotCraftService.Result activeResultEffect;
    private TarotCraftMenu.RevealCard activeRevealCard;
    private boolean revealSoundPlayed;

    public TarotCraftScreen(TarotCraftMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, BG, W, H);
        this.titleLabelX = 0;
        this.titleLabelY = 8;
        this.inventoryLabelX = 28;
        this.inventoryLabelY = 132;
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawCenteredString(this.font, this.title, W / 2, this.titleLabelY, 0xEAF8FF);
        graphics.drawString(this.font, this.playerInventoryTitle,
                this.inventoryLabelX, this.inventoryLabelY, 0x9BEEFF, false);
    }

    @Override
    protected void renderExtra(GuiGraphics graphics, int leftPos, int topPos,
                               int mouseX, int mouseY, float partialTick) {
        Optional<TarotQuality> source = this.menu.previewInputQuality();
        boolean ready = source.isPresent();
        float time = (this.minecraft == null || this.minecraft.level == null)
                ? partialTick
                : this.minecraft.level.getGameTime() + partialTick;

        // Opposing rotations evoke the astrologian star globe without a custom screen renderer.
        renderRotatingGlyph(graphics, leftPos + CORE_X, topPos + CORE_Y,
                time * (ready ? 2.8F : 0.65F), 0);
        renderRotatingGlyph(graphics, leftPos + CORE_X, topPos + CORE_Y,
                -time * (ready ? 1.9F : 0.4F), 32);

        int pulse = ready ? Mth.floor(2.0F + (Mth.sin(time * 0.25F) + 1.0F) * 1.5F) : 1;
        int glow = ready ? 0xFFBDF7FF : 0xFF477A9A;
        graphics.fill(leftPos + CORE_X - pulse, topPos + CORE_Y - pulse,
                leftPos + CORE_X + pulse + 1, topPos + CORE_Y + pulse + 1, glow);

        Component target = targetLabel(source);
        graphics.drawCenteredString(this.font, target, leftPos + CORE_X, topPos + 21,
                ready ? 0xFFE8C879 : 0xFF8EB5CA);

        renderChanceStrip(graphics, leftPos, topPos, source);

        boolean buttonHover = inside(mouseX, mouseY,
                leftPos + BUTTON_X, topPos + BUTTON_Y, BUTTON_W, BUTTON_H);
        if (ready || buttonHover) {
            int outline = ready ? 0xFF9BEEFF : 0xFF6A8599;
            graphics.renderOutline(leftPos + BUTTON_X - 1, topPos + BUTTON_Y - 1,
                    BUTTON_W + 2, BUTTON_H + 2, outline);
        }

        updateResultEffect(time);
        if (activeResultEffect != null) {
            if (activeRevealCard == null) {
                activeRevealCard = this.menu.lastRevealCard().orElse(null);
            }
            float age = time - resultEffectStart;
            if (TarotCraftStarfieldEffect.isActive(age)) {
                renderSequenceInventoryVeil(graphics, leftPos, topPos);
                TarotCraftStarfieldEffect.render(graphics, leftPos, topPos,
                        age, activeResultEffect, isGreatSuccess(), resultEffectSeed);
            } else if (age < TOTAL_SEQUENCE_TICKS) {
                playRevealSoundOnce();
                renderCardReveal(graphics, leftPos, topPos,
                        age - TarotCraftStarfieldEffect.DURATION_TICKS);
            } else {
                activeResultEffect = null;
                activeRevealCard = null;
            }
        }
    }

    /** Consumes the synchronized sequence exactly once and starts the authoritative result effect. */
    private void updateResultEffect(float time) {
        int sequence = this.menu.outcomeSequence();
        if (observedOutcomeSequence < 0) {
            observedOutcomeSequence = sequence;
            return;
        }
        if (sequence == observedOutcomeSequence) {
            return;
        }
        observedOutcomeSequence = sequence;
        this.menu.lastOutcome().ifPresent(result -> {
            activeResultEffect = result;
            activeRevealCard = this.menu.lastRevealCard().orElse(null);
            resultEffectStart = time;
            resultEffectSeed = 0x9E3779B97F4A7C15L
                    ^ ((long) this.menu.containerId << 32)
                    ^ Integer.toUnsignedLong(sequence);
            revealSoundPlayed = false;
            playUiSound(TarotSounds.CAST_START.get());
        });
    }

    /** Hides the newly granted inventory stack until the celestial calculation has completed. */
    private void renderSequenceInventoryVeil(GuiGraphics graphics, int leftPos, int topPos) {
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 279.0F);
        graphics.fill(leftPos, topPos + 131, leftPos + W, topPos + H, 0xF00B1224);
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.miningdim.tarot.craft.calculating"),
                leftPos + W / 2, topPos + 169, 0xFFBDEEFF);
        graphics.pose().popPose();
    }

    /** Reveals a successful product only after the starfield effect has completely finished. */
    private void renderCardReveal(GuiGraphics graphics, int leftPos, int topPos, float revealAge) {
        // ContainerData entries arrive as individual packets; recover gracefully if the sequence
        // counter happened to be processed one render frame before the card snapshot.
        if (activeRevealCard == null) {
            activeRevealCard = this.menu.lastRevealCard().orElse(null);
        }
        float enter = smoothstep(0.0F, 9.0F, revealAge);
        float leave = 1.0F - smoothstep(CARD_REVEAL_TICKS - 9.0F, CARD_REVEAL_TICKS, revealAge);
        float alpha = enter * leave;
        float pulse = 0.96F + 0.04F * Mth.sin(revealAge * 0.18F);
        int centerX = leftPos + W / 2;
        int centerY = topPos + 63;

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 300.0F);
        graphics.fill(leftPos, topPos, leftPos + W, topPos + 132,
                withAlpha(0xFF090F22, alpha * 0.82F));

        if (activeRevealCard != null
                && (activeResultEffect == TarotCraftService.Result.SUCCESS
                || activeResultEffect == TarotCraftService.Result.REVERSE)) {
            float scale = (0.45F + 0.55F * easeOutBack(enter)) * pulse;
            renderRevealedCard(graphics, centerX, centerY, scale, alpha, activeRevealCard);
            if (isGreatSuccess()) {
                graphics.drawCenteredString(this.font,
                        Component.translatable("gui.miningdim.tarot.craft.great_success"),
                        centerX, topPos + 8, withAlpha(0xFFFFE27A, alpha));
            }
            Component quality = qualityName(activeRevealCard.quality());
            Component orientation = Component.translatable(activeRevealCard.upright()
                    ? "tooltip.miningdim.tarot.orientation.upright"
                    : "tooltip.miningdim.tarot.orientation.reversed");
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.miningdim.tarot.craft.revealed", quality, orientation),
                    centerX, topPos + 119, withAlpha(qualityColor(activeRevealCard.quality()), alpha));
        } else {
            Component result = Component.translatable("message.miningdim.tarot.craft.result."
                    + activeResultEffect.name().toLowerCase());
            int color = activeResultEffect == TarotCraftService.Result.BIG_SHATTER
                    ? 0xFFFF526F : 0xFFE58CFF;
            graphics.drawCenteredString(this.font, result, centerX, centerY - 5,
                    withAlpha(color, alpha));
            int radius = Mth.floor(12.0F + 22.0F * enter);
            graphics.renderOutline(centerX - radius, centerY - radius,
                    radius * 2 + 1, radius * 2 + 1, withAlpha(color, alpha * 0.65F));
        }
        graphics.pose().popPose();
    }

    private void renderRevealedCard(GuiGraphics graphics, int centerX, int centerY,
                                    float scale, float alpha, TarotCraftMenu.RevealCard card) {
        String id = card.cardId() < 10 ? "0" + card.cardId() : Integer.toString(card.cardId());
        ResourceLocation texture = new ResourceLocation(MiningConstants.MODID,
                "textures/gui/tarot/cards/" + id + ".png");
        int frame = withAlpha(qualityColor(card.quality()), alpha);

        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY, 1.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.fill(-CARD_WIDTH / 2 - 3, -CARD_HEIGHT / 2 - 3,
                CARD_WIDTH / 2 + 3, CARD_HEIGHT / 2 + 3, withAlpha(0xFF071124, alpha));
        graphics.fill(-CARD_WIDTH / 2 - 2, -CARD_HEIGHT / 2 - 2,
                CARD_WIDTH / 2 + 2, CARD_HEIGHT / 2 + 2, frame);
        if (!card.upright()) {
            graphics.pose().mulPose(Axis.ZP.rotationDegrees(180.0F));
        }
        graphics.blit(texture, -CARD_WIDTH / 2, -CARD_HEIGHT / 2,
                CARD_WIDTH, CARD_HEIGHT, 0.0F, 0.0F,
                CARD_TEXTURE_WIDTH, CARD_TEXTURE_HEIGHT,
                CARD_TEXTURE_WIDTH, CARD_TEXTURE_HEIGHT);
        graphics.pose().popPose();
    }

    private void playRevealSoundOnce() {
        if (revealSoundPlayed) {
            return;
        }
        revealSoundPlayed = true;
        SoundEvent sound = isGreatSuccess()
                ? TarotSounds.CRAFT_GREAT_SUCCESS.get()
                : switch (activeResultEffect) {
                    case SUCCESS -> TarotSounds.CRAFT_SUCCESS.get();
                    case REVERSE -> TarotSounds.CRAFT_REVERSE.get();
                    case SHATTER -> TarotSounds.CRAFT_SHATTER.get();
                    case BIG_SHATTER -> TarotSounds.CRAFT_BIG_SHATTER.get();
                };
        playUiSound(sound);
    }

    /** Shiny synthesis is a presentation upgrade, not an extra server-side RNG result. */
    private boolean isGreatSuccess() {
        return activeResultEffect == TarotCraftService.Result.SUCCESS
                && activeRevealCard != null
                && activeRevealCard.quality() == TarotQuality.SHINY;
    }

    private void playUiSound(SoundEvent sound) {
        if (this.minecraft != null && sound != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.0F));
        }
    }

    private void renderRotatingGlyph(GuiGraphics graphics, int centerX, int centerY,
                                     float degrees, int textureU) {
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY, 0.0F);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(degrees));
        graphics.blit(GLYPHS, -16, -16, textureU, 0, 32, 32, 64, 64);
        graphics.pose().popPose();
    }

    private void renderChanceStrip(GuiGraphics graphics, int leftPos, int topPos,
                                   Optional<TarotQuality> source) {
        double[] chances = source.map(TarotCraftService::chances)
                .map(value -> new double[]{value.success(), value.reverse(), value.shatter(), value.bigShatter()})
                .orElseGet(() -> new double[]{0.0D, 0.0D, 0.0D, 0.0D});
        for (int i = 0; i < RESULT_X.length; i++) {
            String value = source.isPresent() ? Math.round(chances[i] * 100.0D) + "%" : "--";
            graphics.drawCenteredString(this.font, value,
                    leftPos + RESULT_X[i] + 27, topPos + RESULT_Y + 2, RESULT_COLORS[i]);
        }
    }

    private Component targetLabel(Optional<TarotQuality> source) {
        if (source.isPresent()) {
            TarotQuality from = source.get();
            return Component.translatable("gui.miningdim.tarot.craft.target",
                    qualityName(from), qualityName(from.next()));
        }
        return Component.translatable(this.menu.hasAnyInput()
                ? "gui.miningdim.tarot.craft.mismatch"
                : "gui.miningdim.tarot.craft.insert_cards");
    }

    private static Component qualityName(TarotQuality quality) {
        return Component.translatable("tooltip.miningdim.tarot.quality." + quality.id());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (sequenceActive()) {
            return true;
        }
        if (inside(mouseX, mouseY, this.leftPos + BUTTON_X, this.topPos + BUTTON_Y, BUTTON_W, BUTTON_H)) {
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                this.minecraft.gameMode.handleInventoryButtonClick(
                        this.menu.containerId, TarotCraftMenu.BUTTON_CRAFT);
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean locked = sequenceActive();
        super.render(graphics, locked ? -10_000 : mouseX, locked ? -10_000 : mouseY, partialTick);
        if (locked) {
            return;
        }
        if (inside(mouseX, mouseY, this.leftPos + BUTTON_X, this.topPos + BUTTON_Y, BUTTON_W, BUTTON_H)) {
            graphics.renderTooltip(this.font, Component.translatable(
                    this.menu.previewInputQuality().isPresent()
                            ? "gui.miningdim.tarot.craft.button_ready"
                            : "gui.miningdim.tarot.craft.button_blocked"), mouseX, mouseY);
            return;
        }
        for (int i = 0; i < RESULT_X.length; i++) {
            if (inside(mouseX, mouseY, this.leftPos + RESULT_X[i], this.topPos + RESULT_Y,
                    RESULT_W, RESULT_H)) {
                graphics.renderTooltip(this.font,
                        Component.translatable("gui.miningdim.tarot.craft.result." + i), mouseX, mouseY);
                return;
            }
        }
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private boolean sequenceActive() {
        if (activeResultEffect == null) {
            return false;
        }
        float time = (this.minecraft == null || this.minecraft.level == null)
                ? 0.0F : this.minecraft.level.getGameTime();
        return time - resultEffectStart < TOTAL_SEQUENCE_TICKS;
    }

    private static int qualityColor(TarotQuality quality) {
        return switch (quality) {
            case R -> 0xFFE0E8F0;
            case SR -> 0xFF4CE2FF;
            case SSR -> 0xFFCB6EFF;
            case UR -> 0xFFFFAE34;
            case SHINY -> 0xFFFFF484;
        };
    }

    private static int withAlpha(int color, float alpha) {
        return (Mth.floor(Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24)
                | (color & 0x00FFFFFF);
    }

    private static float smoothstep(float start, float end, float value) {
        float t = Mth.clamp((value - start) / (end - start), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    private static float easeOutBack(float value) {
        float x = Mth.clamp(value, 0.0F, 1.0F) - 1.0F;
        return 1.0F + 2.70158F * x * x * x + 1.70158F * x * x;
    }
}
