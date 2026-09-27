package com.miningdim.job.munitions.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.MunitionsConfig;
import com.miningdim.job.munitions.MunitionsLevels;
import com.miningdim.job.munitions.block.GunsmithPressBlockEntity;
import com.miningdim.job.munitions.client.style.GsPainter;
import com.miningdim.job.munitions.client.style.GunsmithStyledScreen;
import com.miningdim.job.munitions.client.style.GunsmithTheme;
import com.miningdim.job.munitions.client.style.GunsmithTheme.BarKind;
import com.miningdim.job.munitions.client.style.GunsmithTheme.ButtonKind;
import com.miningdim.job.munitions.client.style.GunsmithTheme.LampState;
import com.miningdim.job.munitions.client.style.GunsmithTheme.State;
import com.miningdim.job.munitions.client.style.GunsmithUi;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprint;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPartRarity;
import com.miningdim.job.munitions.gunsmith.GunsmithPartVariant;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.job.munitions.menu.GunsmithPressMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.miningdim.job.munitions.client.style.GsPainter.BOLD;
import static com.miningdim.job.munitions.client.style.GsPainter.CENTER;
import static com.miningdim.job.munitions.client.style.GsPainter.RIGHT;

/**
 * 机械冲压机界面 (360x240), 逐项对应设计预览 drawPress。三种风格 (学园终端 / 产线工控 / 蓝图工程) 共用这一套布局,
 * 只是交给 {@link GunsmithTheme} 用不同画法画; 风格由玩家在标题栏右侧的小按钮里自己选 (基类处理)。
 *
 * <p>布局 (GUI 像素):
 * <ul>
 *   <li>左 (8,30,84,112) "平台": 9 个平台一行一个, 没有任何图纸的平台显示"无图纸"、不可点;</li>
 *   <li>中上 (99,30) 部件卡: 按平台 supportedParts() 的顺序居中排开, 点击发 BUTTON_PART_BASE + 行号 (与服务端遍历顺序一致);</li>
 *   <li>中 (99,60,91,59) 液压机工位, 成品槽在 (136,95); (192,60,71,59) "组件型号"; (99,122) 五档品质;</li>
 *   <li>右 (270,30,82,112) "原料": 三个料槽竖排 + 工费 / 耗时;</li>
 *   <li>左下 (8,146,84,86) "成品预估" + 开始按钮; 右下 (270,146,82,86) "进度"; 中下是玩家背包 (100,148)。</li>
 * </ul>
 *
 * <p>所有判定 (解锁等级、工费、料量) 与服务端读同一份配置 ({@link MunitionsConfig} / {@link MunitionsLevels},
 * SERVER 配置会同步到客户端), 界面只做提前提示, 服务端 {@code GunsmithPressBlockEntity} 仍是唯一权威。
 */
public final class GunsmithPressScreen extends GunsmithStyledScreen<GunsmithPressMenu> {

    private static final ResourceLocation BG =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/container/gunsmith_press.png");

    private static final String K = "screen.miningdim.gunsmith_press.";
    private static final String MATERIAL_KEY_GUN_PARTS = K + "material.gun_parts";
    private static final String MATERIAL_KEY_ALLOY = K + "material.alloy";
    private static final String MATERIAL_KEY_POLYMER = K + "material.polymer";

    /** 零件贴图 textures/item/gunsmith_part_*.png 是 64x64。 */
    private static final int PART_ICON_TEX = 64;

    // ------------------------------------------------------------------ layout (GUI px)

    private static final int PLAT_PANEL_X = 8;
    private static final int PLAT_PANEL_Y = 30;
    private static final int PLAT_PANEL_W = 84;
    private static final int PLAT_PANEL_H = 112;
    private static final int PLAT_X = 12;
    private static final int PLAT_Y = 42;
    private static final int PLAT_W = 76;
    private static final int PLAT_H = 10;
    private static final int PLAT_STEP = 11;

    private static final int PART_AREA_X = 99;
    private static final int PART_AREA_W = 164;
    private static final int PART_Y = 30;
    private static final int PART_W = 26;
    private static final int PART_H = 27;
    private static final int PART_GAP = 1;

    private static final int STAGE_X = 99;
    private static final int STAGE_Y = 60;
    private static final int STAGE_W = 91;
    private static final int STAGE_H = 59;
    /** 冲头一个往复的时长 (毫秒): 前 25% 下压, 停 10%, 后 65% 回升。 */
    private static final long RAM_CYCLE_MS = 900L;

    private static final int VAR_PANEL_X = 192;
    private static final int VAR_PANEL_Y = 60;
    private static final int VAR_PANEL_W = 71;
    private static final int VAR_PANEL_H = 59;
    private static final int VAR_X = 195;
    private static final int VAR_Y = 73;
    private static final int VAR_W = 65;
    private static final int VAR_H = 13;
    private static final int VAR_STEP = 15;

    private static final int QUAL_X = 99;
    private static final int QUAL_Y = 122;
    private static final int QUAL_W = 32;
    private static final int QUAL_H = 20;
    private static final int QUAL_STEP = 33;

    private static final int MAT_PANEL_X = 270;
    private static final int MAT_PANEL_Y = 30;
    private static final int MAT_PANEL_W = 82;
    private static final int MAT_PANEL_H = 112;
    private static final int MAT_TEXT_X = 296;
    private static final int MAT_TEXT_R = 346;
    private static final int FEE_X = 270;
    private static final int FEE_Y = 118;
    private static final int FEE_W = 82;
    private static final int FEE_H = 22;

    private static final int PROD_PANEL_X = 8;
    private static final int PROD_PANEL_Y = 146;
    private static final int PROD_PANEL_W = 84;
    private static final int PROD_PANEL_H = 86;
    private static final int PROD_NAME_X = 14;
    private static final int PROD_NAME_Y = 160;
    private static final int PROD_NAME_W = 72;
    private static final int START_X = 14;
    private static final int START_Y = 204;
    private static final int START_W = 72;
    private static final int START_H = 22;

    private static final int PROG_PANEL_X = 270;
    private static final int PROG_PANEL_Y = 146;
    private static final int PROG_PANEL_W = 82;
    private static final int PROG_PANEL_H = 86;

    /** 三个料槽 (方块实体槽位下标 = 菜单槽位下标) 与各自只认的材料。 */
    private static final int[] INPUT_SLOTS = {
            GunsmithPressBlockEntity.SLOT_GUN_PARTS,
            GunsmithPressBlockEntity.SLOT_ALLOY,
            GunsmithPressBlockEntity.SLOT_POLYMER};
    private static final String[] INPUT_LABEL_KEYS = {MATERIAL_KEY_GUN_PARTS, MATERIAL_KEY_ALLOY, MATERIAL_KEY_POLYMER};

    // 与 GunsmithPressBlockEntity.requiredMaterial 同一张表 (那边是包内方法, 界面拿不到); 换专用材料时两处一起改。
    private static Item inputItem(int input) {
        return switch (input) {
            case 0 -> Items.IRON_INGOT;
            case 1 -> Items.COPPER_INGOT;
            default -> Items.SLIME_BLOCK;
        };
    }

    /** 至少有一张图纸的平台才开放冲压 (预览 PLATS[].bp)。 */
    private static final Set<GunsmithPlatform> PLATFORMS_WITH_BLUEPRINT = platformsWithBlueprint();

    /** 每个平台的部件顺序 (= supportedParts() 的迭代顺序, 服务端 trySelectPart 按同一顺序数行号)。 */
    private static final Map<GunsmithPlatform, List<GunsmithPressPart>> PARTS_BY_PLATFORM = partsByPlatform();

    static {
        // 客户端 Screen 进不了 GameTest (GameTest 跑在专用服务端), 所以"会在 super.mouseClicked 之前吞掉左键的控件
        // 不压任何槽位判定盒"做成类加载期自检: 坐标全是常量, 谁改出相交, 客户端一打开界面就炸出来,
        // 而不是等玩家发现背包格点不动 (审查 32 的同类问题)。
        assertClickTargetsClearOfSlots();
    }

    /** 本帧哪些平台行的名字被截断了 (截断的才补一条显示全名的提示)。 */
    private final boolean[] platformNameCut = new boolean[GunsmithPlatform.values().length];
    /** 本帧成品名是否被截断。 */
    private boolean productNameCut;

    public GunsmithPressScreen(GunsmithPressMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, BG);
    }

    // ================================================================== header

    @Override
    protected GunsmithTheme.Header header() {
        GunsmithPlatform platform = menu.selectedPlatform();
        GunsmithPressPart part = menu.selectedPart();
        String code = "WOK-GS-" + platform.id().toUpperCase(Locale.ROOT) + "-" + partCode(part);
        return new GunsmithTheme.Header(
                Component.translatable(K + "header.title"),
                Component.translatable(K + "header.en"),
                null,
                Component.translatable(K + "subtitle"),
                code,
                menu.isPressing(),
                ownerLevel());
    }

    /** 图号里的部件代号: 取 shortLabel 的最后一个词 ("FIRING PIN" -> "PIN", 与预览一致)。 */
    private static String partCode(GunsmithPressPart part) {
        String label = part.shortLabel();
        return label.substring(label.lastIndexOf(' ') + 1);
    }

    // ================================================================== main layer

    @Override
    protected void renderScreen(GsPainter p, GunsmithTheme theme) {
        int level = ownerLevel();
        GunsmithPlatform platform = menu.selectedPlatform();
        GunsmithPressPart part = menu.selectedPart();
        GunsmithPartQuality quality = menu.selectedQuality();
        GunsmithPartVariant variant = menu.selectedVariant();
        boolean pressing = menu.isPressing();
        boolean hasOutput = hasOutput();
        float prog = pressing ? pressFraction() : hasOutput ? 1.0F : 0.0F;

        renderPlatforms(p, theme, platform);
        renderParts(p, theme, platform, part, variant, quality);
        renderStage(p, theme, platform, part, variant, quality, pressing, hasOutput, prog);
        renderVariants(p, theme, platform, part, variant, level);
        renderQualities(p, theme, quality, level);
        renderMaterials(p, theme, part, quality, variant);
        renderProduct(p, theme, platform, part, quality, variant, pressing, level);
        renderProgress(p, theme, quality, pressing, hasOutput, prog);
        drawPlayerInventorySlots(p, theme);
    }

    private void renderPlatforms(GsPainter p, GunsmithTheme t, GunsmithPlatform selected) {
        t.panel(p, PLAT_PANEL_X, PLAT_PANEL_Y, PLAT_PANEL_W, PLAT_PANEL_H, Component.translatable(K + "panel.platform"));
        GunsmithPlatform[] platforms = GunsmithPlatform.values();
        for (int i = 0; i < platforms.length; i++) {
            GunsmithPlatform platform = platforms[i];
            int x = PLAT_X;
            int y = platformRowY(i);
            boolean sel = platform == selected;
            boolean available = hasBlueprint(platform);
            State state = sel ? State.SEL
                    : !available ? State.LOCK
                    : p.hov(x, y, PLAT_W, PLAT_H) ? State.HOVER : State.IDLE;
            t.row(p, x, y, PLAT_W, PLAT_H, state);
            Component sub = available
                    ? Component.translatable(K + "platform.part_count", partsOf(platform).size())
                    : Component.translatable(K + "platform.no_blueprint");
            float subW = p.text(sub, x + PLAT_W - 5, y + 2.4F, t.rowSub(state), 0.5F, RIGHT);
            Component name = Component.translatable(platform.labelKey());
            float maxW = PLAT_W - 10 - subW - 3;
            float sc = p.fitScale(name, maxW, 0.62F, 0.4F, sel);
            Component shown = ellipsize(p, name, maxW, sc, sel);
            platformNameCut[i] = shown != name;
            p.text(shown, x + 5, y + 1.8F + (0.62F - sc) * 4.0F, t.rowText(state), sc, sel ? BOLD : 0);
        }
    }

    private void renderParts(GsPainter p, GunsmithTheme t, GunsmithPlatform platform, GunsmithPressPart selected,
                             GunsmithPartVariant variant, GunsmithPartQuality quality) {
        List<GunsmithPressPart> parts = partsOf(platform);
        int n = parts.size();
        for (int i = 0; i < n; i++) {
            GunsmithPressPart part = parts.get(i);
            int x = partCardX(n, i);
            int y = PART_Y;
            boolean sel = part == selected;
            State state = sel ? State.SEL : p.hov(x, y, PART_W, PART_H) ? State.HOVER : State.IDLE;
            t.card(p, x, y, PART_W, PART_H, state);
            GunsmithPartVariant cardVariant = sel ? iconVariant(variant, platform, part) : GunsmithPartVariant.BASE;
            p.blit(partTexture(platform, part, cardVariant, quality), x + 5, y + 2, 16, 16,
                    0.0F, 0.0F, PART_ICON_TEX, PART_ICON_TEX, PART_ICON_TEX, PART_ICON_TEX);
            Component label = Component.translatable(part.slotKey());
            float sc = p.fitScale(label, PART_W - 3, 0.6F, 0.35F, false);
            p.text(label, x + 13, y + 19.5F + (0.6F - sc) * 4.0F, t.cardText(state), sc, CENTER);
        }
    }

    private void renderStage(GsPainter p, GunsmithTheme t, GunsmithPlatform platform, GunsmithPressPart part,
                             GunsmithPartVariant variant, GunsmithPartQuality quality,
                             boolean pressing, boolean hasOutput, float prog) {
        float ram = pressing ? ramStroke(p.now()) : 0.0F;
        t.stage(p, STAGE_X, STAGE_Y, STAGE_W, STAGE_H, pressing, ram);
        int sx = GunsmithPressMenu.SLOT_OUTPUT_X;
        int sy = GunsmithPressMenu.SLOT_OUTPUT_Y;
        t.slot(p, sx, sy);
        if (!hasOutput) {
            // 空工位: 待机时是选中零件的淡影, 冲压中随进度逐渐显形; 成品出来后由原版槽位画真物品。
            float alpha = pressing ? 0.15F + prog * 0.6F : 0.4F;
            p.blit(partTexture(platform, part, iconVariant(variant, platform, part), quality), sx, sy, 16, 16,
                    0.0F, 0.0F, PART_ICON_TEX, PART_ICON_TEX, PART_ICON_TEX, PART_ICON_TEX, alpha);
        }
    }

    private void renderVariants(GsPainter p, GunsmithTheme t, GunsmithPlatform platform, GunsmithPressPart part,
                                GunsmithPartVariant selected, int level) {
        GunsmithTheme.Palette c = t.palette();
        t.panel(p, VAR_PANEL_X, VAR_PANEL_Y, VAR_PANEL_W, VAR_PANEL_H, Component.translatable(K + "panel.variant"));
        List<GunsmithPartVariant> variants = GunsmithPartVariant.availableFor(platform, part);
        for (int i = 0; i < variants.size(); i++) {
            GunsmithPartVariant variant = variants.get(i);
            int x = VAR_X;
            int y = variantRowY(i);
            GunsmithPartRarity rarity = variant.rarity();
            int unlock = rarityUnlockLevel(rarity);
            boolean ok = level >= unlock;
            boolean sel = variant == selected;
            State state = sel ? State.SEL
                    : !ok ? State.LOCK
                    : p.hov(x, y, VAR_W, VAR_H) ? State.HOVER : State.IDLE;
            t.row(p, x, y, VAR_W, VAR_H, state);
            t.rarTag(p, x + 3, y + 2, rarity);
            Component label = Component.translatable(variant.labelKey());
            float maxW = ok ? VAR_W - 17 : VAR_W - 27;
            float sc = p.fitScale(label, maxW, 0.55F, 0.4F, false);
            p.text(ellipsize(p, label, maxW, sc, false), x + 13, y + (VAR_H - 8.0F * sc) / 2.0F,
                    t.rowText(state), sc, 0);
            if (!ok) {
                p.text(GunsmithUi.levelShort(unlock), x + VAR_W - 3, y + 4, c.bad(), 0.5F, RIGHT);
            }
        }
        if (variants.size() == 1) {
            Component line1 = Component.translatable(K + "variant.only_one");
            Component line2 = Component.translatable(GunsmithPartVariant.BASE.labelKey());
            p.text(line1, 197, 94, c.dim(), p.fitScale(line1, 62.0F, 0.52F, 0.35F, false), 0);
            p.text(line2, 197, 102, c.dim(), p.fitScale(line2, 62.0F, 0.52F, 0.35F, false), 0);
        }
    }

    private void renderQualities(GsPainter p, GunsmithTheme t, GunsmithPartQuality selected, int level) {
        GunsmithTheme.Palette c = t.palette();
        GunsmithPartQuality[] qualities = GunsmithPartQuality.values();
        for (int i = 0; i < qualities.length; i++) {
            GunsmithPartQuality quality = qualities[i];
            int x = qualityX(i);
            int y = QUAL_Y;
            int unlock = qualityUnlockLevel(quality);
            boolean ok = level >= unlock;
            boolean sel = quality == selected;
            State state = sel ? State.SEL
                    : !ok ? State.LOCK
                    : p.hov(x, y, QUAL_W, QUAL_H) ? State.HOVER : State.IDLE;
            t.qbtn(p, x, y, QUAL_W, QUAL_H, quality, state);
            Component name = Component.translatable(quality.labelKey());
            float sc = p.fitScale(name, QUAL_W - 3, 0.66F, 0.4F, sel);
            float cx = x + 16.0F;
            if (!ok && cx - p.textWidth(name, sc, sel) / 2.0F < x + 9) {
                // 锁图标占左上角 (x+3..x+8): 放不下时把名字挪到锁右边再缩 (中文名本来就放得下, 不会走到这里)。
                sc = p.fitScale(name, QUAL_W - 12, 0.66F, 0.4F, sel);
                cx = x + 19.0F;
            }
            p.text(name, cx, y + 3.5F + (0.66F - sc) * 4.0F, ok ? t.qColor(quality) : c.dim(), sc,
                    CENTER | (sel ? BOLD : 0));
            Component sub = ok
                    ? Component.literal("x" + quality.materialMultiplier() + " "
                    + GunsmithUi.formatTicks(quality.requiredTicks()))
                    : GunsmithUi.levelShort(unlock);
            p.text(sub, x + 16, y + 12, ok ? c.muted() : c.bad(), 0.48F, CENTER);
            if (!ok) {
                t.lock(p, x + 3, y + 3);
            }
        }
    }

    private void renderMaterials(GsPainter p, GunsmithTheme t, GunsmithPressPart part, GunsmithPartQuality quality,
                                 GunsmithPartVariant variant) {
        GunsmithTheme.Palette c = t.palette();
        t.panel(p, MAT_PANEL_X, MAT_PANEL_Y, MAT_PANEL_W, MAT_PANEL_H, Component.translatable(K + "panel.materials"));
        float textW = MAT_TEXT_R - MAT_TEXT_X;
        for (int i = 0; i < INPUT_SLOTS.length; i++) {
            int sx = inputSlotX(i);
            int sy = inputSlotY(i);
            int need = requiredMaterial(part, quality, i);
            int have = inputCount(i);
            t.slot(p, sx, sy);
            if (have <= 0) {
                p.itemFaded(new ItemStack(inputItem(i)), sx, sy, 0.3F, slotInner(t));
            }
            Component label = Component.translatable(INPUT_LABEL_KEYS[i]);
            p.text(label, MAT_TEXT_X, sy + 1, c.muted(), p.fitScale(label, textW, 0.55F, 0.35F, false), 0);
            if (need == 0) {
                Component none = Component.translatable(K + "material.not_needed");
                p.text(none, MAT_TEXT_X, sy + 9, c.dim(), p.fitScale(none, textW, 0.6F, 0.35F, false), 0);
            } else {
                p.text(have + " / " + need, MAT_TEXT_X, sy + 8.5F, have >= need ? c.text() : c.bad(), 0.66F, 0);
            }
        }
        t.sep(p, 276, 116, 70);
        long fee = pressFee(quality, variant.rarity());
        p.text(Component.translatable(K + "fee"), 276, 121, c.muted(), 0.6F, 0);
        p.text(Component.translatable(K + "fee_value", GunsmithUi.formatCount(fee)), MAT_TEXT_R, 121, c.text(),
                0.62F, RIGHT);
        p.text(Component.translatable(K + "time"), 276, 131, c.muted(), 0.6F, 0);
        p.text(GunsmithUi.formatTicks(quality.requiredTicks()), MAT_TEXT_R, 131, c.text(), 0.62F, RIGHT);
    }

    private void renderProduct(GsPainter p, GunsmithTheme t, GunsmithPlatform platform, GunsmithPressPart part,
                               GunsmithPartQuality quality, GunsmithPartVariant variant, boolean pressing, int level) {
        GunsmithTheme.Palette c = t.palette();
        t.panel(p, PROD_PANEL_X, PROD_PANEL_Y, PROD_PANEL_W, PROD_PANEL_H, Component.translatable(K + "panel.product"));
        Component name = productName(platform, part, variant);
        float ns = p.fitScale(name, PROD_NAME_W, 0.66F, 0.42F, true);
        Component shownName = ellipsize(p, name, PROD_NAME_W, ns, true);
        productNameCut = shownName != name;
        p.text(shownName, PROD_NAME_X, PROD_NAME_Y + (0.66F - ns) * 4.0F, c.text(), ns, BOLD);

        GunsmithPartRarity rarity = variant.rarity();
        t.rarTag(p, 14, 170, rarity);
        float qw = p.text(Component.translatable(quality.labelKey()), 86, 171, t.qColor(quality), 0.62F, RIGHT | BOLD);
        Component rarityName = Component.translatable(rarity.labelKey());
        float rMax = 86 - 25 - qw - 3;
        float rs = p.fitScale(rarityName, rMax, 0.55F, 0.35F, false);
        p.text(ellipsize(p, rarityName, rMax, rs, false), 25, 171 + (0.55F - rs) * 4.0F, t.rarText(rarity), rs, 0);

        p.text(Component.translatable(K + "product.coefficient"), 14, 183, c.muted(), 0.55F, 0);
        p.text(GunsmithUi.format2(quality.minCoefficient()) + "–" + GunsmithUi.format2(quality.maxCoefficient()),
                86, 183, c.text(), 0.58F, RIGHT);
        p.text(Component.translatable(K + "product.material_mult"), 14, 192, c.muted(), 0.55F, 0);
        p.text("x" + quality.materialMultiplier(), 86, 192, c.text(), 0.58F, RIGHT);

        Component why = startBlockReason(level);
        State state = why != null ? State.OFF
                : p.hov(START_X, START_Y, START_W, START_H) ? State.HOVER : State.IDLE;
        Component label = pressing
                ? Component.translatable(K + "status.pressing")
                : Component.translatable(K + "start");
        t.btn(p, START_X, START_Y, START_W, START_H, label, ButtonKind.GO, state);
    }

    private void renderProgress(GsPainter p, GunsmithTheme t, GunsmithPartQuality quality,
                                boolean pressing, boolean hasOutput, float prog) {
        GunsmithTheme.Palette c = t.palette();
        t.panel(p, PROG_PANEL_X, PROG_PANEL_Y, PROG_PANEL_W, PROG_PANEL_H, Component.translatable(K + "panel.progress"));
        t.lamp(p, 277, 161, pressing ? LampState.RUN : hasOutput ? LampState.DONE : LampState.IDLE);
        Component status = Component.translatable(K + (pressing ? "status.pressing" : hasOutput ? "status.done" : "status.idle"));
        int statusColor = pressing ? c.accent() : hasOutput ? c.good() : c.muted();
        p.text(status, 286, 160.5F, statusColor, p.fitScale(status, 60.0F, 0.66F, 0.4F, true), BOLD);
        Component label = Component.translatable(K + (pressing ? "remaining_time" : hasOutput ? "output_waiting" : "craft_time"));
        p.text(label, 276, 173, c.muted(), p.fitScale(label, 70.0F, 0.55F, 0.35F, false), 0);
        String time = hasOutput ? "0:00"
                : GunsmithUi.formatTicks(pressing ? remainingTicks() : quality.requiredTicks());
        p.text(time, 311, 182, c.text(), 1.25F, CENTER | BOLD);
        t.bar(p, 276, 198, 70, 5, prog, hasOutput ? BarKind.FE : BarKind.PROG, pressing);
        Component foot = Component.translatable(K + (hasOutput ? "footer.take_first" : "footer.output_here"));
        p.text(foot, 276, 208, c.dim(), p.fitScale(foot, 70.0F, 0.48F, 0.35F, false), 0);
    }

    // ================================================================== front layer (z 300)

    @Override
    protected void renderFront(GsPainter p, GunsmithTheme theme) {
        boolean pressing = menu.isPressing();
        theme.stageFront(p, STAGE_X, STAGE_Y, STAGE_W, STAGE_H, pressing, pressing ? ramStroke(p.now()) : 0.0F);
    }

    /** 预览 drawPress 的冲头行程: 前 25% 压下, 停 10%, 后 65% 回升 (0..1)。 */
    private static float ramStroke(long now) {
        float ph = Math.floorMod(now, RAM_CYCLE_MS) / (float) RAM_CYCLE_MS;
        if (ph < 0.25F) {
            return ph / 0.25F;
        }
        if (ph < 0.35F) {
            return 1.0F;
        }
        return 1.0F - (ph - 0.35F) / 0.65F;
    }

    // ================================================================== tooltips

    @Nullable
    @Override
    protected List<Component> screenTooltip(double mx, double my) {
        int level = ownerLevel();
        GunsmithPlatform platform = menu.selectedPlatform();
        GunsmithPressPart part = menu.selectedPart();
        GunsmithPartQuality quality = menu.selectedQuality();
        GunsmithPartVariant selectedVariant = menu.selectedVariant();
        boolean carrying = !menu.getCarried().isEmpty();

        GunsmithPlatform[] platforms = GunsmithPlatform.values();
        for (int i = 0; i < platforms.length; i++) {
            if (GunsmithUi.inRect(mx, my, PLAT_X, platformRowY(i), PLAT_W, PLAT_H)) {
                GunsmithPlatform target = platforms[i];
                Component name = Component.translatable(target.labelKey());
                if (!hasBlueprint(target)) {
                    return List.of(tip(name, GunsmithUi.TIP_TITLE),
                            tip(Component.translatable(K + "platform.no_blueprint_tip"), GunsmithUi.TIP_RED));
                }
                if (platformNameCut[i]) {
                    return List.of(tip(name, GunsmithUi.TIP_TITLE));
                }
                return null;
            }
        }

        List<GunsmithPressPart> parts = partsOf(platform);
        for (int i = 0; i < parts.size(); i++) {
            if (GunsmithUi.inRect(mx, my, partCardX(parts.size(), i), PART_Y, PART_W, PART_H)) {
                GunsmithPressPart target = parts.get(i);
                return List.of(
                        tip(Component.translatable(target.labelKey()), GunsmithUi.TIP_TITLE),
                        tip(Component.translatable(target.roleKey()), GunsmithUi.TIP_GRAY),
                        tip(Component.translatable(K + "part.base_cost",
                                inputItem(0).getDescription(), target.partsCost(),
                                inputItem(1).getDescription(), target.alloyCost(),
                                inputItem(2).getDescription(), target.polymerCost()), GunsmithUi.TIP_GRAY));
            }
        }

        if (GunsmithUi.inRect(mx, my, GunsmithPressMenu.SLOT_OUTPUT_X - 1, GunsmithPressMenu.SLOT_OUTPUT_Y - 1, 18, 18)) {
            // 有成品时让原版物品提示 (带摇出的系数) 优先; 手上拿着东西时也不挡视线。
            if (hasOutput() || carrying) {
                return null;
            }
            return List.of(tip(Component.translatable(K + "station.title"), GunsmithUi.TIP_TITLE),
                    tip(Component.translatable(K + "station.hint"), GunsmithUi.TIP_GRAY));
        }

        List<GunsmithPartVariant> variants = GunsmithPartVariant.availableFor(platform, part);
        for (int i = 0; i < variants.size(); i++) {
            if (GunsmithUi.inRect(mx, my, VAR_X, variantRowY(i), VAR_W, VAR_H)) {
                GunsmithPartVariant variant = variants.get(i);
                GunsmithPartRarity rarity = variant.rarity();
                int unlock = rarityUnlockLevel(rarity);
                Component last = level >= unlock
                        ? tip(Component.translatable(K + "variant.fee_mult",
                        formatMultiplier(MunitionsConfig.pressRarityFeeMultiplier(rarity))), GunsmithUi.TIP_GRAY)
                        : tip(Component.translatable(K + "need_level", unlock), GunsmithUi.TIP_RED);
                return List.of(tip(Component.translatable(variant.labelKey()), GunsmithUi.TIP_TITLE),
                        tip(Component.translatable(rarity.labelKey()), GunsmithUi.rarityLight(rarity)),
                        last);
            }
        }

        GunsmithPartQuality[] qualities = GunsmithPartQuality.values();
        for (int i = 0; i < qualities.length; i++) {
            if (GunsmithUi.inRect(mx, my, qualityX(i), QUAL_Y, QUAL_W, QUAL_H)) {
                GunsmithPartQuality target = qualities[i];
                int unlock = qualityUnlockLevel(target);
                Component name = Component.translatable(target.labelKey());
                return List.of(
                        tip(Component.translatable(K + "quality.title", name), GunsmithUi.TIP_TITLE),
                        tip(Component.translatable(K + "quality.cost_time", target.materialMultiplier(),
                                GunsmithUi.formatTicks(target.requiredTicks())), GunsmithUi.TIP_GRAY),
                        tip(Component.translatable(K + "quality.coefficient",
                                GunsmithUi.format2(target.minCoefficient()) + "–"
                                        + GunsmithUi.format2(target.maxCoefficient())), GunsmithUi.TIP_GRAY),
                        level >= unlock
                                ? tip(Component.translatable(K + "quality.unlocked"), GunsmithUi.TIP_GRAY)
                                : tip(Component.translatable(K + "need_level", unlock), GunsmithUi.TIP_RED));
            }
        }

        for (int i = 0; i < INPUT_SLOTS.length; i++) {
            if (GunsmithUi.inRect(mx, my, inputSlotX(i) - 1, inputSlotY(i) - 1, 18, 18)) {
                if (carrying) {
                    return null;
                }
                int need = requiredMaterial(part, quality, i);
                int have = inputCount(i);
                Slot slot = menu.getSlot(INPUT_SLOTS[i]);
                return List.of(
                        tip(Component.translatable(K + "material.tip_title",
                                Component.translatable(INPUT_LABEL_KEYS[i]), inputItem(i).getDescription()),
                                GunsmithUi.TIP_TITLE),
                        tip(Component.translatable(K + "material.tip_need", need, have),
                                have >= need ? GunsmithUi.TIP_GRAY : GunsmithUi.TIP_RED),
                        tip(Component.translatable(K + "material.tip_cap", slot.getMaxStackSize()),
                                GunsmithUi.TIP_GRAY));
            }
        }

        if (GunsmithUi.inRect(mx, my, FEE_X, FEE_Y, FEE_W, FEE_H)) {
            GunsmithPartRarity rarity = selectedVariant.rarity();
            long fee = pressFee(quality, rarity);
            return List.of(
                    tip(Component.translatable(K + "fee_tip.title"), GunsmithUi.TIP_TITLE),
                    tip(Component.translatable(K + "fee_tip.formula",
                            MunitionsConfig.PRESS_WORK_FEE_CREDITS.get(),
                            quality.materialMultiplier(),
                            formatMultiplier(MunitionsConfig.pressRarityFeeMultiplier(rarity)),
                            GunsmithUi.formatCount(fee)), GunsmithUi.TIP_GRAY),
                    tip(Component.translatable(K + "fee_tip.charged"), GunsmithUi.TIP_GRAY));
        }

        if (productNameCut && GunsmithUi.inRect(mx, my, PROD_NAME_X, PROD_NAME_Y - 2, PROD_NAME_W, 10)) {
            return List.of(tip(productName(platform, part, selectedVariant), GunsmithUi.TIP_TITLE));
        }

        if (GunsmithUi.inRect(mx, my, START_X, START_Y, START_W, START_H)) {
            Component why = startBlockReason(level);
            if (why != null) {
                return List.of(tip(why, GunsmithUi.TIP_RED));
            }
            return List.of(tip(Component.translatable(K + "start"), GunsmithUi.TIP_TITLE),
                    tip(Component.translatable(K + "start_tip"), GunsmithUi.TIP_GRAY));
        }
        return null;
    }

    private static Component tip(Component text, int rgb) {
        return GunsmithUi.tip(text, rgb);
    }

    // ================================================================== input

    /**
     * 只处理左键 (与旧界面一致); 其它键与没点中控件的左键返回 false, 交给原版槽位逻辑。
     * 这里所有矩形都在 {@link #assertClickTargetsClearOfSlots()} 里验过不压任何槽位判定盒。
     */
    @Override
    protected boolean onScreenClick(double mx, double my, int button) {
        if (button != 0) {
            return false;
        }
        int level = ownerLevel();
        GunsmithPlatform platform = menu.selectedPlatform();
        GunsmithPressPart part = menu.selectedPart();

        GunsmithPlatform[] platforms = GunsmithPlatform.values();
        for (int i = 0; i < platforms.length; i++) {
            if (GunsmithUi.inRect(mx, my, PLAT_X, platformRowY(i), PLAT_W, PLAT_H)) {
                GunsmithPlatform target = platforms[i];
                if (target != platform && hasBlueprint(target)) {
                    sendButton(GunsmithPressMenu.BUTTON_PLATFORM_BASE + target.index());
                }
                return true;
            }
        }

        List<GunsmithPressPart> parts = partsOf(platform);
        for (int i = 0; i < parts.size(); i++) {
            if (GunsmithUi.inRect(mx, my, partCardX(parts.size(), i), PART_Y, PART_W, PART_H)) {
                if (parts.get(i) != part) {
                    // 行号按 supportedParts() 顺序, 与服务端 trySelectPart 的遍历一致。
                    sendButton(GunsmithPressMenu.BUTTON_PART_BASE + i);
                }
                return true;
            }
        }

        List<GunsmithPartVariant> variants = GunsmithPartVariant.availableFor(platform, part);
        for (int i = 0; i < variants.size(); i++) {
            if (GunsmithUi.inRect(mx, my, VAR_X, variantRowY(i), VAR_W, VAR_H)) {
                GunsmithPartVariant target = variants.get(i);
                if (target != menu.selectedVariant() && level >= rarityUnlockLevel(target.rarity())) {
                    sendButton(GunsmithPressMenu.BUTTON_VARIANT_BASE + target.index());
                }
                return true;
            }
        }

        GunsmithPartQuality[] qualities = GunsmithPartQuality.values();
        for (int i = 0; i < qualities.length; i++) {
            if (GunsmithUi.inRect(mx, my, qualityX(i), QUAL_Y, QUAL_W, QUAL_H)) {
                GunsmithPartQuality target = qualities[i];
                if (target != menu.selectedQuality() && level >= qualityUnlockLevel(target)) {
                    sendButton(GunsmithPressMenu.BUTTON_QUALITY_BASE + target.index());
                }
                return true;
            }
        }

        if (GunsmithUi.inRect(mx, my, START_X, START_Y, START_W, START_H)) {
            // 按钮置灰时不发 (原因在红字提示里); 服务端 tryStartPreview 仍会独立复核并扣工费。
            if (startBlockReason(level) == null) {
                sendButton(GunsmithPressMenu.BUTTON_START_PREVIEW);
            }
            return true;
        }
        return false;
    }

    // ================================================================== state helpers

    /**
     * 开工会被拒的原因 (预览 pressCanStart 的顺序), 可开工时返回 null。信用点是否够扣只有服务端知道,
     * 不在这里判断 (不够时服务端照常回 work_fee_unaffordable)。
     */
    @Nullable
    private Component startBlockReason(int level) {
        if (menu.isPressing()) {
            return Component.translatable(K + "why.busy");
        }
        if (hasOutput()) {
            return Component.translatable(K + "why.output");
        }
        GunsmithPartQuality quality = menu.selectedQuality();
        int qualityUnlock = qualityUnlockLevel(quality);
        if (level < qualityUnlock) {
            return Component.translatable(K + "why.quality", qualityUnlock);
        }
        int rarityUnlock = rarityUnlockLevel(menu.selectedVariant().rarity());
        if (level < rarityUnlock) {
            return Component.translatable(K + "why.rarity", rarityUnlock);
        }
        GunsmithPressPart part = menu.selectedPart();
        for (int i = 0; i < INPUT_SLOTS.length; i++) {
            int need = requiredMaterial(part, quality, i);
            if (need > 0 && inputCount(i) < need) {
                return Component.translatable(K + "why.materials");
            }
        }
        return null;
    }

    private boolean hasOutput() {
        return menu.getSlot(GunsmithPressBlockEntity.SLOT_OUTPUT).hasItem();
    }

    private int inputCount(int input) {
        return menu.getSlot(INPUT_SLOTS[input]).getItem().getCount();
    }

    /** 本次冲压某料槽要多少 (与服务端 requiredGunParts/Alloy/Polymer 同式: 部件基础用料 x 品质材料倍率)。 */
    private static int requiredMaterial(GunsmithPressPart part, GunsmithPartQuality quality, int input) {
        int base = switch (input) {
            case 0 -> part.partsCost();
            case 1 -> part.alloyCost();
            default -> part.polymerCost();
        };
        return base * quality.materialMultiplier();
    }

    private float pressFraction() {
        int required = menu.productionRequiredTicks();
        if (required <= 0) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, menu.productionProgressTicks() / (float) required));
    }

    private int remainingTicks() {
        int required = Math.max(0, menu.productionRequiredTicks());
        int progress = Math.max(0, Math.min(required, menu.productionProgressTicks()));
        return required - progress;
    }

    private static int qualityUnlockLevel(GunsmithPartQuality quality) {
        return MunitionsLevels.partQualityUnlockLevel(quality);
    }

    private static int rarityUnlockLevel(GunsmithPartRarity rarity) {
        return MunitionsConfig.rarityUnlockLevel(rarity);
    }

    /** 与服务端扣费同一个方法 (向上取整)。 */
    private static long pressFee(GunsmithPartQuality quality, GunsmithPartRarity rarity) {
        return MunitionsConfig.pressWorkFeeCredits(quality, rarity);
    }

    private static Component productName(GunsmithPlatform platform, GunsmithPressPart part, GunsmithPartVariant variant) {
        if (variant == GunsmithPartVariant.BASE) {
            return Component.translatable(K + "product.name_base",
                    Component.translatable(platform.labelKey()), Component.translatable(part.labelKey()));
        }
        return Component.translatable(variant.labelKey());
    }

    private static boolean hasBlueprint(GunsmithPlatform platform) {
        return PLATFORMS_WITH_BLUEPRINT.contains(platform);
    }

    private static List<GunsmithPressPart> partsOf(GunsmithPlatform platform) {
        return PARTS_BY_PLATFORM.get(platform);
    }

    private static Set<GunsmithPlatform> platformsWithBlueprint() {
        EnumSet<GunsmithPlatform> set = EnumSet.noneOf(GunsmithPlatform.class);
        for (GunsmithBlueprint blueprint : GunsmithBlueprint.values()) {
            set.add(blueprint.platform());
        }
        return set;
    }

    private static Map<GunsmithPlatform, List<GunsmithPressPart>> partsByPlatform() {
        Map<GunsmithPlatform, List<GunsmithPressPart>> map = new EnumMap<>(GunsmithPlatform.class);
        for (GunsmithPlatform platform : GunsmithPlatform.values()) {
            map.put(platform, List.copyOf(platform.supportedParts()));
        }
        return map;
    }

    // ================================================================== geometry

    private static int platformRowY(int i) {
        return PLAT_Y + i * PLAT_STEP;
    }

    /** 部件卡在 (99, 164 宽) 的中栏里居中排开 (预览 px0 = 99 + floor((164 - total) / 2))。 */
    private static int partCardX(int count, int i) {
        int total = count * PART_W + (count - 1) * PART_GAP;
        int x0 = PART_AREA_X + Math.floorDiv(PART_AREA_W - total, 2);
        return x0 + i * (PART_W + PART_GAP);
    }

    private static int variantRowY(int i) {
        return VAR_Y + i * VAR_STEP;
    }

    private static int qualityX(int i) {
        return QUAL_X + i * QUAL_STEP;
    }

    private static int inputSlotX(int input) {
        return GunsmithPressMenu.containerSlotX(INPUT_SLOTS[input]);
    }

    private static int inputSlotY(int input) {
        return GunsmithPressMenu.containerSlotY(INPUT_SLOTS[input]);
    }

    // ================================================================== drawing helpers

    /** 选中型号的贴图只在它确实适配这个部件时用 (菜单数据逐项同步, 切平台/部件的那一两帧可能不一致)。 */
    private static GunsmithPartVariant iconVariant(GunsmithPartVariant variant, GunsmithPlatform platform,
                                                   GunsmithPressPart part) {
        return variant.supports(platform, part) ? variant : GunsmithPartVariant.BASE;
    }

    private static ResourceLocation partTexture(GunsmithPlatform platform, GunsmithPressPart part,
                                                GunsmithPartVariant variant, GunsmithPartQuality quality) {
        String suffix = switch (variant) {
            case BASE -> "";
            case GEHENNA_GAS -> "_gehenna";
            case RED_EAST_HIGH_PRESSURE_GAS -> "_red_east_high_pressure";
            case MK_AX_A_BOLT -> "_mk_ax_a";
            case TRINITY_PRECISION_GRADUATED_BARREL -> "_trinity_precision_graduated";
            case AR_THREE_ROUND_BURST_BOLT -> "_three_round_burst";
            case TRINITY_PRECISION_GRADUATED_SNIPER_BARREL -> "_trinity_precision_graduated";
            case RED_WINTER_CHIXUE_A_BOLT -> "_red_winter_chixue_a";
        };
        return new ResourceLocation(MiningConstants.MODID,
                "textures/item/gunsmith_part_" + platform.id() + "_" + part.id() + suffix + "_" + quality.id() + ".png");
    }

    /** 槽内 16x16 的底色 (空料槽里材料淡影的"面纱"色), 与各风格 slot() 的内底一致。 */
    private static int slotInner(GunsmithTheme theme) {
        return switch (theme.style()) {
            case ACADEMY -> 0xE4E9F0;
            case INDUSTRIAL -> 0x101318;
            case BLUEPRINT -> 0x123A6C;
        };
    }

    /**
     * 缩到最小字号仍放不下时截断并补省略号 (英文平台名/型号名远长于中文)。放得下时原样返回同一个对象,
     * 调用方据此判断"是否截断过"。
     */
    private static Component ellipsize(GsPainter p, Component text, float maxW, float scale, boolean bold) {
        if (p.textWidth(text, scale, bold) <= maxW) {
            return text;
        }
        String full = text.getString();
        String ellipsis = "…";
        int end = full.length();
        while (end > 0 && p.textWidth(full.substring(0, end) + ellipsis, scale, bold) > maxW) {
            end--;
        }
        return Component.literal(full.substring(0, end).trim() + ellipsis);
    }

    /** 倍率显示: 整数不带小数 (1, 4), 其余去掉尾零 (1.5, 2.5)。 */
    private static String formatMultiplier(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0E-9D) {
            return String.valueOf((long) Math.rint(value));
        }
        String s = String.format(Locale.ROOT, "%.2f", value);
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    // ================================================================== self-check

    /**
     * 逐一比对 onScreenClick 里所有可能出现的可点矩形 (9 个平台行、任一平台的部件卡、任一平台/部件组合的型号行、
     * 5 个品质钮、开始按钮) 与 36 个背包槽 + 4 个容器槽的原版判定盒 (槽 x-1, y-1 起 18x18), 相交即抛。
     * 这些矩形在 super.mouseClicked 之前就吞掉左键, 一旦压到槽位, 那个槽就点不动了。
     */
    private static void assertClickTargetsClearOfSlots() {
        List<int[]> slotBoxes = new ArrayList<>();
        for (int row = 0; row < 4; row++) {
            // 与 AbstractMiningMenu.addPlayerInventory 一致: 18px 槽距, 快捷栏在主背包下方再隔 4px。
            int slotY = row < 3
                    ? GunsmithPressMenu.PLAYER_INV_Y + row * 18
                    : GunsmithPressMenu.PLAYER_INV_Y + 3 * 18 + 4;
            for (int col = 0; col < 9; col++) {
                slotBoxes.add(new int[]{GunsmithPressMenu.PLAYER_INV_X + col * 18 - 1, slotY - 1, 18, 18});
            }
        }
        for (int slot = 0; slot < GunsmithPressBlockEntity.SLOT_COUNT; slot++) {
            slotBoxes.add(new int[]{GunsmithPressMenu.containerSlotX(slot) - 1,
                    GunsmithPressMenu.containerSlotY(slot) - 1, 18, 18});
        }

        List<int[]> targets = new ArrayList<>();
        int maxVariants = 0;
        for (int i = 0; i < GunsmithPlatform.values().length; i++) {
            targets.add(new int[]{PLAT_X, platformRowY(i), PLAT_W, PLAT_H});
        }
        for (GunsmithPlatform platform : GunsmithPlatform.values()) {
            List<GunsmithPressPart> parts = List.copyOf(platform.supportedParts());
            for (int i = 0; i < parts.size(); i++) {
                targets.add(new int[]{partCardX(parts.size(), i), PART_Y, PART_W, PART_H});
                maxVariants = Math.max(maxVariants, GunsmithPartVariant.availableFor(platform, parts.get(i)).size());
            }
        }
        for (int i = 0; i < maxVariants; i++) {
            targets.add(new int[]{VAR_X, variantRowY(i), VAR_W, VAR_H});
        }
        for (int i = 0; i < GunsmithPartQuality.values().length; i++) {
            targets.add(new int[]{qualityX(i), QUAL_Y, QUAL_W, QUAL_H});
        }
        targets.add(new int[]{START_X, START_Y, START_W, START_H});

        for (int[] t : targets) {
            for (int[] s : slotBoxes) {
                if (t[0] < s[0] + s[2] && s[0] < t[0] + t[2] && t[1] < s[1] + s[3] && s[1] < t[1] + t[3]) {
                    throw new IllegalStateException("gunsmith press click target (" + t[0] + "," + t[1] + " "
                            + t[2] + "x" + t[3] + ") overlaps the slot hit box at (" + (s[0] + 1) + ","
                            + (s[1] + 1) + ")");
                }
            }
        }
    }
}
