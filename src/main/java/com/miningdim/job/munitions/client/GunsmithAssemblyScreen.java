package com.miningdim.job.munitions.client;

import com.miningdim.job.munitions.gunsmith.GunsmithAssemblyRecipe;
import com.miningdim.job.munitions.gunsmith.GunsmithBaseStats;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprint;
import com.miningdim.job.munitions.gunsmith.GunsmithGunDurability;
import com.miningdim.job.munitions.gunsmith.GunsmithGunStats;
import com.miningdim.job.munitions.gunsmith.GunsmithPartItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.job.munitions.gunsmith.GunsmithTaczBridge;
import com.miningdim.job.munitions.menu.GunsmithAssemblyMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class GunsmithAssemblyScreen extends AbstractContainerScreen<GunsmithAssemblyMenu> {

    private static final int W = 420;
    private static final int H = 240;
    private static final int PREVIEW_X = 74;
    private static final int PREVIEW_Y = 72;
    private static final int PREVIEW_W = 192;
    private static final int PREVIEW_H = 64;
    private static final int PREVIEW_TEX_W = 384;
    private static final int PREVIEW_TEX_H = 128;
    private static final int STATS_X = 305;
    private static final int STATS_Y = 45;
    private static final int STATS_W = 97;
    private static final int STATS_H = 124;
    private static final int STAT_LINE_HEIGHT = 10;
    private static final int OUTPUT_X = 366;
    private static final int OUTPUT_Y = 177;
    private static final int ASSEMBLE_X = 310;
    private static final int ASSEMBLE_Y = 205;
    private static final int ASSEMBLE_W = 94;
    private static final int ASSEMBLE_H = 20;

    public GunsmithAssemblyScreen(GunsmithAssemblyMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = W;
        this.imageHeight = H;
        this.titleLabelX = 0;
        this.titleLabelY = 0;
        this.inventoryLabelX = 0;
        this.inventoryLabelY = 0;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = this.leftPos;
        int top = this.topPos;
        drawPanel(graphics, left, top, W, H, 0xFF0B0F14, 0xFF687781);
        graphics.fill(left + 10, top + 10, left + W - 10, top + 35, 0xFF151C24);
        graphics.fill(left + 12, top + 12, left + 16, top + 33, 0xFFF0B52A);
        graphics.drawString(this.font,
                Component.translatable("screen.miningdim.gunsmith_assembly.title"),
                left + 24, top + 18, 0xFFE8EDEF, false);

        drawPanel(graphics, left + 50, top + 43, 250, 105, 0xFF10161C, 0xFF35454F);
        drawPanel(graphics, left + STATS_X, top + STATS_Y, STATS_W, STATS_H,
                0xFF12181F, 0xFF35454F);
        drawPanel(graphics, left + 122, top + 151, 174, 87, 0xFF10161C, 0xFF35454F);
        drawPanel(graphics, left + 350, top + 171, 54, 28, 0xFF10161C, 0xFF35454F);
        drawPlayerInventoryFrames(graphics, left, top);

        Map<GunsmithPressPart, ItemStack> partStacks = menu.partStacks();
        // 渲染线程没有外层兜底, 待维修枪一律经菜单的不抛入口读一次, 读不出来就退回"等待图纸"提示 (审查 2)。
        GunsmithGunDurability.Managed repairTarget = menu.repairTarget();
        boolean repairMode = repairTarget != null;
        boolean hasBlueprint = GunsmithAssemblyRecipe.isBlueprint(menu.blueprint());
        if (repairTarget != null) {
            GunsmithGunStats gunStats = repairTarget.stats();
            Optional<GunsmithTaczClientData> clientData = GunsmithTaczClientData.find(gunStats.gunId());
            if (clientData.isPresent()) {
                drawRightAlignedScaledText(graphics, clientData.get().gunName().getString(),
                        left + W - 20, top + 19, 0xFFB9D7DE, 0.62F);
                graphics.blit(clientData.get().hudTexture(), left + PREVIEW_X, top + PREVIEW_Y,
                        PREVIEW_W, PREVIEW_H, 0.0F, 0.0F, PREVIEW_TEX_W, PREVIEW_TEX_H,
                        PREVIEW_TEX_W, PREVIEW_TEX_H);
            } else {
                drawRightAlignedScaledText(graphics, gunStats.gunId().toString(),
                        left + W - 20, top + 19, 0xFFE0525C, 0.62F);
            }
            renderRepairStats(graphics, left, top, GunsmithGunDurability.repairPreview(repairTarget));
        } else if (hasBlueprint) {
            GunsmithBlueprint blueprint = GunsmithAssemblyRecipe.blueprint(menu.blueprint());
            Map<GunsmithPressPart, ItemStack> previewParts =
                    GunsmithAssemblyRecipe.previewCompatibleParts(blueprint, partStacks);
            ResourceLocation previewGunId = GunsmithAssemblyRecipe.assembledGunId(menu.blueprint(), previewParts);
            Optional<GunsmithBaseStats> baseStats = GunsmithTaczBridge.findBaseStats(previewGunId);
            Optional<GunsmithTaczClientData> clientData = GunsmithTaczClientData.find(previewGunId);
            if (baseStats.isPresent() && clientData.isPresent()) {
                drawRightAlignedScaledText(graphics, clientData.get().gunName().getString(),
                        left + W - 20, top + 19, 0xFFB9D7DE, 0.62F);
                graphics.blit(clientData.get().hudTexture(), left + PREVIEW_X, top + PREVIEW_Y,
                        PREVIEW_W, PREVIEW_H, 0.0F, 0.0F, PREVIEW_TEX_W, PREVIEW_TEX_H,
                        PREVIEW_TEX_W, PREVIEW_TEX_H);
                renderStats(graphics, left, top,
                        GunsmithAssemblyRecipe.preview(blueprint, previewParts, baseStats.get()), baseStats.get());
            } else {
                drawRightAlignedScaledText(graphics, previewGunId.toString(),
                        left + W - 20, top + 19, 0xFFE0525C, 0.62F);
                graphics.drawCenteredString(this.font,
                        Component.translatable("screen.miningdim.gunsmith_assembly.tacz_data_unavailable",
                                previewGunId.toString()),
                        left + 175, top + 93, 0xFFE0525C);
            }
        } else {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.miningdim.gunsmith_assembly.awaiting_blueprint"),
                    left + 175, top + 93, 0xFF82919B);
        }

        drawPartConnections(graphics, left, top, menu);
        drawSlotFrame(graphics, left + 24, top + 94, 0xFF53636D);
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            if (!menu.isPartSlotVisible(part)) {
                continue;
            }
            ItemStack stack = partStacks.get(part);
            drawSlotFrame(graphics, left + GunsmithAssemblyMenu.partSlotX(part),
                    top + GunsmithAssemblyMenu.partSlotY(part), qualityColor(stack));
            drawPartLabel(graphics, left, top, part);
        }
        drawSlotFrame(graphics, left + OUTPUT_X, top + OUTPUT_Y, 0xFFA66CE0);

        drawScaledText(graphics, Component.translatable(repairMode
                        ? "screen.miningdim.gunsmith_assembly.repair_gun"
                        : "screen.miningdim.gunsmith_assembly.blueprint").getString(),
                left + 18, top + 82, 0xFFB7C2C8, 0.62F);
        drawScaledText(graphics, Component.translatable("screen.miningdim.gunsmith_assembly.inventory").getString(),
                left + 126, top + 153, 0xFF9DAAB2, 0.58F);
        drawScaledText(graphics, Component.translatable("screen.miningdim.gunsmith_assembly.output").getString(),
                left + 351, top + 161, 0xFFB7C2C8, 0.62F);

        boolean ready = menu.canAssemble();
        boolean assembling = menu.isAnimating();
        int buttonBorder = assembling ? 0xFF865649 : ready ? 0xFFFFD75A : 0xFF515B61;
        int buttonFill = assembling ? 0xFF3A2927 : ready ? 0xFFE0A319 : 0xFF262D31;
        drawPanel(graphics, left + ASSEMBLE_X, top + ASSEMBLE_Y, ASSEMBLE_W, ASSEMBLE_H,
                buttonFill, buttonBorder);
        graphics.drawCenteredString(this.font,
                Component.translatable(assembling
                        ? "screen.miningdim.gunsmith_assembly.assembling"
                        : repairMode
                                ? "screen.miningdim.gunsmith_assembly.repair"
                                : "screen.miningdim.gunsmith_assembly.assemble"),
                left + ASSEMBLE_X + ASSEMBLE_W / 2, top + ASSEMBLE_Y + 6,
                ready ? 0xFF171A1C : 0xFFA8B0B4);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
        if (inRect(mouseX, mouseY, this.leftPos + ASSEMBLE_X, this.topPos + ASSEMBLE_Y,
                ASSEMBLE_W, ASSEMBLE_H)) {
            graphics.renderTooltip(this.font,
                    Component.translatable(menu.isRepairMode()
                            ? "screen.miningdim.gunsmith_assembly.repair_tooltip"
                            : "screen.miningdim.gunsmith_assembly.assemble_tooltip"),
                    mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && menu.canAssemble()
                && inRect(mouseX, mouseY, this.leftPos + ASSEMBLE_X, this.topPos + ASSEMBLE_Y,
                ASSEMBLE_W, ASSEMBLE_H)) {
            Minecraft.getInstance().gameMode.handleInventoryButtonClick(
                    menu.containerId, GunsmithAssemblyMenu.BUTTON_START_ASSEMBLY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void renderStats(GuiGraphics graphics, int left, int top, GunsmithAssemblyRecipe.Preview preview,
                             GunsmithBaseStats baseStats) {
        int x = left + STATS_X + 6;
        int y = top + STATS_Y + 6;
        drawScaledText(graphics, Component.translatable("screen.miningdim.gunsmith_assembly.stats").getString(),
                x, y, 0xFFB9D7DE, 0.68F);
        // 预览行数由 8 涨到 11 (后坐拆成水平与额外垂直, 再补弹速与穿甲), 而属性面板下沿被成品槽面板
        // (top + 171) 卡死不能加高, 故把行距从 13 压到 STAT_LINE_HEIGHT: 末行底边落在 top + 166.6,
        // 面板内沿在 top + 168, 尚余一像素余量。
        int line = y + 11;
        drawStat(graphics, x, line, "screen.miningdim.gunsmith_assembly.stat.damage",
                formatTwo(baseStats.damage()) + " > " + formatTwo(preview.damage()));
        line += STAT_LINE_HEIGHT;
        drawStat(graphics, x, line, "screen.miningdim.gunsmith_assembly.stat.headshot",
                formatTwo(baseStats.headshot()) + " > " + formatTwo(preview.headshot()));
        line += STAT_LINE_HEIGHT;
        drawStat(graphics, x, line, "screen.miningdim.gunsmith_assembly.stat.range",
                formatRange(baseStats.effectiveRange()) + " > " + formatRange(preview.effectiveRange()));
        line += STAT_LINE_HEIGHT;
        drawStat(graphics, x, line, "screen.miningdim.gunsmith_assembly.stat.semi_auto_fire_rate",
                formatSignedPercent(preview.fireRateChange()));
        line += STAT_LINE_HEIGHT;
        // recoilChange 是 yaw 轴 (水平) 的乘子变化, 同时也是 pitch 轴的基底; verticalRecoilChange 是型号
        // 在此基底之上额外叠的垂直分量。原先把 recoilChange 挂在 vertical_recoil 键下是纯粹的错标。
        drawStat(graphics, x, line, "screen.miningdim.gunsmith_assembly.stat.recoil",
                formatSignedPercent(preview.recoilChange()));
        line += STAT_LINE_HEIGHT;
        drawStat(graphics, x, line, "screen.miningdim.gunsmith_assembly.stat.vertical_recoil_extra",
                formatSignedPercent(preview.verticalRecoilChange()));
        line += STAT_LINE_HEIGHT;
        drawStat(graphics, x, line, "screen.miningdim.gunsmith_assembly.stat.spread",
                formatSignedPercent(preview.spreadChange()));
        line += STAT_LINE_HEIGHT;
        drawStat(graphics, x, line, "screen.miningdim.gunsmith_assembly.stat.ammo_speed",
                formatSignedPercent(preview.ammoSpeedChange()));
        line += STAT_LINE_HEIGHT;
        drawStat(graphics, x, line, "screen.miningdim.gunsmith_assembly.stat.armor_ignore",
                formatSignedPercent(preview.armorIgnoreChange()));
        line += STAT_LINE_HEIGHT;
        drawStat(graphics, x, line, "screen.miningdim.gunsmith_assembly.stat.ads",
                formatSeconds(baseStats.adsTime()) + " > " + formatSeconds(preview.adsTime()));
        line += STAT_LINE_HEIGHT;
        drawStat(graphics, x, line, "screen.miningdim.gunsmith_assembly.stat.overall",
                "x" + String.format(Locale.ROOT, "%.3f", preview.average()));
    }

    private void renderRepairStats(GuiGraphics graphics, int left, int top,
                                   GunsmithGunDurability.RepairPreview preview) {
        int x = left + STATS_X + 6;
        int y = top + STATS_Y + 6;
        GunsmithGunDurability.State state = preview.before();
        drawScaledText(graphics, Component.translatable(
                        "screen.miningdim.gunsmith_assembly.repair_stats").getString(),
                x, y, 0xFFB9D7DE, 0.68F);
        drawStat(graphics, x, y + 17, "screen.miningdim.gunsmith_assembly.stat.durability",
                state.current() + " / " + state.maximum());
        drawStat(graphics, x, y + 33, "screen.miningdim.gunsmith_assembly.stat.repaired",
                Integer.toString(state.repairs()));
        drawStat(graphics, x, y + 49, "screen.miningdim.gunsmith_assembly.stat.next_maximum",
                preview.available() ? Integer.toString(preview.nextMaximum()) : "-");
        drawStat(graphics, x, y + 65, "screen.miningdim.gunsmith_assembly.stat.permanent_loss",
                preview.available() ? Integer.toString(state.maximum() - preview.nextMaximum()) : "-");
        drawScaledText(graphics, Component.translatable(switch (preview.status()) {
                    case AVAILABLE -> "screen.miningdim.gunsmith_assembly.repair_ready";
                    case FULL -> "screen.miningdim.gunsmith_assembly.repair_full";
                    case INSUFFICIENT_WEAR -> "screen.miningdim.gunsmith_assembly.repair_wait";
                    case EXHAUSTED -> "screen.miningdim.gunsmith_assembly.repair_exhausted";
                }).getString(), x, y + 88,
                preview.available() ? 0xFF54C879 : 0xFFE0525C, 0.58F);
    }

    private void drawStat(GuiGraphics graphics, int x, int y, String key, String value) {
        drawScaledText(graphics, Component.translatable(key, value).getString(), x, y,
                0xFFDFE8EF, 0.58F);
    }

    private void drawPartLabel(GuiGraphics graphics, int left, int top, GunsmithPressPart part) {
        String label = Component.translatable(part.labelKey()).getString();
        int slotX = GunsmithAssemblyMenu.partSlotX(part);
        int slotY = GunsmithAssemblyMenu.partSlotY(part);
        int y = switch (part) {
            case BARREL, CORE, BOLT, FIRING_PIN -> top + slotY - 10;
            case STOCK, HANDGUARD, GRIP, TRIGGER, BIPOD -> top + slotY - 9;
            case SLIDE, HAMMER, RECEIVER -> top + slotY - 10;
        };
        if (part == GunsmithPressPart.HANDGUARD) {
            drawRightAlignedScaledText(graphics, label, left + slotX + 20, y,
                    0xFFABB8BE, 0.52F);
        } else {
            drawScaledText(graphics, label, left + slotX - 2, y, 0xFFABB8BE, 0.52F);
        }
    }

    private static void drawPartConnections(GuiGraphics graphics, int left, int top,
                                            GunsmithAssemblyMenu menu) {
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            if (!menu.isPartSlotVisible(part)) {
                continue;
            }
            int slotCenterX = left + GunsmithAssemblyMenu.partSlotX(part) + 9;
            int slotCenterY = top + GunsmithAssemblyMenu.partSlotY(part) + 9;
            int anchorX = left + anchorX(part);
            int anchorY = top + anchorY(part);
            int color = 0x885BAFBA;
            int minX = Math.min(slotCenterX, anchorX);
            int maxX = Math.max(slotCenterX, anchorX);
            int minY = Math.min(slotCenterY, anchorY);
            int maxY = Math.max(slotCenterY, anchorY);
            graphics.fill(minX, slotCenterY, maxX + 1, slotCenterY + 1, color);
            graphics.fill(anchorX, minY, anchorX + 1, maxY + 1, color);
            graphics.fill(anchorX - 1, anchorY - 1, anchorX + 2, anchorY + 2, 0xFF43D3DF);
        }
    }

    private static int anchorX(GunsmithPressPart part) {
        return switch (part) {
            case BARREL -> 102;
            case HANDGUARD -> 148;
            case CORE -> 188;
            case BOLT -> 202;
            case GRIP -> 202;
            case STOCK -> 252;
            case SLIDE -> 178;
            case TRIGGER -> 190;
            case HAMMER -> 238;
            case RECEIVER -> 224;
            case BIPOD -> 176;
            case FIRING_PIN -> 210;
        };
    }

    private static int anchorY(GunsmithPressPart part) {
        return switch (part) {
            case BOLT -> 94;
            case GRIP -> 126;
            case BARREL -> 103;
            case CORE, STOCK -> 105;
            case HANDGUARD -> 107;
            case SLIDE -> 91;
            case TRIGGER -> 119;
            case HAMMER -> 95;
            case RECEIVER -> 104;
            case BIPOD -> 126;
            case FIRING_PIN -> 94;
        };
    }

    private static int qualityColor(ItemStack stack) {
        // 维修态与"等待图纸"态都不会走装配预览, 品质描边是这两条渲染路径上唯一会解析组件 NBT 的地方。
        // qualityOf 遇到损坏 NBT 会抛, 而渲染线程没有外层兜底, 抛出去就是崩客户端, 故走不抛入口,
        // 读不出来的组件按空槽的中性描边处理 (不用红色, 免得与传奇品质撞色)。
        GunsmithPartItem.PartData data = stack.isEmpty() ? null : GunsmithPartItem.tryPartData(stack);
        if (data == null) {
            return 0xFF53636D;
        }
        GunsmithPartQuality quality = data.quality();
        return switch (quality) {
            case COMMON -> 0xFFD7DEE1;
            case IMPROVED -> 0xFF54C879;
            case MILSPEC -> 0xFF5A9CE8;
            case PRECISION -> 0xFFA66CE0;
            case LEGENDARY -> 0xFFE0525C;
        };
    }

    private static void drawPlayerInventoryFrames(GuiGraphics graphics, int left, int top) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                drawSlotFrame(graphics, left + 126 + column * 18, top + 160 + row * 18, 0xFF46565F);
            }
        }
        for (int column = 0; column < 9; column++) {
            drawSlotFrame(graphics, left + 126 + column * 18, top + 218, 0xFF556771);
        }
    }

    private void drawScaledText(GuiGraphics graphics, String value, float x, float y, int color, float scale) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(this.font, value, 0, 0, color, false);
        graphics.pose().popPose();
    }

    private void drawRightAlignedScaledText(GuiGraphics graphics, String value, float rightX, float y,
                                            int color, float scale) {
        drawScaledText(graphics, value, rightX - this.font.width(value) * scale, y, color, scale);
    }

    private static String formatTwo(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String formatSeconds(double value) {
        return String.format(Locale.ROOT, "%.3fs", value);
    }

    private static String formatRange(double value) {
        return String.format(Locale.ROOT, "%.1fm", value);
    }

    private static String formatSignedPercent(double value) {
        return String.format(Locale.ROOT, "%+.1f%%", value);
    }

    private static void drawPanel(GuiGraphics graphics, int x, int y, int width, int height,
                                  int fill, int border) {
        graphics.fill(x, y, x + width, y + height, border);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
    }

    private static void drawSlotFrame(GuiGraphics graphics, int x, int y, int border) {
        drawPanel(graphics, x - 2, y - 2, 22, 22, 0xFF080C10, border);
    }

    private static boolean inRect(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
