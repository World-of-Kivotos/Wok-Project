package com.miningdim.job.tarot.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.tarot.TarotArcana;
import com.miningdim.job.tarot.TarotCardItem;
import com.miningdim.job.tarot.TarotQuality;
import com.miningdim.job.tarot.TarotRegistry;
import com.miningdim.job.tarot.TarotSounds;
import com.miningdim.job.tarot.network.TarotPackRevealS2C;
import com.miningdim.job.tarot.pack.PackKind;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Full-screen academy sci-fi tarot recruitment presentation. Results are a
 * read-only mirror of the server outcome; this screen never grants or rerolls.
 */
public final class TarotPackRevealScreen extends Screen {

    private static final int REVEAL_START_TICK = 78;
    private static final int REVEAL_INTERVAL_TICKS = 18;
    private static final int LAST_CARD_HOLD_TICKS = 28;
    private static final UUID VISUAL_OWNER = new UUID(0L, 0L);
    private static final ResourceLocation CARD_BACK = new ResourceLocation(
            MiningConstants.MODID, "textures/gui/tarot/cards/card_back.png");
    private static final int CARD_BACK_TEXTURE_WIDTH = 184;
    private static final int CARD_BACK_TEXTURE_HEIGHT = 326;

    private final TarotPackRevealS2C result;
    private final List<ItemStack> cards;
    private final ItemStack packStack;

    private int age;
    private int lastRevealSoundCount;
    private int page;
    private boolean skipped;
    private boolean completionSoundPlayed;

    private TarotPackRevealScreen(TarotPackRevealS2C result) {
        super(titleFor(result.packKind()));
        this.result = result;
        this.cards = new ArrayList<>(result.cards().size());
        for (TarotPackRevealS2C.RevealedCard card : result.cards()) {
            this.cards.add(TarotCardItem.create(
                    TarotRegistry.TAROT_CARD.get(), card.cardId(), card.quality(),
                    card.upright(), VISUAL_OWNER));
        }
        this.packStack = new ItemStack(switch (result.packKind()) {
            case COMMON -> TarotRegistry.PACK_COMMON.get();
            case ADVANCED -> TarotRegistry.PACK_ADVANCED.get();
            case SHINY -> TarotRegistry.PACK_SHINY.get();
        });
    }

    public static void open(TarotPackRevealS2C result) {
        Minecraft.getInstance().setScreen(new TarotPackRevealScreen(result));
    }

    @Override
    protected void init() {
        this.age = 0;
        this.page = 0;
        this.skipped = false;
        this.lastRevealSoundCount = 0;
        this.completionSoundPlayed = false;
    }

    @Override
    public void tick() {
        this.age++;
        if (this.age == 1) {
            play(TarotSounds.PACK_SCAN.get(), 1.0F);
        } else if (this.age == 42) {
            play(TarotSounds.PACK_OPEN.get(), 1.0F);
        }

        int visible = visibleCardCount();
        while (this.lastRevealSoundCount < visible) {
            TarotQuality quality = result.cards().get(this.lastRevealSoundCount).quality();
            play(TarotSounds.packReveal(quality), 1.0F);
            this.lastRevealSoundCount++;
        }
        if (showFinalGrid() && !this.completionSoundPlayed) {
            this.completionSoundPlayed = true;
            play(TarotSounds.PACK_COMPLETE.get(), 1.0F);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        float time = this.age + partialTick;
        renderAcademyBackdrop(graphics, time);

        if (time < REVEAL_START_TICK && !this.skipped) {
            renderPackSequence(graphics, time);
        } else if (showFinalGrid()) {
            renderFinalGrid(graphics, mouseX, mouseY, time);
        } else {
            renderSingleReveal(graphics, time);
        }

        renderFlash(graphics, time);
    }

    private void renderAcademyBackdrop(GuiGraphics graphics, float time) {
        graphics.fill(0, 0, this.width, this.height, 0xFF071326);
        graphics.fill(0, this.height / 2, this.width, this.height, 0xFF0B2340);

        int grid = Math.max(36, Math.min(64, this.width / 14));
        int drift = (int) (time * 0.35F) % grid;
        for (int x = -grid + drift; x < this.width + grid; x += grid) {
            graphics.fill(x, 0, x + 1, this.height, 0x1839BEEB);
        }
        for (int y = 0; y < this.height; y += grid) {
            graphics.fill(0, y, this.width, y + 1, 0x1239BEEB);
        }

        for (int i = 0; i < 72; i++) {
            int x = Math.floorMod(i * 137 + (int) (time * (0.4F + (i % 4) * 0.08F)),
                    Math.max(1, this.width));
            int y = Math.floorMod(i * 83 + i * i * 7, Math.max(1, this.height));
            int pulse = 80 + (int) (70.0D * (0.5D + 0.5D * Math.sin(time * 0.08D + i)));
            int size = i % 11 == 0 ? 2 : 1;
            graphics.fill(x, y, x + size, y + size, (pulse << 24) | 0xBDEFFF);
        }

        int bandX = Math.floorMod((int) (time * 2.0F), this.width + 220) - 110;
        for (int i = 0; i < 8; i++) {
            graphics.fill(bandX + i * 9, 0, bandX + i * 9 + 2, this.height, 0x102DE5FF);
        }
    }

    private void renderPackSequence(GuiGraphics graphics, float time) {
        int cx = this.width / 2;
        int cy = this.height / 2;
        float progress = Mth.clamp(time / REVEAL_START_TICK, 0.0F, 1.0F);

        drawOrbit(graphics, cx, cy, 90 + (int) (progress * 34), 50 + (int) (progress * 19),
                0xC855E7FF, time * 1.8F, 120);
        drawOrbit(graphics, cx, cy, 64 + (int) (progress * 18), 64 + (int) (progress * 18),
                0xB8FFF2B5, -time * 2.4F, 96);
        drawOrbit(graphics, cx, cy, 116, 31, 0x7678A9FF, time * 1.1F, 144);

        int beamAlpha = (int) (24 + progress * 80);
        int beamHalf = 10 + (int) (progress * 42);
        graphics.fill(cx - beamHalf, 0, cx + beamHalf, this.height,
                (beamAlpha << 24) | 0xA8F4FF);

        float floatY = (float) Math.sin(time * 0.09F) * 5.0F;
        float rotation = (float) Math.sin(time * 0.035F) * 7.0F;
        renderItem(graphics, this.packStack, cx, (int) (cy - 52 + floatY), 7.5F, rotation);

        graphics.drawCenteredString(this.font, this.title, cx, 26, 0xFFF2FBFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("screen.miningdim.tarot.pack_reveal.scanning"),
                cx, 44, 0xFF81DCF7);
        graphics.drawCenteredString(this.font,
                Component.translatable("screen.miningdim.tarot.pack_reveal.skip"),
                cx, this.height - 28, 0xBFD4ECF5);
    }

    private void renderSingleReveal(GuiGraphics graphics, float time) {
        int cx = this.width / 2;
        int visible = visibleCardCount();
        if (visible <= 0) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.miningdim.tarot.pack_reveal.duplicates"),
                    cx, this.height / 2 - 8, 0xFFFFE39A);
            return;
        }

        int index = visible - 1;
        TarotPackRevealS2C.RevealedCard card = this.result.cards().get(index);
        int color = qualityColor(card.quality());
        int revealTick = REVEAL_START_TICK + index * REVEAL_INTERVAL_TICKS;
        float local = Mth.clamp((time - revealTick) / 12.0F, 0.0F, 1.0F);
        float eased = 1.0F - (1.0F - local) * (1.0F - local);

        drawOrbit(graphics, cx, this.height / 2 - 25, 95, 95,
                withAlpha(color, 205), time * 3.0F, 128);
        drawOrbit(graphics, cx, this.height / 2 - 25, 128, 44,
                withAlpha(color, 150), -time * 2.0F, 144);

        float scale = 3.0F + eased * 5.5F;
        int cardTop = this.height / 2 - (int) (8.0F * scale) - 30;
        float faceWidth = Math.max(0.04F, Math.abs(Mth.cos(eased * Mth.PI)));
        if (eased < 0.5F) {
            renderCardBack(graphics, cx, cardTop, scale, 0.0F, faceWidth);
        } else {
            renderItem(graphics, this.cards.get(index), cx, cardTop, scale, 0.0F, faceWidth);
        }

        int textY = this.height / 2 + 68;
        graphics.drawCenteredString(this.font, arcanaName(card.cardId()), cx, textY, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font,
                qualityAndOrientation(card), cx, textY + 15, color);
        graphics.drawCenteredString(this.font,
                Component.literal(visible + " / " + this.result.cards().size()),
                cx, textY + 34, 0xFF9DDDF2);
    }

    private void renderFinalGrid(GuiGraphics graphics, int mouseX, int mouseY, float time) {
        int cx = this.width / 2;
        graphics.drawCenteredString(this.font,
                Component.translatable("screen.miningdim.tarot.pack_reveal.result"),
                cx, 20, 0xFFF4FCFF);

        if (this.cards.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.miningdim.tarot.pack_reveal.duplicates"),
                    cx, this.height / 2 - 20, 0xFFFFDE84);
        } else {
            int cols = Math.max(1, Math.min(5, Math.max(1, (this.width - 40) / 108)));
            int rows = Math.max(1, Math.min(2, Math.max(1, (this.height - 170) / 106)));
            int pageSize = cols * rows;
            int pages = Math.max(1, (this.cards.size() + pageSize - 1) / pageSize);
            this.page = Mth.clamp(this.page, 0, pages - 1);
            int first = this.page * pageSize;
            int count = Math.min(pageSize, this.cards.size() - first);
            int usedCols = Math.min(cols, Math.max(1, count));
            int cellW = Math.min(108, (this.width - 30) / usedCols);
            int gridW = usedCols * cellW;
            int startX = cx - gridW / 2;
            int startY = 58;

            for (int i = 0; i < count; i++) {
                int global = first + i;
                int col = i % cols;
                int row = i / cols;
                int cellX = startX + col * cellW;
                int cellY = startY + row * 108;
                TarotPackRevealS2C.RevealedCard card = this.result.cards().get(global);
                int color = qualityColor(card.quality());
                boolean hovered = mouseX >= cellX && mouseX < cellX + cellW
                        && mouseY >= cellY && mouseY < cellY + 100;

                graphics.fill(cellX + 4, cellY, cellX + cellW - 4, cellY + 100,
                        withAlpha(color, hovered ? 60 : 31));
                graphics.fill(cellX + 4, cellY, cellX + cellW - 4, cellY + 1,
                        withAlpha(color, 230));
                graphics.fill(cellX + 4, cellY + 99, cellX + cellW - 4, cellY + 100,
                        withAlpha(color, 180));

                renderItem(graphics, this.cards.get(global), cellX + cellW / 2,
                        cellY + 4, hovered ? 4.6F : 4.2F, 0.0F);
                String cardName = this.font.plainSubstrByWidth(arcanaName(card.cardId()).getString(), cellW - 10);
                graphics.drawCenteredString(this.font, cardName,
                        cellX + cellW / 2, cellY + 72, 0xFFF6FCFF);
                graphics.drawCenteredString(this.font, qualityAndOrientation(card),
                        cellX + cellW / 2, cellY + 85, color);
            }

            if (pages > 1) {
                graphics.drawCenteredString(this.font,
                        Component.translatable("screen.miningdim.tarot.pack_reveal.page",
                                this.page + 1, pages),
                        cx, this.height - 58, 0xFF8EDCF5);
            }
        }

        int summaryY = this.height - 42;
        if (this.result.shardRefund() > 0) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.miningdim.tarot.pack_reveal.shards",
                            this.result.shardRefund()),
                    cx, summaryY - 13, 0xFFFFD97C);
        }
        if (this.result.derivedPacks() > 0) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.miningdim.tarot.pack_reveal.derived",
                            this.result.derivedPacks()),
                    cx, summaryY, 0xFF86E8FF);
        }
        if (this.result.totalCards() > this.result.cards().size()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.miningdim.tarot.pack_reveal.truncated",
                            this.result.cards().size(), this.result.totalCards()),
                    cx, summaryY + 13, 0xFFBBD2DC);
        }
        graphics.drawCenteredString(this.font,
                Component.translatable("screen.miningdim.tarot.pack_reveal.close"),
                cx, this.height - 16, 0xBFD6E8F0);
    }

    private void renderFlash(GuiGraphics graphics, float time) {
        float distance = Math.abs(time - REVEAL_START_TICK);
        if (distance < 10.0F) {
            int alpha = (int) ((1.0F - distance / 10.0F) * 230.0F);
            graphics.fill(0, 0, this.width, this.height, (alpha << 24) | 0xE9FBFF);
        }
        int visible = visibleCardCount();
        if (visible > 0 && !showFinalGrid()) {
            int revealTick = REVEAL_START_TICK + (visible - 1) * REVEAL_INTERVAL_TICKS;
            float local = time - revealTick;
            if (local >= 0.0F && local < 5.0F) {
                int alpha = (int) ((1.0F - local / 5.0F) * 145.0F);
                graphics.fill(0, 0, this.width, this.height, (alpha << 24) | 0xFFFFFF);
            }
        }
    }

    private int visibleCardCount() {
        if (this.age < REVEAL_START_TICK || this.cards.isEmpty()) {
            return 0;
        }
        return Math.min(this.cards.size(),
                1 + (this.age - REVEAL_START_TICK) / REVEAL_INTERVAL_TICKS);
    }

    private boolean showFinalGrid() {
        if (this.skipped) {
            return true;
        }
        int reveals = Math.max(1, this.cards.size());
        int finalTick = REVEAL_START_TICK
                + (reveals - 1) * REVEAL_INTERVAL_TICKS
                + LAST_CARD_HOLD_TICKS;
        return this.age >= finalTick;
    }

    private void skipToResults() {
        this.skipped = true;
        this.lastRevealSoundCount = this.cards.size();
        this.completionSoundPlayed = true;
        play(TarotSounds.PACK_COMPLETE.get(), 1.0F);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!showFinalGrid()) {
            skipToResults();
        } else {
            onClose();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (showFinalGrid() && !this.cards.isEmpty()) {
            this.page = Math.max(0, this.page + (delta < 0.0D ? 1 : -1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_LEFT && showFinalGrid()) {
            this.page = Math.max(0, this.page - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT && showFinalGrid()) {
            this.page++;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER) {
            if (!showFinalGrid()) {
                skipToResults();
            } else {
                onClose();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void play(net.minecraft.sounds.SoundEvent sound, float pitch) {
        if (this.minecraft != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch));
        }
    }

    private void renderItem(GuiGraphics graphics, ItemStack stack, int centerX, int top,
                            float scale, float rotationDegrees) {
        renderItem(graphics, stack, centerX, top, scale, rotationDegrees, 1.0F);
    }

    private void renderItem(GuiGraphics graphics, ItemStack stack, int centerX, int top,
                            float scale, float rotationDegrees, float horizontalScale) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, top + 8.0F * scale, 220.0F);
        pose.mulPose(Axis.ZP.rotationDegrees(rotationDegrees));
        pose.scale(scale * horizontalScale, scale, 1.0F);
        pose.translate(-8.0F, -8.0F, 0.0F);
        graphics.renderItem(stack, 0, 0);
        pose.popPose();
    }

    private void renderCardBack(GuiGraphics graphics, int centerX, int top,
                                float scale, float rotationDegrees, float horizontalScale) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, top + 8.0F * scale, 220.0F);
        pose.mulPose(Axis.ZP.rotationDegrees(rotationDegrees));
        pose.scale(scale * horizontalScale * 0.5F, scale * 0.5F, 1.0F);
        graphics.blit(CARD_BACK, -9, -16, 18, 32,
                0.0F, 0.0F, CARD_BACK_TEXTURE_WIDTH, CARD_BACK_TEXTURE_HEIGHT,
                CARD_BACK_TEXTURE_WIDTH, CARD_BACK_TEXTURE_HEIGHT);
        pose.popPose();
    }

    private static void drawOrbit(GuiGraphics graphics, int cx, int cy, int rx, int ry,
                                  int color, float rotationDegrees, int points) {
        double rotation = Math.toRadians(rotationDegrees);
        for (int i = 0; i < points; i++) {
            if ((i / 7) % 3 == 2) {
                continue;
            }
            double angle = Math.PI * 2.0D * i / points + rotation;
            int x = cx + (int) Math.round(Math.cos(angle) * rx);
            int y = cy + (int) Math.round(Math.sin(angle) * ry);
            graphics.fill(x, y, x + 2, y + 2, color);
        }
    }

    private static Component titleFor(PackKind kind) {
        return Component.translatable("screen.miningdim.tarot.pack_reveal.title." + kind.id());
    }

    private static Component arcanaName(int cardId) {
        return Component.translatable("tooltip.miningdim.tarot.arcana."
                + TarotArcana.byId(cardId).id());
    }

    private static Component qualityAndOrientation(TarotPackRevealS2C.RevealedCard card) {
        return Component.translatable("tooltip.miningdim.tarot.quality." + card.quality().id())
                .append(Component.literal(" · "))
                .append(Component.translatable(card.upright()
                        ? "tooltip.miningdim.tarot.orientation.upright"
                        : "tooltip.miningdim.tarot.orientation.reversed"));
    }

    private static int qualityColor(TarotQuality quality) {
        return switch (quality) {
            case R -> 0xFFF0F7FF;
            case SR -> 0xFF347EFF;
            case SSR -> 0xFFA64FFF;
            case UR -> 0xFFFF69B8;
            case SHINY -> 0xFFFF313E;
        };
    }

    private static int withAlpha(int color, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }
}
