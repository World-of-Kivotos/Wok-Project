package com.miningdim.job.munitions.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.ModMunitionsItems;
import com.miningdim.job.munitions.MunitionsAmmoFactory;
import com.miningdim.job.munitions.MunitionsCaliber;
import com.miningdim.job.munitions.MunitionsConfig;
import com.miningdim.job.munitions.MunitionsLevels;
import com.miningdim.job.munitions.MunitionsProduction;
import com.miningdim.job.munitions.block.MunitionsBenchBlockEntity;
import com.miningdim.job.munitions.client.style.GsPainter;
import com.miningdim.job.munitions.client.style.GunsmithStyledScreen;
import com.miningdim.job.munitions.client.style.GunsmithTheme;
import com.miningdim.job.munitions.client.style.GunsmithTheme.BarKind;
import com.miningdim.job.munitions.client.style.GunsmithTheme.ButtonKind;
import com.miningdim.job.munitions.client.style.GunsmithTheme.State;
import com.miningdim.job.munitions.client.style.GunsmithUi;
import com.miningdim.job.munitions.menu.MunitionsBenchMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.miningdim.job.munitions.client.style.GsPainter.BOLD;
import static com.miningdim.job.munitions.client.style.GsPainter.CENTER;
import static com.miningdim.job.munitions.client.style.GsPainter.RIGHT;
import static com.miningdim.job.munitions.client.style.GunsmithUi.inRect;

/**
 * 军火台 (弹药制造) 界面, 360x240, 按已定稿的设计预览 drawBench / drawCancelDialog 逐块移植:
 * <ul>
 *   <li>左上 产线参数: 产能 / 每批产出 / 单批耗时 / 缓冲上限 / 工费 / 电费 / 发射药 (直造 / 提炼);</li>
 *   <li>中上 类别页签 + 该类口径卡 (图标是真弹药物品; 点页签只切换本地查看的类别, 点已解锁口径卡发选口径按钮);</li>
 *   <li>中间 口径展示 + 名称 / 售价 + 本批进度;</li>
 *   <li>右上 四种原料 (每批用量, 不够标红) + 内部电池;</li>
 *   <li>左下 控制: 开始 / 停止 (停止先弹确认框) + 单次 / 连续 + 上锁状态;</li>
 *   <li>右下 产出槽 + 缓冲。</li>
 * </ul>
 * 风格 (学园 / 工控 / 蓝图)、标题栏、操作员块、风格面板与浮层输入规则都在 {@link GunsmithStyledScreen}。
 * 取消确认框走基类的模态框钩子: 打开时任何点击、按键、滚轮都到不了下面的格子和按钮。
 *
 * 所有数值来自 Menu 同步值与 {@link MunitionsConfig} (服务端配置, 已同步到客户端), 与服务端判定同一套公式;
 * "为什么不能开工" 只是提示, 真正的门仍在服务端重校。
 */
public final class MunitionsBenchScreen extends GunsmithStyledScreen<MunitionsBenchMenu> {

    /** 基类要求非空的底图 (新界面不画它, 底图由风格运行时生成)。 */
    private static final ResourceLocation FALLBACK_BACKGROUND =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/container/munitions_bench.png");
    private static final String KEY = "screen.miningdim.munitions_bench.";

    private static final MunitionsCaliber.Category[] CATEGORY_ORDER = {
            MunitionsCaliber.Category.PISTOL,
            MunitionsCaliber.Category.RIFLE,
            MunitionsCaliber.Category.SHOTGUN,
            MunitionsCaliber.Category.SNIPER,
            MunitionsCaliber.Category.EXPLOSIVE
    };
    /** 每个类别下的口径 (枚举顺序)。 */
    private static final Map<MunitionsCaliber.Category, List<MunitionsCaliber>> CALIBERS = calibersByCategory();

    // ---- 左上: 产线参数
    private static final int PARAM_X = 8;
    private static final int PARAM_Y = 30;
    private static final int PARAM_W = 84;
    private static final int PARAM_H = 112;
    private static final int PARAM_ROW_X = 14;
    private static final int PARAM_ROW_Y = 45;
    private static final int PARAM_ROW_PITCH = 13;
    private static final int PARAM_ROW_W = 72;
    /** 发射药那一行的提示命中区 (预览 hit(8, 30 + 13 + 6 * 13, 84, 13))。 */
    private static final int PROPELLANT_TIP_Y = PARAM_Y + PARAM_ROW_PITCH + 6 * PARAM_ROW_PITCH;

    // ---- 中上: 类别页签 + 口径卡
    private static final int TAB_X = 99;
    private static final int TAB_Y = 30;
    private static final int TAB_W = 32;
    private static final int TAB_H = 12;
    private static final int TAB_PITCH = 33;
    private static final int CARD_X = 99;
    private static final int CARD_Y = 45;
    private static final int CARD_H = 25;
    private static final int CARD_PITCH = 33;
    /** 一排最多按标准宽度放 5 张; 更多时按这块宽度均分 (合入更多口径后不重叠)。 */
    private static final int CARDS_AT_FULL_WIDTH = 5;
    private static final int CARD_AREA_W = 165;

    // ---- 中间: 口径展示 + 进度
    private static final int SHOW_PANEL_X = 99;
    private static final int SHOW_PANEL_Y = 73;
    private static final int SHOW_PANEL_W = 164;
    private static final int SHOW_PANEL_H = 69;
    private static final int SHOWCASE_X = 100;
    private static final int SHOWCASE_Y = 74;
    private static final int SHOWCASE_W = 162;
    private static final int SHOWCASE_H = 42;
    private static final int INFO_X = 105;
    private static final int INFO_RIGHT = 257;
    private static final int INFO_W = INFO_RIGHT - INFO_X;

    // ---- 右侧两块面板共用的文字列
    private static final int SIDE_PANEL_X = 270;
    private static final int SIDE_PANEL_W = 82;
    private static final int SIDE_TEXT_X = 276;
    private static final int SIDE_TEXT_RIGHT = 346;
    private static final int SIDE_TEXT_W = SIDE_TEXT_RIGHT - SIDE_TEXT_X;

    // ---- 右上: 原料 + 电力
    private static final int MATERIAL_PANEL_Y = 30;
    private static final int MATERIAL_PANEL_H = 112;
    private static final int MATERIAL_LABEL_W = 40;
    private static final int POWER_TIP_Y = 104;
    private static final int POWER_TIP_H = 36;

    // ---- 左下: 控制
    private static final int CONTROL_X = 8;
    private static final int CONTROL_Y = 146;
    private static final int CONTROL_W = 84;
    private static final int CONTROL_H = 86;
    private static final int START_X = 14;
    private static final int START_Y = 160;
    private static final int START_W = 72;
    private static final int START_H = 22;
    private static final int SEG_X = 14;
    private static final int SEG_Y = 189;
    private static final int SEG_W = 72;
    private static final int SEG_H = 12;

    // ---- 右下: 产出
    private static final int OUTPUT_PANEL_Y = 146;
    private static final int OUTPUT_PANEL_H = 86;

    // ---- 取消确认框
    private static final int DIALOG_X = 105;
    private static final int DIALOG_Y = 80;
    private static final int DIALOG_W = 150;
    private static final int DIALOG_H = 72;
    private static final int DIALOG_CONFIRM_X = 120;
    private static final int DIALOG_KEEP_X = 184;
    private static final int DIALOG_BUTTON_Y = 124;
    private static final int DIALOG_BUTTON_W = 56;
    private static final int DIALOG_BUTTON_H = 16;

    // ---- 四种原料 (料槽下标与 BE 槽位一致; 坐标与 Menu 同源)
    private static final int MATERIAL_COUNT = 4;
    private static final int[] MATERIAL_SLOT = {
            MunitionsBenchBlockEntity.SLOT_PRIMER,
            MunitionsBenchBlockEntity.SLOT_CASING,
            MunitionsBenchBlockEntity.SLOT_BULLET_HEAD,
            MunitionsBenchBlockEntity.SLOT_PROPELLANT
    };
    private static final int[] MATERIAL_X = {
            MunitionsBenchMenu.SLOT_PRIMER_X,
            MunitionsBenchMenu.SLOT_CASING_X,
            MunitionsBenchMenu.SLOT_BULLET_HEAD_X,
            MunitionsBenchMenu.SLOT_PROPELLANT_X
    };
    private static final int[] MATERIAL_Y = {
            MunitionsBenchMenu.SLOT_PRIMER_Y,
            MunitionsBenchMenu.SLOT_CASING_Y,
            MunitionsBenchMenu.SLOT_BULLET_HEAD_Y,
            MunitionsBenchMenu.SLOT_PROPELLANT_Y
    };
    private static final String[] MATERIAL_KEY = {"primer", "casing", "bullet_head", "propellant"};

    /** 玩家在本界面里看的类别 (纯客户端; null = 跟随选中口径的类别)。 */
    @Nullable
    private MunitionsCaliber.Category viewedCategory;
    /** 点了"停止制造", 等玩家确认。 */
    private boolean confirmingCancel;
    /** 口径卡图标: 真弹药物品 (TACZ 未加载时为空, 不画)。按口径缓存, 不每帧构造。 */
    private final Map<MunitionsCaliber, ItemStack> ammoIcons = new EnumMap<>(MunitionsCaliber.class);
    /** 空料槽里的淡色原料剪影。 */
    @Nullable
    private ItemStack[] materialGhosts;

    public MunitionsBenchScreen(MunitionsBenchMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, FALLBACK_BACKGROUND);
    }

    // ================================================================== per-frame model

    /**
     * 一帧用到的全部数据 (渲染、提示、点击共用同一份计算)。
     *
     * @param selected 服务端选中的口径 (null = 还没选)
     * @param shown    展示区显示的口径 (没选时退回手枪弹, 与旧界面一致)
     * @param blocker  现在不能开工的原因 (null = 可以开工)
     */
    private record Model(int level, @Nullable MunitionsCaliber selected, MunitionsCaliber shown,
                         MunitionsCaliber.Category viewed, int perBatch, long batchTicks,
                         int buffered, int bufferCap, int feBatch, long fe, long feCap,
                         boolean running, boolean continuous, int requiredTicks, int progressTicks,
                         int[] have, int[] need, int materialBatches, @Nullable Component blocker) {

        float progress() {
            if (!running || requiredTicks <= 0) {
                return 0.0F;
            }
            return Math.max(0.0F, Math.min(1.0F, progressTicks / (float) requiredTicks));
        }

        long remainingTicks() {
            return Math.max(0L, (long) requiredTicks - progressTicks);
        }

        long powerBatches() {
            return feBatch <= 0 ? 0L : fe / feBatch;
        }

        int batchesUntilFull() {
            return perBatch <= 0 ? 0 : Math.max(0, bufferCap - buffered) / perBatch;
        }
    }

    private Model model() {
        int level = menu.effectiveMunitionsLevel();
        MunitionsCaliber selected = selectedCaliber();
        MunitionsCaliber shown = selected != null ? selected : MunitionsCaliber.PISTOL;
        MunitionsCaliber.Category viewed = viewedCategory != null ? viewedCategory : shown.category();
        int perBatch = MunitionsProduction.roundsPerBatch(shown, level);
        long batchTicks = batchTicks(shown, level, perBatch);
        int buffered = Math.max(0, menu.bufferedRounds());
        int bufferCap = Math.max(0, menu.bufferCap());
        int feBatch = MunitionsProduction.feCostPerBatch(level);
        long fe = menu.storedEnergyFe();
        long feCap = menu.energyCapacityFe();
        boolean running = menu.isCraftingActive();
        int required = Math.max(0, menu.productionRequiredTicks());
        int progress = Math.max(0, Math.min(required, menu.productionProgressTicks()));
        int[] have = new int[MATERIAL_COUNT];
        int[] need = new int[MATERIAL_COUNT];
        int materialBatches = Integer.MAX_VALUE;
        for (int i = 0; i < MATERIAL_COUNT; i++) {
            have[i] = materialCount(i);
            need[i] = Math.max(1, materialCost(i));
            materialBatches = Math.min(materialBatches, have[i] / need[i]);
        }
        Component blocker = startBlocker(level, selected, perBatch, buffered, bufferCap, feBatch, fe,
                materialBatches);
        return new Model(level, selected, shown, viewed, perBatch, batchTicks, buffered, bufferCap, feBatch, fe,
                feCap, running, menu.isContinuousCrafting(), required, progress, have, need, materialBatches,
                blocker);
    }

    /**
     * 与服务端 tryStartCraft 同序的开工门 (预览 benchCanStart), 只作按钮置灰与提示。
     * 台主身份与缓冲口径是否一致客户端看不到, 仍由服务端把关。
     */
    @Nullable
    private static Component startBlocker(int level, @Nullable MunitionsCaliber selected, int perBatch,
                                          int buffered, int bufferCap, int feBatch, long fe, int materialBatches) {
        if (selected == null) {
            return tr("why.no_caliber");
        }
        if (!MunitionsLevels.isCaliberUnlocked(level, selected)) {
            return tr("why.level", selected.unlockLevel());
        }
        if (materialBatches < 1) {
            return tr("why.materials");
        }
        // 电量按 kFE 同步 (向下取整), 只有差出一个同步单位以上才算确定不够, 不误拦服务端会放行的开工。
        if (fe + MunitionsBenchBlockEntity.ENERGY_SYNC_UNIT_FE - 1 < feBatch) {
            return tr("why.power", GunsmithUi.formatMegaFe(feBatch));
        }
        if (buffered + perBatch > bufferCap) {
            return tr("why.buffer");
        }
        return null;
    }

    /** 与 BE productionRequiredTicksFor 同一公式: ceil(每批发数 / 缩产系数) × 每发 tick。 */
    private static long batchTicks(MunitionsCaliber caliber, int level, int perBatch) {
        long rifleRounds = Math.max(1L, (long) Math.ceil(perBatch / caliber.yieldFactor()));
        return rifleRounds * MunitionsProduction.ticksPerRound(level);
    }

    @Nullable
    private MunitionsCaliber selectedCaliber() {
        int index = menu.selectedCaliberIndex();
        if (index < 0) {
            return null;
        }
        for (MunitionsCaliber caliber : MunitionsCaliber.values()) {
            if (caliber.index() == index) {
                return caliber;
            }
        }
        return null;
    }

    /** 容器自己的槽 (BE 缺失时 Menu 不加容器槽, 此时返回 null, 不误读玩家背包槽)。 */
    @Nullable
    private Slot containerSlot(int index) {
        if (index < 0 || index >= menu.slots.size()) {
            return null;
        }
        Slot slot = menu.slots.get(index);
        return slot.container instanceof Inventory ? null : slot;
    }

    private int materialCount(int i) {
        Slot slot = containerSlot(MATERIAL_SLOT[i]);
        return slot == null ? 0 : slot.getItem().getCount();
    }

    private static int materialCost(int i) {
        return switch (i) {
            case 0 -> MunitionsConfig.RECIPE_PRIMER_COST.get();
            case 1 -> MunitionsConfig.RECIPE_CASING_COST.get();
            case 2 -> MunitionsConfig.RECIPE_BULLET_HEAD_COST.get();
            default -> MunitionsConfig.RECIPE_PROPELLANT_COST.get();
        };
    }

    // ================================================================== header

    @Override
    protected GunsmithTheme.Header header() {
        int level = menu.effectiveMunitionsLevel();
        MunitionsCaliber selected = selectedCaliber();
        MunitionsCaliber shown = selected != null ? selected : MunitionsCaliber.PISTOL;
        return new GunsmithTheme.Header(tr("title"), tr("title_en"), GunsmithUi.tierBadge(level), tr("subtitle"),
                "WOK-MUN-" + shown.defaultAmmoPath().toUpperCase(Locale.ROOT), menu.isCraftingActive(), level);
    }

    // ================================================================== main layer

    @Override
    protected void renderScreen(GsPainter p, GunsmithTheme t) {
        // 批次在确认框打开期间自己结束了: 收起确认, 下次开工不会凭旧状态直接弹框。
        if (!menu.isCraftingActive()) {
            confirmingCancel = false;
        }
        Model m = model();
        renderParameters(p, t, m);
        renderSelector(p, t, m);
        renderShowcase(p, t, m);
        renderMaterials(p, t, m);
        renderControls(p, t, m);
        renderOutput(p, t, m);
        drawPlayerInventorySlots(p, t);
    }

    private void renderParameters(GsPainter p, GunsmithTheme t, Model m) {
        t.panel(p, PARAM_X, PARAM_Y, PARAM_W, PARAM_H, tr("panel.params"));
        Component[] labels = {
                tr("param.rate"),
                tr("param.per_batch"),
                tr("param.batch_time"),
                tr("param.buffer_cap"),
                tr("param.work_fee"),
                tr("param.power_fee"),
                tr("param.propellant")
        };
        Component[] values = {
                tr("param.rate_value", MunitionsLevels.ratePerTable(m.level())),
                tr("rounds", m.perBatch()),
                Component.literal(GunsmithUi.formatTicks(m.batchTicks())),
                tr("rounds", GunsmithUi.formatCount(m.bufferCap())),
                tr("param.work_fee_value", workFeePerRound()),
                tr("param.power_fee_value", GunsmithUi.formatMegaFe(m.feBatch())),
                menu.isRefineUnlocked()
                        ? tr("param.refine_value", MunitionsConfig.REFINED_ROUNDS_PER_BATCH.get())
                        : tr("param.direct_value", MunitionsConfig.DIRECT_ROUNDS_PER_BATCH.get())
        };
        for (int i = 0; i < labels.length; i++) {
            int y = PARAM_ROW_Y + i * PARAM_ROW_PITCH;
            float labelW = p.text(labels[i], PARAM_ROW_X, y, t.c.muted(), 0.62F, 0);
            fitText(p, values[i], PARAM_ROW_X + PARAM_ROW_W, y, t.c.text(), 0.62F,
                    PARAM_ROW_W - labelW - 3.0F, RIGHT);
            if (i < labels.length - 1) {
                t.sep(p, PARAM_ROW_X, y + 9, PARAM_ROW_W);
            }
        }
    }

    private void renderSelector(GsPainter p, GunsmithTheme t, Model m) {
        for (int i = 0; i < CATEGORY_ORDER.length; i++) {
            MunitionsCaliber.Category category = CATEGORY_ORDER[i];
            int x = TAB_X + i * TAB_PITCH;
            boolean reachable = m.level() >= minUnlockLevel(category);
            State state = category == m.viewed() ? State.SEL
                    : !reachable ? State.LOCK
                    : p.hov(x, TAB_Y, TAB_W, TAB_H) ? State.HOVER : State.IDLE;
            t.tab(p, x, TAB_Y, TAB_W, TAB_H, tabLabel(category), state);
        }

        List<MunitionsCaliber> calibers = calibersOf(m.viewed());
        int pitch = cardPitch(calibers.size());
        int w = pitch - 1;
        for (int i = 0; i < calibers.size(); i++) {
            MunitionsCaliber caliber = calibers.get(i);
            int x = CARD_X + i * pitch;
            boolean unlocked = MunitionsLevels.isCaliberUnlocked(m.level(), caliber);
            State state = caliber == m.selected() ? State.SEL
                    : !unlocked ? State.LOCK
                    : p.hov(x, CARD_Y, w, CARD_H) ? State.HOVER : State.IDLE;
            t.card(p, x, CARD_Y, w, CARD_H, state);
            int iconX = x + (w - 16) / 2;
            ItemStack icon = ammoIcon(caliber);
            if (unlocked) {
                p.item(icon, iconX, CARD_Y + 2);
            } else {
                p.itemFaded(icon, iconX, CARD_Y + 2, 0.35F, lockedCardInner(t));
            }
            p.text(caliber.shortLabel(), x + w / 2.0F, CARD_Y + 18.5F, t.cardText(state), 0.6F, CENTER);
            if (!unlocked) {
                t.lock(p, x + w - 8, CARD_Y + 3);
                p.text(GunsmithUi.levelShort(caliber.unlockLevel()), x + 3, CARD_Y + 3, t.c.bad(), 0.5F, 0);
            }
        }
    }

    private void renderShowcase(GsPainter p, GunsmithTheme t, Model m) {
        t.panel(p, SHOW_PANEL_X, SHOW_PANEL_Y, SHOW_PANEL_W, SHOW_PANEL_H, null);
        t.showcase(p, m.shown(), SHOWCASE_X, SHOWCASE_Y, SHOWCASE_W, SHOWCASE_H);

        Component sell = tr("sell_price", m.shown().sellPrice());
        float sellW = p.textWidth(sell, 0.58F, false);
        fitText(p, caliberName(m.shown()), INFO_X, 117, t.c.text(), 0.7F, INFO_W - sellW - 4.0F, BOLD);
        p.text(sell, INFO_RIGHT, 117.5F, t.c.muted(), 0.58F, RIGHT);

        boolean running = m.running();
        Component status = running ? tr("status.running", m.perBatch()) : tr("status.idle", m.perBatch());
        Component time = running
                ? tr("time.remaining", GunsmithUi.formatTicks(m.remainingTicks()))
                : tr("time.batch", GunsmithUi.formatTicks(m.batchTicks()));
        float timeW = p.textWidth(time, 0.62F, false);
        fitText(p, status, INFO_X, 127, running ? t.c.accent() : t.c.muted(), 0.58F, INFO_W - timeW - 4.0F, 0);
        p.text(time, INFO_RIGHT, 126.5F, t.c.text(), 0.62F, RIGHT);
        t.bar(p, INFO_X, 134, INFO_W, 4, m.progress(), BarKind.PROG, running);
    }

    private void renderMaterials(GsPainter p, GunsmithTheme t, Model m) {
        t.panel(p, SIDE_PANEL_X, MATERIAL_PANEL_Y, SIDE_PANEL_W, MATERIAL_PANEL_H, tr("panel.materials"));
        for (int i = 0; i < MATERIAL_COUNT; i++) {
            int sx = MATERIAL_X[i];
            int sy = MATERIAL_Y[i];
            int have = m.have()[i];
            int need = m.need()[i];
            t.slot(p, sx, sy);
            if (have <= 0) {
                // 空槽: 淡色剪影提示该放什么 (有料时原版在槽里画真物品)。
                p.itemFaded(materialGhost(i), sx, sy, 0.3F, slotInner(t));
            }
            fitText(p, tr("material.need", materialName(i), need), sx + 8, sy + 19,
                    have >= need ? t.c.muted() : t.c.bad(), 0.55F, MATERIAL_LABEL_W, CENTER);
        }

        t.sep(p, SIDE_TEXT_X, 103, SIDE_TEXT_W);
        Component powerValue = Component.literal(GunsmithUi.formatMegaFe(m.fe()) + " / " + compactMega(m.feCap()));
        float valueW = p.textWidth(powerValue, 0.56F, false);
        fitText(p, tr("power.label"), SIDE_TEXT_X, 107, t.c.muted(), 0.62F, SIDE_TEXT_W - valueW - 3.0F, 0);
        p.text(powerValue, SIDE_TEXT_RIGHT, 107.5F, t.c.text(), 0.56F, RIGHT);
        long runs = m.powerBatches();
        t.bar(p, SIDE_TEXT_X, 116, SIDE_TEXT_W, 5, fraction(m.fe(), m.feCap()),
                runs > 0 ? BarKind.FE : BarKind.BAD, false);
        fitText(p, runs > 0 ? tr("power.batches", runs) : tr("power.short"), SIDE_TEXT_X, 125,
                runs > 0 ? t.c.muted() : t.c.bad(), 0.55F, SIDE_TEXT_W, 0);
        fitText(p, tr("materials.batches", m.materialBatches()), SIDE_TEXT_X, 133,
                m.materialBatches() > 0 ? t.c.muted() : t.c.bad(), 0.55F, SIDE_TEXT_W, 0);
    }

    private void renderControls(GsPainter p, GunsmithTheme t, Model m) {
        t.panel(p, CONTROL_X, CONTROL_Y, CONTROL_W, CONTROL_H, tr("panel.control"));
        boolean running = m.running();
        State buttonState = !running && m.blocker() != null ? State.OFF
                : p.hov(START_X, START_Y, START_W, START_H) ? State.HOVER : State.IDLE;
        t.btn(p, START_X, START_Y, START_W, START_H, running ? tr("button.stop") : tr("button.start"),
                running ? ButtonKind.STOP : ButtonKind.GO, buttonState);
        t.seg(p, SEG_X, SEG_Y, SEG_W, SEG_H, new Component[]{modeLabel(false), modeLabel(true)},
                m.continuous() ? 1 : 0);
        t.lock(p, 14, 207);
        fitText(p, menu.isLocked() ? tr("lock.locked") : tr("lock.unlocked"), 22, 207.5F, t.c.muted(), 0.52F,
                64.0F, 0);
        fitText(p, tr("lock.hint"), 14, 217, t.c.dim(), 0.48F, 72.0F, 0);
    }

    private void renderOutput(GsPainter p, GunsmithTheme t, Model m) {
        t.panel(p, SIDE_PANEL_X, OUTPUT_PANEL_Y, SIDE_PANEL_W, OUTPUT_PANEL_H, tr("panel.output"));
        t.slotOut(p, MunitionsBenchMenu.SLOT_OUTPUT_X, MunitionsBenchMenu.SLOT_OUTPUT_Y);

        Component value = Component.literal(GunsmithUi.formatCount(m.buffered()) + " / "
                + GunsmithUi.formatCount(m.bufferCap()));
        float valueW = p.textWidth(value, 0.56F, false);
        fitText(p, tr("buffer.label"), SIDE_TEXT_X, 187, t.c.muted(), 0.62F, SIDE_TEXT_W - valueW - 3.0F, 0);
        p.text(value, SIDE_TEXT_RIGHT, 187.5F, t.c.text(), 0.56F, RIGHT);
        t.bar(p, SIDE_TEXT_X, 196, SIDE_TEXT_W, 5, fraction(m.buffered(), m.bufferCap()), BarKind.BUF, false);
        int left = m.batchesUntilFull();
        fitText(p, left > 0 ? tr("buffer.batches_left", left) : tr("buffer.full"), SIDE_TEXT_X, 206,
                left > 0 ? t.c.muted() : t.c.bad(), 0.52F, SIDE_TEXT_W, 0);
        fitText(p, tr("buffer.hint"), SIDE_TEXT_X, 216, t.c.dim(), 0.48F, SIDE_TEXT_W, 0);
    }

    // ================================================================== cancel confirmation (modal)

    @Override
    protected boolean isModalOpen() {
        return confirmingCancel && menu.isCraftingActive();
    }

    @Override
    protected void renderModal(GsPainter p, GunsmithTheme t) {
        p.rect(0, 0, W, H, t.shade());
        t.panel(p, DIALOG_X, DIALOG_Y, DIALOG_W, DIALOG_H, null);
        float centerX = DIALOG_X + DIALOG_W / 2.0F;
        fitText(p, Component.translatable("gui.miningdim.munitions.cancel_dialog_title"), centerX, 91,
                t.c.text(), 0.86F, DIALOG_W - 12.0F, CENTER | BOLD);
        fitText(p, tr("cancel.hint"), centerX, 105, t.c.muted(), 0.62F, DIALOG_W - 12.0F, CENTER);
        t.btn(p, DIALOG_CONFIRM_X, DIALOG_BUTTON_Y, DIALOG_BUTTON_W, DIALOG_BUTTON_H,
                Component.translatable("gui.miningdim.munitions.cancel_confirm"), ButtonKind.STOP,
                p.hov(DIALOG_CONFIRM_X, DIALOG_BUTTON_Y, DIALOG_BUTTON_W, DIALOG_BUTTON_H)
                        ? State.HOVER : State.IDLE);
        t.btn(p, DIALOG_KEEP_X, DIALOG_BUTTON_Y, DIALOG_BUTTON_W, DIALOG_BUTTON_H,
                Component.translatable("gui.miningdim.munitions.cancel_keep"), ButtonKind.ACC,
                p.hov(DIALOG_KEEP_X, DIALOG_BUTTON_Y, DIALOG_BUTTON_W, DIALOG_BUTTON_H)
                        ? State.HOVER : State.IDLE);
    }

    @Override
    protected void onModalClick(double relX, double relY, int button) {
        if (button != 0) {
            return;
        }
        if (inRect(relX, relY, DIALOG_CONFIRM_X, DIALOG_BUTTON_Y, DIALOG_BUTTON_W, DIALOG_BUTTON_H)) {
            confirmingCancel = false;
            sendButton(MunitionsBenchMenu.BUTTON_CANCEL_CRAFT);
        } else if (inRect(relX, relY, DIALOG_KEEP_X, DIALOG_BUTTON_Y, DIALOG_BUTTON_W, DIALOG_BUTTON_H)) {
            confirmingCancel = false;
        }
    }

    @Override
    protected void closeModal() {
        confirmingCancel = false;
    }

    // ================================================================== input

    @Override
    protected boolean onScreenClick(double relX, double relY, int button) {
        // 自绘控件只认左键; 右键 / 中键交给原版槽位逻辑 (分堆等容器操作不误触开工 / 取消)。
        if (button != 0) {
            return false;
        }
        Model m = model();
        for (int i = 0; i < CATEGORY_ORDER.length; i++) {
            if (inRect(relX, relY, TAB_X + i * TAB_PITCH, TAB_Y, TAB_W, TAB_H)) {
                viewedCategory = CATEGORY_ORDER[i];
                return true;
            }
        }
        MunitionsCaliber card = cardAt(m.viewed(), relX, relY);
        if (card != null) {
            // 已选中的口径不重发: 服务端每次选口径都会把离线追产的时间戳推到当前。
            if (MunitionsLevels.isCaliberUnlocked(m.level(), card) && card != m.selected()) {
                sendButton(card.index());
            }
            return true;
        }
        if (inRect(relX, relY, START_X, START_Y, START_W, START_H)) {
            if (m.running()) {
                confirmingCancel = true;
            } else if (m.blocker() == null) {
                sendButton(MunitionsBenchMenu.BUTTON_START_CRAFT);
            }
            return true;
        }
        int segment = segmentAt(relX, relY);
        if (segment >= 0) {
            boolean wantContinuous = segment == 1;
            if (wantContinuous != m.continuous()) {
                sendButton(MunitionsBenchMenu.BUTTON_TOGGLE_CONTINUOUS);
            }
            return true;
        }
        return false;
    }

    /** 单次 / 连续 的命中段 (0 / 1), 不在上面返回 -1。段宽与各风格 seg 的算法一致。 */
    private int segmentAt(double relX, double relY) {
        int gap = theme().segGap();
        int bw = (SEG_W - gap) / 2;
        for (int i = 0; i < 2; i++) {
            if (inRect(relX, relY, SEG_X + i * (bw + gap), SEG_Y, bw, SEG_H)) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    private static MunitionsCaliber cardAt(MunitionsCaliber.Category category, double relX, double relY) {
        List<MunitionsCaliber> calibers = calibersOf(category);
        int pitch = cardPitch(calibers.size());
        for (int i = 0; i < calibers.size(); i++) {
            if (inRect(relX, relY, CARD_X + i * pitch, CARD_Y, pitch - 1, CARD_H)) {
                return calibers.get(i);
            }
        }
        return null;
    }

    // ================================================================== tooltips (preview hit() tips)

    @Nullable
    @Override
    protected List<Component> screenTooltip(double relX, double relY) {
        Model m = model();
        if (inRect(relX, relY, PARAM_X, PROPELLANT_TIP_Y, PARAM_W, PARAM_ROW_PITCH)) {
            return propellantTooltip(menu.isRefineUnlocked());
        }
        for (int i = 0; i < CATEGORY_ORDER.length; i++) {
            if (inRect(relX, relY, TAB_X + i * TAB_PITCH, TAB_Y, TAB_W, TAB_H)) {
                MunitionsCaliber.Category category = CATEGORY_ORDER[i];
                int minLevel = minUnlockLevel(category);
                if (m.level() >= minLevel) {
                    return null;
                }
                return List.of(title(Component.translatable(category.labelKey())),
                        red(tr("tip.need_level", minLevel)));
            }
        }
        MunitionsCaliber card = cardAt(m.viewed(), relX, relY);
        if (card != null) {
            boolean unlocked = MunitionsLevels.isCaliberUnlocked(m.level(), card);
            return List.of(title(caliberName(card)),
                    GunsmithUi.tip(tr("tip.unlock_level", card.unlockLevel()),
                            unlocked ? GunsmithUi.TIP_GRAY : GunsmithUi.TIP_RED),
                    gray(tr("tip.card_yield", MunitionsProduction.roundsPerBatch(card, m.level()),
                            card.sellPrice())));
        }
        // 手上拿着物品时原版不出格子提示, 料槽 / 产出槽的说明也跟着让路。
        boolean carrying = !menu.getCarried().isEmpty();
        if (!carrying) {
            for (int i = 0; i < MATERIAL_COUNT; i++) {
                if (inRect(relX, relY, MATERIAL_X[i] - 1, MATERIAL_Y[i] - 1, 18, 18)) {
                    int have = m.have()[i];
                    int need = m.need()[i];
                    return List.of(title(materialName(i)),
                            gray(tr("tip.material.per_batch", need)),
                            GunsmithUi.tip(tr("tip.material.have", have, have / need),
                                    have >= need ? GunsmithUi.TIP_GRAY : GunsmithUi.TIP_RED));
                }
            }
        }
        if (inRect(relX, relY, SIDE_PANEL_X, POWER_TIP_Y, SIDE_PANEL_W, POWER_TIP_H)) {
            return List.of(title(tr("tip.battery.title")),
                    gray(tr("tip.battery.value", GunsmithUi.formatCount(m.fe()), GunsmithUi.formatCount(m.feCap()))),
                    gray(tr("tip.battery.desc", GunsmithUi.formatCount(m.feBatch()))));
        }
        if (inRect(relX, relY, START_X, START_Y, START_W, START_H)) {
            return !m.running() && m.blocker() != null ? List.of(red(m.blocker())) : null;
        }
        int segment = segmentAt(relX, relY);
        if (segment == 0) {
            return List.of(title(modeLabel(false)), gray(tr("tip.single")));
        }
        if (segment == 1) {
            return List.of(title(modeLabel(true)), gray(tr("tip.continuous")));
        }
        if (!carrying && inRect(relX, relY, MunitionsBenchMenu.SLOT_OUTPUT_X - 1, MunitionsBenchMenu.SLOT_OUTPUT_Y - 1,
                18, 18)) {
            List<Component> lines = new ArrayList<>();
            lines.add(title(caliberName(m.shown())));
            lines.add(gray(tr("tip.output.buffered", GunsmithUi.formatCount(m.buffered()))));
            Slot output = containerSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT);
            if (output != null && output.hasItem()) {
                lines.add(gold(tr("tip.output.take", output.getItem().getCount())));
            }
            return lines;
        }
        return null;
    }

    private static List<Component> propellantTooltip(boolean refineUnlocked) {
        int unlockLevel = MunitionsConfig.REFINE_UNLOCK_LEVEL.get();
        int direct = MunitionsConfig.DIRECT_ROUNDS_PER_BATCH.get();
        int refined = MunitionsConfig.REFINED_ROUNDS_PER_BATCH.get();
        if (refineUnlocked) {
            return List.of(title(tr("tip.refine.title")), gray(tr("tip.refine.desc", unlockLevel, refined)));
        }
        return List.of(title(tr("tip.direct.title")), gray(tr("tip.direct.desc", unlockLevel, direct, refined)));
    }

    // ================================================================== helpers

    private static Component tr(String suffix, Object... args) {
        return Component.translatable(KEY + suffix, args);
    }

    private static Component title(Component text) {
        return GunsmithUi.tip(text, GunsmithUi.TIP_TITLE);
    }

    private static Component gray(Component text) {
        return GunsmithUi.tip(text, GunsmithUi.TIP_GRAY);
    }

    private static Component red(Component text) {
        return GunsmithUi.tip(text, GunsmithUi.TIP_RED);
    }

    private static Component gold(Component text) {
        return GunsmithUi.tip(text, GunsmithUi.TIP_GOLD);
    }

    /** 口径全名 (如 "7.62×39mm 步枪弹"); 没收录的口径退回短代号。 */
    private static Component caliberName(MunitionsCaliber caliber) {
        return Component.translatableWithFallback(KEY + "caliber." + caliber.name().toLowerCase(Locale.ROOT),
                caliber.shortLabel());
    }

    /** 页签上的短类别名 (页签只有 32px 宽; 提示框里用类别全名)。 */
    private static Component tabLabel(MunitionsCaliber.Category category) {
        return tr("tab." + category.name().toLowerCase(Locale.ROOT));
    }

    private static Component materialName(int i) {
        return tr("material." + MATERIAL_KEY[i]);
    }

    private static Component modeLabel(boolean continuous) {
        return Component.translatable(continuous
                ? "gui.miningdim.munitions.mode_continuous"
                : "gui.miningdim.munitions.mode_single");
    }

    /** 每发工费 = 每 10 发工费 / 10 (如 15 -> "1.5", 20 -> "2")。 */
    private static String workFeePerRound() {
        return BigDecimal.valueOf(MunitionsConfig.WORK_FEE_PER_TEN_ROUNDS.get(), 1)
                .stripTrailingZeros().toPlainString();
    }

    /** 容量这类整百万的数显示成 "32M", 其余同 {@link GunsmithUi#formatMegaFe}。 */
    private static String compactMega(long fe) {
        if (fe > 0 && fe % 1_000_000L == 0) {
            return (fe / 1_000_000L) + "M";
        }
        return GunsmithUi.formatMegaFe(fe);
    }

    private static float fraction(long value, long max) {
        if (max <= 0) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, value / (float) max));
    }

    /**
     * 按首选字号画, 超出 maxW 时缩小 (最小 0.4), 并按字号差把字压回原来的垂直中线。
     *
     * @return 实际画出的宽度
     */
    private static float fitText(GsPainter p, Component text, float x, float y, int color, float scale,
                                 float maxW, int flags) {
        float fitted = p.fitScale(text, Math.max(1.0F, maxW), scale, 0.4F, (flags & BOLD) != 0);
        return p.text(text, x, y + (scale - fitted) * 4.0F, color, fitted, flags);
    }

    private ItemStack ammoIcon(MunitionsCaliber caliber) {
        return ammoIcons.computeIfAbsent(caliber, c -> MunitionsAmmoFactory.materialize(c, 1));
    }

    private ItemStack materialGhost(int i) {
        if (materialGhosts == null) {
            materialGhosts = new ItemStack[]{
                    new ItemStack(ModMunitionsItems.PRIMER.get()),
                    new ItemStack(ModMunitionsItems.CASING.get()),
                    new ItemStack(ModMunitionsItems.BULLET_HEAD.get()),
                    new ItemStack(ModMunitionsItems.PROPELLANT.get())
            };
        }
        return materialGhosts[i];
    }

    /**
     * 锁定口径卡的内底色 (褪色图标的面纱色): 学园 / 工控取 card(LOCK) 的实心内底, 蓝图的锁定卡只有虚线框,
     * 取图纸底色。
     */
    private static int lockedCardInner(GunsmithTheme theme) {
        return switch (theme.style()) {
            case ACADEMY -> 0xF3F5F8;
            case INDUSTRIAL -> 0x171A1F;
            case BLUEPRINT -> 0x16427A;
        };
    }

    /** 各风格 slot() 的 16x16 槽内底色 (空料槽剪影的面纱色)。 */
    private static int slotInner(GunsmithTheme theme) {
        return switch (theme.style()) {
            case ACADEMY -> 0xE4E9F0;
            case INDUSTRIAL -> 0x101318;
            case BLUEPRINT -> 0x123A6C;
        };
    }

    private static int cardPitch(int count) {
        return count <= CARDS_AT_FULL_WIDTH ? CARD_PITCH : Math.max(9, CARD_AREA_W / count);
    }

    private static List<MunitionsCaliber> calibersOf(MunitionsCaliber.Category category) {
        return CALIBERS.getOrDefault(category, List.of());
    }

    private static int minUnlockLevel(MunitionsCaliber.Category category) {
        int min = Integer.MAX_VALUE;
        for (MunitionsCaliber caliber : calibersOf(category)) {
            min = Math.min(min, caliber.unlockLevel());
        }
        return min;
    }

    private static Map<MunitionsCaliber.Category, List<MunitionsCaliber>> calibersByCategory() {
        Map<MunitionsCaliber.Category, List<MunitionsCaliber>> map = new EnumMap<>(MunitionsCaliber.Category.class);
        for (MunitionsCaliber.Category category : MunitionsCaliber.Category.values()) {
            List<MunitionsCaliber> list = new ArrayList<>();
            for (MunitionsCaliber caliber : MunitionsCaliber.values()) {
                if (caliber.category() == category) {
                    list.add(caliber);
                }
            }
            map.put(category, Collections.unmodifiableList(list));
        }
        return map;
    }
}
