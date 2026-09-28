package com.miningdim.job.tarot.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.tarot.TarotArcana;
import com.miningdim.job.tarot.TarotQuality;
import com.miningdim.job.tarot.pack.ShinyPackSelectMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

/**
 * 闪耀卡包自选界面 (TarotReader spec 第七章: 开出后自选一张 SSR)。
 *
 * 左侧 22 张卡面缩略图 (6 列 x 4 行, 末行 4 张居中), 右侧放大预览所选牌与"就选这张"按钮。点缩略图只是选中,
 * 按确认才发 clickMenuButton(cardId) —— 闪耀卡包要花 64 青辉石, 原先点一下数字格子就直接消耗, 没有卡面也
 * 没有确认。服务端 {@link ShinyPackSelectMenu#clickMenuButton} 仍是唯一裁决点, 本界面不改任何规则。
 *
 * 整张界面用 fill/描边绘制, 配色取合成台的深蓝星空 (tools/build_tarot_craft_assets.py 的 NAVY/CYAN/GOLD),
 * 不依赖底图贴图。
 */
public final class ShinyPackSelectScreen extends AbstractContainerScreen<ShinyPackSelectMenu> {

    private static final int W = 322;
    private static final int H = 206;

    private static final int COLS = 6;
    private static final int THUMB_W = 22;
    private static final int THUMB_H = 39;
    private static final int CELL_W = 26;
    private static final int CELL_H = 43;
    private static final int GRID_X = 10;
    private static final int GRID_Y = 24;

    private static final int PREVIEW_PANE_X = 176;
    private static final int PREVIEW_PANE_W = 136;
    private static final int PREVIEW_W = 60;
    private static final int PREVIEW_H = 106;

    private static final int CARD_TEXTURE_WIDTH = 184;
    private static final int CARD_TEXTURE_HEIGHT = 326;

    private static final int NAVY = 0xF20D1932;
    private static final int NAVY_2 = 0xFF142A4B;
    private static final int NAVY_3 = 0xFF1D3D65;
    private static final int CYAN = 0xFF8BEBFF;
    private static final int BLUE = 0xFF3EA0DE;
    private static final int WHITE = 0xFFEAF8FF;
    private static final int MUTED = 0xFF8EB5CA;

    private int selected = -1;
    private boolean sent;
    private Button confirmButton;

    public ShinyPackSelectScreen(ShinyPackSelectMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = W;
        this.imageHeight = H;
    }

    @Override
    protected void init() {
        super.init();
        int buttonX = this.leftPos + PREVIEW_PANE_X + 8;
        this.confirmButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.miningdim.tarot.shiny_select.confirm"), b -> confirm())
                .bounds(buttonX, this.topPos + H - 30, PREVIEW_PANE_W - 16, 20).build());
        updateButton();
    }

    private void updateButton() {
        if (this.confirmButton != null) {
            this.confirmButton.active = this.selected >= 0 && !this.sent;
        }
    }

    private void confirm() {
        if (this.selected < 0 || this.sent || this.minecraft == null || this.minecraft.gameMode == null) {
            return;
        }
        this.sent = true; // 服务端也有一次性保护; 这里防连点重复发包。
        updateButton();
        this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, this.selected);
    }

    private void select(int cardId) {
        if (this.sent || cardId < 0 || cardId >= TarotArcana.COUNT || cardId == this.selected) {
            return;
        }
        this.selected = cardId;
        updateButton();
        if (this.minecraft != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.4F, 0.35F));
        }
    }

    // ---- layout ----

    /** 第 cardId 张缩略图左上角 (相对界面左上角); 末行 4 张居中。 */
    private static int thumbX(int cardId) {
        int row = cardId / COLS;
        int col = cardId % COLS;
        int rowCount = Math.min(COLS, TarotArcana.COUNT - row * COLS);
        int offset = (COLS - rowCount) * CELL_W / 2;
        return GRID_X + offset + col * CELL_W + (CELL_W - THUMB_W) / 2;
    }

    private static int thumbY(int cardId) {
        return GRID_Y + (cardId / COLS) * CELL_H + (CELL_H - THUMB_H) / 2;
    }

    private int cardAt(double mouseX, double mouseY) {
        for (int id = 0; id < TarotArcana.COUNT; id++) {
            int x = this.leftPos + thumbX(id);
            int y = this.topPos + thumbY(id);
            if (mouseX >= x && mouseX < x + THUMB_W && mouseY >= y && mouseY < y + THUMB_H) {
                return id;
            }
        }
        return -1;
    }

    // ---- rendering ----

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        float time = this.minecraft == null || this.minecraft.level == null
                ? 0.0F : this.minecraft.level.getGameTime() + partialTick;

        // 外框: 深蓝底 + 蓝色细边 + 四角亮点, 与合成台同一套视觉语言。
        graphics.fill(x, y, x + W, y + H, NAVY);
        graphics.renderOutline(x, y, W, H, BLUE);
        graphics.renderOutline(x + 2, y + 2, W - 4, H - 4, NAVY_3);
        for (int[] corner : new int[][]{{3, 3}, {W - 5, 3}, {3, H - 5}, {W - 5, H - 5}}) {
            graphics.fill(x + corner[0], y + corner[1], x + corner[0] + 2, y + corner[1] + 2, CYAN);
        }
        renderStars(graphics, x, y, time);

        // 左侧牌格底板与右侧预览区底板。
        graphics.fill(x + GRID_X - 3, y + GRID_Y - 3, x + GRID_X + COLS * CELL_W + 3, y + GRID_Y + 4 * CELL_H + 3,
                0x80142A4B);
        graphics.fill(x + PREVIEW_PANE_X, y + GRID_Y - 3, x + PREVIEW_PANE_X + PREVIEW_PANE_W, y + H - 8, NAVY_2);
        graphics.renderOutline(x + PREVIEW_PANE_X, y + GRID_Y - 3, PREVIEW_PANE_W, H - 8 - (GRID_Y - 3), NAVY_3);

        int hovered = this.sent ? -1 : cardAt(mouseX, mouseY);
        for (int id = 0; id < TarotArcana.COUNT; id++) {
            renderThumb(graphics, id, id == hovered, time);
        }
        renderPreview(graphics, x, y, time);
    }

    private void renderStars(GuiGraphics graphics, int x, int y, float time) {
        for (int i = 0; i < 26; i++) {
            int sx = x + 4 + Math.floorMod(i * 97 + 13, W - 8);
            int sy = y + 4 + Math.floorMod(i * 61 + i * i * 5, H - 8);
            int alpha = 50 + (int) (60.0F * (0.5F + 0.5F * Mth.sin(time * 0.07F + i * 1.7F)));
            graphics.fill(sx, sy, sx + 1, sy + 1, (alpha << 24) | 0xBDEFFF);
        }
    }

    private void renderThumb(GuiGraphics graphics, int cardId, boolean hovered, float time) {
        int x = this.leftPos + thumbX(cardId);
        int y = this.topPos + thumbY(cardId) - (hovered ? 1 : 0);
        boolean isSelected = cardId == this.selected;
        int ssr = TarotQuality.SSR.argb();
        if (isSelected) {
            int glow = 0x60000000 | (TarotQuality.SSR.rgb());
            graphics.fill(x - 3, y - 3, x + THUMB_W + 3, y + THUMB_H + 3, glow);
        }
        graphics.fill(x - 1, y - 1, x + THUMB_W + 1, y + THUMB_H + 1,
                isSelected ? ssr : hovered ? WHITE : 0xFF2F4F78);
        blitCard(graphics, cardId, x, y, THUMB_W, THUMB_H);
        if (this.sent && !isSelected) {
            graphics.fill(x, y, x + THUMB_W, y + THUMB_H, 0xA0060C18);
        }
    }

    private void renderPreview(GuiGraphics graphics, int x, int y, float time) {
        int centerX = x + PREVIEW_PANE_X + PREVIEW_PANE_W / 2;
        if (this.selected < 0) {
            graphics.drawCenteredString(this.font, Component.translatable("gui.miningdim.tarot.shiny_select.pick"),
                    centerX, y + 84, MUTED);
            graphics.drawCenteredString(this.font, Component.translatable("gui.miningdim.tarot.shiny_select.random"),
                    centerX, y + 98, 0xFF6F8BA3);
            return;
        }
        int cardX = centerX - PREVIEW_W / 2;
        int cardY = y + GRID_Y + 4;
        int ssr = TarotQuality.SSR.argb();
        float pulse = 0.5F + 0.5F * Mth.sin(time * 0.12F);
        int glowAlpha = 40 + (int) (50.0F * pulse);
        graphics.fill(cardX - 4, cardY - 4, cardX + PREVIEW_W + 4, cardY + PREVIEW_H + 4,
                (glowAlpha << 24) | TarotQuality.SSR.rgb());
        graphics.fill(cardX - 2, cardY - 2, cardX + PREVIEW_W + 2, cardY + PREVIEW_H + 2, ssr);
        blitCard(graphics, this.selected, cardX, cardY, PREVIEW_W, PREVIEW_H);

        int textY = cardY + PREVIEW_H + 8;
        graphics.drawCenteredString(this.font, arcanaName(this.selected), centerX, textY, WHITE);
        graphics.drawCenteredString(this.font, Component.translatable("gui.miningdim.tarot.shiny_select.quality_line",
                        Component.translatable("tooltip.miningdim.tarot.quality." + TarotQuality.SSR.id())),
                centerX, textY + 12, ssr);
    }

    private static void blitCard(GuiGraphics graphics, int cardId, int x, int y, int width, int height) {
        String id = cardId < 10 ? "0" + cardId : Integer.toString(cardId);
        ResourceLocation texture = new ResourceLocation(MiningConstants.MODID, "textures/gui/tarot/cards/" + id + ".png");
        graphics.blit(texture, x, y, width, height, 0.0F, 0.0F,
                CARD_TEXTURE_WIDTH, CARD_TEXTURE_HEIGHT, CARD_TEXTURE_WIDTH, CARD_TEXTURE_HEIGHT);
    }

    private static Component arcanaName(int cardId) {
        return Component.translatable("tooltip.miningdim.tarot.arcana." + TarotArcana.byId(cardId).id());
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 只画标题 (无背包, 不画"物品栏"字样)。
        graphics.drawCenteredString(this.font, this.title, W / 2, 9, WHITE);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        int hovered = this.sent ? -1 : cardAt(mouseX, mouseY);
        if (hovered >= 0) {
            graphics.renderTooltip(this.font, arcanaName(hovered), mouseX, mouseY);
        }
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int cardId = cardAt(mouseX, mouseY);
        if (cardId >= 0 && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            select(cardId);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int current = Math.max(0, this.selected);
        int next = switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> this.selected < 0 ? 0 : current - 1;
            case GLFW.GLFW_KEY_RIGHT -> this.selected < 0 ? 0 : current + 1;
            case GLFW.GLFW_KEY_UP -> this.selected < 0 ? 0 : current - COLS;
            case GLFW.GLFW_KEY_DOWN -> this.selected < 0 ? 0 : current + COLS;
            default -> Integer.MIN_VALUE;
        };
        if (next != Integer.MIN_VALUE) {
            select(Mth.clamp(next, 0, TarotArcana.COUNT - 1));
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && this.selected >= 0) {
            confirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
