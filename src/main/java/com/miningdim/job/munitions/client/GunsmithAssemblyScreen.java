package com.miningdim.job.munitions.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.MunitionsConfig;
import com.miningdim.job.munitions.MunitionsLevels;
import com.miningdim.job.munitions.block.GunsmithAssemblyBenchBlockEntity;
import com.miningdim.job.munitions.client.style.GsCanvas;
import com.miningdim.job.munitions.client.style.GsPainter;
import com.miningdim.job.munitions.client.style.GunsmithStyledScreen;
import com.miningdim.job.munitions.client.style.GunsmithTheme;
import com.miningdim.job.munitions.client.style.GunsmithTheme.AssemblyColors;
import com.miningdim.job.munitions.client.style.GunsmithTheme.BarKind;
import com.miningdim.job.munitions.client.style.GunsmithTheme.ButtonKind;
import com.miningdim.job.munitions.client.style.GunsmithTheme.LampState;
import com.miningdim.job.munitions.client.style.GunsmithTheme.State;
import com.miningdim.job.munitions.client.style.GunsmithUi;
import com.miningdim.job.munitions.gunsmith.GunsmithAssemblyRecipe;
import com.miningdim.job.munitions.gunsmith.GunsmithBaseStats;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprint;
import com.miningdim.job.munitions.gunsmith.GunsmithGunDurability;
import com.miningdim.job.munitions.gunsmith.GunsmithGunStats;
import com.miningdim.job.munitions.gunsmith.GunsmithPartItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartVariant;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.job.munitions.gunsmith.GunsmithTaczBridge;
import com.miningdim.job.munitions.menu.GunsmithAssemblyMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.miningdim.job.munitions.client.style.GsPainter.BOLD;
import static com.miningdim.job.munitions.client.style.GsPainter.CENTER;
import static com.miningdim.job.munitions.client.style.GsPainter.RIGHT;

/**
 * 枪械组装台界面 (360x240), 逐项对应设计预览 drawAsm。与军火台、冲压机共用三种风格与标题栏右侧的风格按钮 (基类处理)。
 *
 * <p>布局 (GUI 像素):
 * <ul>
 *   <li>左 (8,30,84,112) "图纸 / 待修枪械": 输入槽 (15,45) + 枪名, 下面是装配清单 (每个部件: 放没放、品质、管哪项属性)
 *       或维修的换件要求;</li>
 *   <li>中 (99,30,164,112): 枪械展示窗 (103,55,157,62) 里画 TaCZ 剪影, 部件槽上下两排围着它, 每格一根引线指到枪身;</li>
 *   <li>右 (270,30,82,112) "属性预览" 或 "维修预览"; 左下 (8,146,84,86) 工费 / 耗时 / 等级 + 开工按钮;
 *       右下 (270,146,82,86) 成品格 (303,162) + 状态与进度; 中下是玩家背包 (100,148)。</li>
 * </ul>
 *
 * <p>判定与服务端同源 ({@link GunsmithAssemblyRecipe} / {@link GunsmithGunDurability} / {@link MunitionsConfig}),
 * 界面只做提前提示, 开工仍由服务端 {@code GunsmithAssemblyBenchBlockEntity} 独立复核并扣工费。开工后服务端照旧关掉界面,
 * 让玩家看机械臂; 重新打开时进度按菜单同步的剩余 tick 走。
 */
public final class GunsmithAssemblyScreen extends GunsmithStyledScreen<GunsmithAssemblyMenu> {

    /** 基类要求的非空底图 (不会被画出来, 底图由风格运行时生成)。 */
    private static final ResourceLocation FALLBACK_BG =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/container/gunsmith_press.png");
    private static final ResourceLocation BLUEPRINT_HINT =
            new ResourceLocation(MiningConstants.MODID, "textures/item/gunsmith_blueprint_ar.png");
    private static final int BLUEPRINT_HINT_TEX = 256;

    private static final String K = "screen.miningdim.gunsmith_assembly.";

    // ------------------------------------------------------------------ layout (GUI px)

    private static final int LEFT_X = 8;
    private static final int LEFT_Y = 30;
    private static final int LEFT_W = 84;
    private static final int LEFT_H = 112;
    private static final int NAME_X = 36;
    private static final int CHECK_X = 12;
    private static final int CHECK_Y = 70;
    private static final int CHECK_W = 76;
    private static final int CHECK_STEP = 11;

    private static final int CENTER_X = 99;
    private static final int CENTER_Y = 30;
    private static final int CENTER_W = 164;
    private static final int CENTER_H = 112;
    private static final int DISP_X = 103;
    private static final int DISP_Y = 55;
    private static final int DISP_W = 157;
    private static final int DISP_H = 62;
    /** 部件槽右边标签的可用宽度 (到下一列槽框之前)。 */
    private static final int SLOT_LABEL_W = 19;

    private static final int STATS_X = 270;
    private static final int STATS_Y = 30;
    private static final int STATS_W = 82;
    private static final int STATS_H = 112;
    private static final int STAT_X = 276;
    private static final int STAT_R = 346;
    private static final int STAT_VALUE_R = 327;
    private static final int STAT_Y = 43;
    private static final int STAT_STEP = 9;
    private static final int DUR_BAR_X = 276;
    private static final int DUR_BAR_Y = 55;
    private static final int DUR_BAR_W = 70;
    private static final int DUR_BAR_H = 5;

    private static final int ORDER_X = 8;
    private static final int ORDER_Y = 146;
    private static final int ORDER_W = 84;
    private static final int ORDER_H = 86;
    private static final int START_X = 14;
    private static final int START_Y = 202;
    private static final int START_W = 72;
    private static final int START_H = 22;

    private static final int OUT_PANEL_X = 270;
    private static final int OUT_PANEL_Y = 146;
    private static final int OUT_PANEL_W = 82;
    private static final int OUT_PANEL_H = 86;

    /** 属性预览的十行 (第十一行"综合"单独画在分隔线下)。 */
    private static final String[] STAT_ROWS = {
            "damage", "headshot", "range", "fire_rate", "recoil", "vertical_recoil", "spread", "ammo_speed",
            "armor_ignore", "ads"};

    /** 剪影在 HUD 图里的横向源区间 {u, uW}: 手枪只占右半边, 裁出来放大; 其余整张。 */
    private static final int[] CROP_FULL = {0, GunsmithUi.HUD_W};
    private static final int[] CROP_PISTOL = {168, 216};

    /** 引线落点 (剪影矩形内的比例坐标), 各平台按 HUD 图里枪的轮廓给, 保证同一排的横线互不重叠。 */
    private static final Map<GunsmithPlatform, Map<GunsmithPressPart, float[]>> ANCHORS = anchors();

    static {
        // 客户端 Screen 进不了 GameTest (GameTest 跑在专用服务端), 所以"会在 super.mouseClicked 之前吞掉左键的控件
        // 不压任何槽位判定盒"做成类加载期自检 (与冲压机界面同一做法)。
        assertClickTargetsClearOfSlots();
    }

    private enum Mode {
        IDLE, BUILD, REPAIR
    }

    private enum PartState {
        OK, WRONG, EMPTY
    }

    /** 一格部件的状态; data 在 EMPTY 或读不出部件数据时为 null。 */
    private record PartView(PartState state, @Nullable GunsmithPartItem.PartData data) {
    }

    /**
     * 本帧从菜单读出的全部状态 (渲染、提示、点击各算一次, 都很便宜)。
     *
     * @param parts        当前可见的部件格 (图纸要的全部部件, 或维修件一格), 按槽位 上排→下排、左→右 排好
     * @param gunId        展示与预览用的枪 (装配时已按枪机型号换成连发版); IDLE 为 null
     * @param newDurability 装配: 成品出厂耐久 (0 = 算不出)
     * @param durabilityMultiplier 装配: 组件型号的耐久倍率连乘 (&lt; 1 时在成品栏写出来)
     */
    private record Snapshot(Mode mode, ItemStack input, @Nullable GunsmithBlueprint blueprint,
                            @Nullable GunsmithGunDurability.Managed repair,
                            @Nullable GunsmithGunDurability.RepairPreview repairPreview,
                            @Nullable GunsmithPlatform platform, List<GunsmithPressPart> parts,
                            Map<GunsmithPressPart, PartView> views, @Nullable ResourceLocation gunId,
                            @Nullable GunsmithTaczClientData client, @Nullable GunsmithBaseStats base,
                            @Nullable GunsmithAssemblyRecipe.Preview preview,
                            int newDurability, double durabilityMultiplier) {

        boolean allPartsOk() {
            for (GunsmithPressPart part : parts) {
                if (views.get(part).state() != PartState.OK) {
                    return false;
                }
            }
            return true;
        }
    }

    public GunsmithAssemblyScreen(GunsmithAssemblyMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, FALLBACK_BG);
    }

    // ================================================================== level

    /** 等级取服务端经菜单数据槽推来的实时值 (与服务端开工判定同一个数); 首次同步前退回本地镜像。 */
    @Override
    protected int ownerLevel() {
        int synced = menu.ownerMunitionsLevel();
        if (synced > 0) {
            return MunitionsLevels.clampLevel(synced);
        }
        return super.ownerLevel();
    }

    // ================================================================== header

    @Override
    protected GunsmithTheme.Header header() {
        Snapshot s = snapshot();
        String code = switch (s.mode()) {
            case BUILD -> s.blueprint() != null ? s.blueprint().templateId().toUpperCase(Locale.ROOT) : "BUILD";
            case REPAIR -> "REPAIR";
            case IDLE -> "IDLE";
        };
        return new GunsmithTheme.Header(
                Component.translatable(K + "header.title"),
                Component.translatable(K + "header.en"),
                null,
                Component.translatable(K + "subtitle"),
                "WOK-ASM-" + code,
                menu.isAnimating(),
                ownerLevel());
    }

    // ================================================================== main layer

    @Override
    protected void renderScreen(GsPainter p, GunsmithTheme theme) {
        Snapshot s = snapshot();
        int level = ownerLevel();
        boolean running = menu.isAnimating();
        float prog = running ? progress() : 0.0F;
        renderInput(p, theme, s, running);
        renderJig(p, theme, s, running, prog);
        if (s.mode() == Mode.REPAIR) {
            renderRepairStats(p, theme, s);
        } else {
            renderStats(p, theme, s, running);
        }
        renderOrder(p, theme, s, level, running);
        renderOutput(p, theme, s, running, prog);
        drawPlayerInventorySlots(p, theme);
    }

    private void renderInput(GsPainter p, GunsmithTheme t, Snapshot s, boolean running) {
        GunsmithTheme.Palette c = t.palette();
        t.panel(p, LEFT_X, LEFT_Y, LEFT_W, LEFT_H,
                Component.translatable(K + (s.mode() == Mode.REPAIR ? "repair_gun" : "blueprint")));
        int ix = GunsmithAssemblyMenu.SLOT_BLUEPRINT_X;
        int iy = GunsmithAssemblyMenu.SLOT_BLUEPRINT_Y;
        t.slot(p, ix, iy);
        if (s.input().isEmpty()) {
            p.blit(BLUEPRINT_HINT, ix, iy, 16, 16, 0.0F, 0.0F, BLUEPRINT_HINT_TEX, BLUEPRINT_HINT_TEX,
                    BLUEPRINT_HINT_TEX, BLUEPRINT_HINT_TEX, 0.18F);
        }
        float nameW = LEFT_X + LEFT_W - 6 - NAME_X;
        if (s.mode() == Mode.IDLE) {
            Component name = Component.translatable(K + (running ? "input.busy" : "input.empty"));
            Component sub = Component.translatable(K + (running ? "input.busy_sub" : "input.empty_sub"));
            p.text(name, NAME_X, 45.5F, c.text(), p.fitScale(name, nameW, 0.66F, 0.4F, true), BOLD);
            p.text(sub, NAME_X, 55, c.muted(), p.fitScale(sub, nameW, 0.5F, 0.35F, false), 0);
        } else {
            Component name = gunName(s);
            float ns = p.fitScale(name, nameW, 0.72F, 0.45F, true);
            p.text(GunsmithUi.ellipsize(p, name, nameW, ns, true), NAME_X, 45.5F + (0.72F - ns) * 4.0F, c.text(), ns, BOLD);
            Component sub = s.mode() == Mode.REPAIR
                    ? Component.translatable(K + "input.repairs", s.repair().state().repairs())
                    : Component.translatable(K + "input.parts", Component.translatable(s.platform().labelKey()),
                    s.parts().size());
            float ss = p.fitScale(sub, nameW, 0.5F, 0.35F, false);
            p.text(GunsmithUi.ellipsize(p, sub, nameW, ss, false), NAME_X, 55, c.muted(), ss, 0);
        }
        t.sep(p, 14, 66, 72);

        switch (s.mode()) {
            case IDLE -> {
                String[] help = {"blueprint", "gun", "parts"};
                for (int i = 0; i < help.length; i++) {
                    int y = 72 + i * 20;
                    Component head = Component.translatable(K + "help." + help[i] + ".title");
                    Component body = Component.translatable(K + "help." + help[i] + ".desc");
                    p.text(head, 14, y, c.text(), p.fitScale(head, 72.0F, 0.58F, 0.35F, true), BOLD);
                    p.text(body, 14, y + 8, c.muted(), p.fitScale(body, 72.0F, 0.52F, 0.35F, false), 0);
                }
            }
            case BUILD -> {
                for (int i = 0; i < s.parts().size(); i++) {
                    renderCheckRow(p, t, s, s.parts().get(i), checkRowY(i));
                }
            }
            case REPAIR -> renderRepairRequirement(p, t, s);
        }
    }

    private void renderCheckRow(GsPainter p, GunsmithTheme t, Snapshot s, GunsmithPressPart part, int y) {
        GunsmithTheme.Palette c = t.palette();
        PartView view = s.views().get(part);
        String mark = switch (view.state()) {
            case OK -> "✓";
            case WRONG -> "✗";
            case EMPTY -> "·";
        };
        int markColor = switch (view.state()) {
            case OK -> c.good();
            case WRONG -> c.bad();
            case EMPTY -> c.dim();
        };
        p.text(mark, 14, y, markColor, 0.6F, 0);
        Component name = Component.translatable(part.slotKey());
        float nameScale = p.fitScale(name, 12.0F, 0.58F, 0.35F, false);
        p.text(name, 21, y + (0.58F - nameScale) * 4.0F, c.text(), nameScale, 0);
        Component status;
        int statusColor;
        float statusScale;
        int flags = RIGHT;
        if (view.state() == PartState.OK && view.data() != null) {
            status = Component.translatable(view.data().quality().labelKey());
            statusColor = t.qColor(view.data().quality());
            statusScale = 0.56F;
            flags |= BOLD;
        } else {
            status = Component.translatable(K + (view.state() == PartState.WRONG ? "check.wrong" : "check.missing"));
            statusColor = view.state() == PartState.WRONG ? c.bad() : c.dim();
            statusScale = 0.5F;
        }
        float sw = p.text(status, 86, y + (view.state() == PartState.OK ? 0.0F : 0.5F), statusColor,
                p.fitScale(status, 30.0F, statusScale, 0.35F, (flags & BOLD) != 0), flags);
        Component affects = affectsText(s.platform(), part);
        float affW = 86 - sw - 3 - 34;
        if (affW > 4) {
            float as = p.fitScale(affects, affW, 0.46F, 0.3F, false);
            p.text(GunsmithUi.ellipsize(p, affects, affW, as, false), 34, y + 1, c.dim(), as, 0);
        }
    }

    private void renderRepairRequirement(GsPainter p, GunsmithTheme t, Snapshot s) {
        GunsmithTheme.Palette c = t.palette();
        GunsmithPressPart need = s.repairPreview().requiredPart();
        PartView view = s.views().get(need);
        boolean ok = view.state() == PartState.OK;
        p.text(ok ? "✓" : "·", 14, 70, ok ? c.good() : c.dim(), 0.6F, 0);
        p.text(Component.translatable(need.slotKey()), 21, 70, c.text(), 0.58F, 0);
        p.text(Component.translatable(K + "check.repair_part"), 34, 71, c.dim(), 0.46F, 0);
        if (ok && view.data() != null) {
            p.text(Component.translatable(view.data().quality().labelKey()), 86, 70, t.qColor(view.data().quality()),
                    0.56F, RIGHT | BOLD);
        } else {
            p.text(Component.translatable(K + (view.state() == PartState.WRONG ? "check.wrong" : "check.missing")),
                    86, 70.5F, view.state() == PartState.WRONG ? c.bad() : c.dim(), 0.5F, RIGHT);
        }
        t.sep(p, 14, 82, 72);
        Component title = Component.translatable(K + "repair.req_title");
        p.text(title, 14, 86, c.muted(), p.fitScale(title, 72.0F, 0.52F, 0.35F, false), 0);
        Component same = Component.translatable(K + "repair.req_same");
        p.text(same, 14, 95, c.text(), p.fitScale(same, 72.0F, 0.52F, 0.35F, false), 0);
        GunsmithGunStats.PartSummary installed = GunsmithGunDurability.installedRepairPart(s.repair().stats());
        if (installed != null) {
            Component quality = Component.translatable(installed.quality().labelKey());
            Component line = Component.translatable(K + "repair.req_quality", quality);
            float qs = p.fitScale(line, 72.0F, 0.52F, 0.35F, false);
            float lead = p.text(Component.translatable(K + "repair.req_quality", ""), 14, 104, c.text(), qs, 0);
            p.text(quality, 14 + lead, 104, t.qColor(installed.quality()), qs, BOLD);
        }
        Component n1 = Component.translatable(K + "repair.note_consumed");
        Component n2 = Component.translatable(K + "repair.note_loss");
        p.text(n1, 14, 116, c.dim(), p.fitScale(n1, 72.0F, 0.48F, 0.3F, false), 0);
        p.text(n2, 14, 124, c.dim(), p.fitScale(n2, 72.0F, 0.48F, 0.3F, false), 0);
    }

    private void renderJig(GsPainter p, GunsmithTheme t, Snapshot s, boolean running, float prog) {
        GunsmithTheme.Palette c = t.palette();
        AssemblyColors ac = t.assembly();
        t.panel(p, CENTER_X, CENTER_Y, CENTER_W, CENTER_H, null);
        t.gunDisplay(p, DISP_X, DISP_Y, DISP_W, DISP_H);

        if (s.client() != null && s.platform() != null) {
            int[] crop = crop(s.platform());
            int[] r = gunRect(crop);
            float alpha = running ? 0.55F + 0.45F * prog
                    : (s.mode() == Mode.BUILD ? s.allPartsOk() : blockReason(s, ownerLevel()) == null) ? 1.0F : 0.45F;
            t.gunSilhouette(p, s.client().hudTexture(), r[0], r[1], r[2], r[3], crop[0], crop[1], alpha);
            Map<GunsmithPressPart, float[]> anchors = ANCHORS.get(s.platform());
            if (running) {
                int sx = r[0] + Math.round(r[2] * prog);
                p.rect(sx, DISP_Y + 2, 1, DISP_H - 4, ac.sweep());
                p.rect(sx - 3, DISP_Y + 2, 3, DISP_H - 4, SWEEP_GLOW);
                if (!s.parts().isEmpty()) {
                    int n = s.parts().size();
                    float[] a = anchors.get(s.parts().get((int) Math.floorMod((long) Math.floor(prog * n * 2), (long) n)));
                    int wx = r[0] + Math.round(a[0] * r[2]);
                    int wy = r[1] + Math.round(a[1] * r[3]);
                    long ph = p.now() / 70L;
                    p.batch(() -> {
                        for (int i = 0; i < 5; i++) {
                            p.rect(wx - 3 + (int) Math.floorMod(ph * 5 + i * 7L, 7L),
                                    wy - 3 + (int) Math.floorMod(ph * 3 + i * 5L, 7L), 1, 1,
                                    i % 2 == 0 ? ac.weldA() : ac.weldB());
                        }
                    });
                }
            } else {
                p.batch(() -> {
                    for (GunsmithPressPart part : s.parts()) {
                        drawWire(p, t, s, part, anchors.get(part), r);
                    }
                });
            }
        } else if (s.mode() != Mode.IDLE && s.gunId() != null) {
            Component missing = Component.translatable(K + "tacz_data_unavailable", s.gunId().toString());
            p.text(missing, DISP_X + DISP_W / 2.0F, DISP_Y + 27, c.bad(),
                    p.fitScale(missing, DISP_W - 8, 0.55F, 0.3F, false), CENTER);
        } else {
            Component l1 = Component.translatable(K + (running ? "display.busy" : "display.empty1"));
            p.text(l1, DISP_X + DISP_W / 2.0F, DISP_Y + 23, c.dim(), p.fitScale(l1, DISP_W - 8, 0.62F, 0.35F, false), CENTER);
            if (!running) {
                Component l2 = Component.translatable(K + "display.empty2");
                p.text(l2, DISP_X + DISP_W / 2.0F, DISP_Y + 33, c.dim(),
                        p.fitScale(l2, DISP_W - 8, 0.52F, 0.35F, false), CENTER);
            }
        }

        p.batch(() -> {
            for (GunsmithPressPart part : s.parts()) {
                t.slot(p, GunsmithAssemblyMenu.partSlotX(part), GunsmithAssemblyMenu.partSlotY(part));
            }
        });
        for (GunsmithPressPart part : s.parts()) {
            int sx = GunsmithAssemblyMenu.partSlotX(part);
            int sy = GunsmithAssemblyMenu.partSlotY(part);
            PartView view = s.views().get(part);
            Component name = Component.translatable(part.slotKey());
            boolean ok = view.state() == PartState.OK;
            float ns = p.fitScale(name, SLOT_LABEL_W, 0.55F, 0.32F, ok);
            p.text(GunsmithUi.ellipsize(p, name, SLOT_LABEL_W, ns, ok), sx + 19, sy + 1.5F,
                    view.state() == PartState.EMPTY ? c.muted() : c.text(), ns, ok ? BOLD : 0);
            Component sub;
            int subColor;
            if (ok && view.data() != null) {
                sub = Component.translatable(view.data().quality().labelKey());
                subColor = t.qColor(view.data().quality());
            } else {
                sub = Component.translatable(K + (view.state() == PartState.WRONG ? "slot.wrong" : "slot.empty"));
                subColor = view.state() == PartState.WRONG ? c.bad() : c.dim();
            }
            float ss = p.fitScale(sub, SLOT_LABEL_W, 0.5F, 0.3F, false);
            p.text(GunsmithUi.ellipsize(p, sub, SLOT_LABEL_W, ss, false), sx + 19, sy + 9.5F, subColor, ss, 0);
        }
    }

    private static final int SWEEP_GLOW = GsCanvas.rgba(255, 255, 255, 0.08D);

    /** 槽 → 总线 → 枪身落点的折线 (上排的总线贴展示窗上沿, 下排贴下沿), 没放对的画虚线。 */
    private static void drawWire(GsPainter p, GunsmithTheme t, Snapshot s, GunsmithPressPart part,
                                 @Nullable float[] anchor, int[] r) {
        float[] a = anchor != null ? anchor : new float[]{0.5F, 0.5F};
        int sx = GunsmithAssemblyMenu.partSlotX(part);
        int sy = GunsmithAssemblyMenu.partSlotY(part);
        boolean top = sy < DISP_Y;
        PartView view = s.views().get(part);
        boolean ok = view.state() == PartState.OK && view.data() != null;
        int color = t.wire(ok ? view.data().quality() : null, ok);
        int ax = r[0] + Math.round(a[0] * r[2]);
        int ay = r[1] + Math.round(a[1] * r[3]);
        int cx = sx + 8;
        int y0 = top ? sy + 17 : sy - 2;
        int bus = top ? DISP_Y + 3 : DISP_Y + DISP_H - 4;
        vline(p, cx, y0, bus, color, !ok);
        hline(p, cx, ax, bus, color, !ok);
        vline(p, ax, bus, ay, color, !ok);
        p.rect(ax - 1, ay - 1, 3, 3, ok ? t.assembly().dot() : color);
    }

    private static void vline(GsPainter p, int x, int ya, int yb, int color, boolean dashed) {
        int a = Math.min(ya, yb);
        int b = Math.max(ya, yb);
        if (!dashed) {
            p.rect(x, a, 1, b - a + 1, color);
            return;
        }
        for (int y = a; y <= b; y += 3) {
            p.rect(x, y, 1, Math.min(2, b - y + 1), color);
        }
    }

    private static void hline(GsPainter p, int xa, int xb, int y, int color, boolean dashed) {
        int a = Math.min(xa, xb);
        int b = Math.max(xa, xb);
        if (!dashed) {
            p.rect(a, y, b - a + 1, 1, color);
            return;
        }
        for (int x = a; x <= b; x += 3) {
            p.rect(x, y, Math.min(2, b - x + 1), 1, color);
        }
    }

    private void renderStats(GsPainter p, GunsmithTheme t, Snapshot s, boolean running) {
        GunsmithTheme.Palette c = t.palette();
        t.panel(p, STATS_X, STATS_Y, STATS_W, STATS_H, Component.translatable(K + "stats"));
        GunsmithAssemblyRecipe.Preview pv = running ? null : s.preview();
        GunsmithBaseStats base = s.base();
        Map<String, GunsmithPressPart> sources = s.platform() != null
                ? GunsmithAssemblyRecipe.statSourceParts(s.platform()) : Map.of();
        for (int i = 0; i < STAT_ROWS.length; i++) {
            String id = STAT_ROWS[i];
            int y = statRowY(i);
            Component label = Component.translatable(K + "stat_short." + id);
            p.text(label, STAT_X, y, c.muted(), p.fitScale(label, 24.0F, 0.55F, 0.35F, false), 0);
            if (pv == null || base == null) {
                p.text("—", STAT_R, y, c.dim(), 0.55F, RIGHT);
                continue;
            }
            String value = null;
            double delta;
            boolean lowerBetter = false;
            boolean noSource = false;
            switch (id) {
                case "damage" -> {
                    value = GunsmithUi.format2(pv.damage());
                    delta = relative(pv.damage(), base.damage());
                }
                case "headshot" -> {
                    value = "x" + GunsmithUi.format2(pv.headshot());
                    delta = relative(pv.headshot(), base.headshot());
                }
                case "range" -> {
                    noSource = !sources.containsKey("range");
                    value = String.format(Locale.ROOT, "%.1fm", noSource ? base.effectiveRange() : pv.effectiveRange());
                    delta = relative(pv.effectiveRange(), base.effectiveRange());
                }
                case "fire_rate" -> delta = pv.fireRateChange();
                case "recoil" -> {
                    delta = pv.recoilChange();
                    lowerBetter = true;
                }
                case "vertical_recoil" -> {
                    delta = pv.verticalRecoilChange();
                    lowerBetter = true;
                }
                case "spread" -> {
                    delta = pv.spreadChange();
                    lowerBetter = true;
                }
                case "ammo_speed" -> delta = pv.ammoSpeedChange();
                case "armor_ignore" -> delta = pv.armorIgnoreChange();
                default -> {
                    value = String.format(Locale.ROOT, "%.3fs", pv.adsTime());
                    delta = relative(pv.adsTime(), base.adsTime());
                    lowerBetter = true;
                }
            }
            String deltaText = noSource ? "—" : String.format(Locale.ROOT, "%+.1f%%", delta);
            int deltaColor = noSource || Math.abs(delta) < 0.05D ? c.dim()
                    : (delta < 0.0D) == lowerBetter ? c.good() : c.bad();
            if (value != null) {
                p.text(value, STAT_VALUE_R, y, c.text(), 0.55F, RIGHT);
                p.text(deltaText, STAT_R, y + 0.5F, deltaColor, 0.48F, RIGHT);
            } else {
                p.text(deltaText, STAT_R, y, deltaColor, 0.55F, RIGHT);
            }
        }
        t.sep(p, STAT_X, 133, 70);
        p.text(Component.translatable(K + "stat_short.overall"), STAT_X, 135.5F, c.muted(), 0.55F, 0);
        if (pv != null) {
            p.text("x" + String.format(Locale.ROOT, "%.3f", pv.average()), STAT_R, 135.5F, c.accent(), 0.58F, RIGHT | BOLD);
        } else {
            p.text("—", STAT_R, 135.5F, c.dim(), 0.58F, RIGHT);
        }
    }

    private void renderRepairStats(GsPainter p, GunsmithTheme t, Snapshot s) {
        GunsmithTheme.Palette c = t.palette();
        t.panel(p, STATS_X, STATS_Y, STATS_W, STATS_H, Component.translatable(K + "repair_stats"));
        GunsmithGunDurability.State state = s.repair().state();
        GunsmithGunDurability.RepairPreview rp = s.repairPreview();
        boolean available = rp.available();
        p.text(Component.translatable(K + "dur.current"), STAT_X, 45, c.muted(), 0.56F, 0);
        p.text(GunsmithUi.formatCount(state.current()) + " / " + GunsmithUi.formatCount(state.maximum()),
                STAT_R, 45, c.text(), 0.56F, RIGHT);
        drawDurabilityBar(p, t, state, available ? rp.nextMaximum() : -1);
        int floor = GunsmithGunDurability.minimumMaximum(state.originalMaximum());
        String[] keys = {"dur.repairs", "dur.next", "dur.loss", "dur.restore", "dur.floor"};
        Component[] values = {
                Component.translatable(K + "dur.repairs_value", state.repairs()),
                Component.literal(available ? GunsmithUi.formatCount(rp.nextMaximum()) : "—"),
                Component.literal(available ? "-" + GunsmithUi.formatCount(state.maximum() - rp.nextMaximum()) : "—"),
                Component.literal(available ? "+" + GunsmithUi.formatCount(rp.restored()) : "—"),
                Component.literal(GunsmithUi.formatCount(floor))};
        int[] colors = {c.text(), c.text(), available ? c.bad() : c.dim(), available ? c.good() : c.dim(), c.muted()};
        for (int i = 0; i < keys.length; i++) {
            int y = 67 + i * 10;
            Component k = Component.translatable(K + keys[i]);
            p.text(k, STAT_X, y, c.muted(), p.fitScale(k, 40.0F, 0.55F, 0.35F, false), 0);
            p.text(values[i], STAT_R, y, colors[i], 0.56F, RIGHT);
            if (i < keys.length - 1) {
                t.sep(p, STAT_X, y + 8, 70);
            }
        }
        Component status = Component.translatable(repairStatusKey(rp.status()));
        p.text(status, STAT_X, 124, available ? c.good() : c.bad(), p.fitScale(status, 70.0F, 0.56F, 0.35F, true), BOLD);
        Component note = Component.translatable(K + "dur.floor_note",
                Math.round(MunitionsConfig.GUN_REPAIR_MINIMUM_RATIO.get() * 100.0D) + "%");
        p.text(note, STAT_X, 133, c.dim(), p.fitScale(note, 70.0F, 0.44F, 0.3F, false), 0);
    }

    /** 耐久条: 全长 = 出厂上限; 最暗 = 已损失的上限, 槽 = 当前上限, 绿 = 当前耐久, 红斜线 = 这次维修要扣的上限。 */
    private static void drawDurabilityBar(GsPainter p, GunsmithTheme t, GunsmithGunDurability.State state, int next) {
        AssemblyColors ac = t.assembly();
        double k = DUR_BAR_W / (double) state.originalMaximum();
        int maxW = (int) Math.round(state.maximum() * k);
        int curW = (int) Math.round(state.current() * k);
        p.batch(() -> {
            p.rect(DUR_BAR_X, DUR_BAR_Y, DUR_BAR_W, DUR_BAR_H, ac.durGone());
            p.rect(DUR_BAR_X, DUR_BAR_Y, maxW, DUR_BAR_H, ac.durTrack());
            if (next >= 0 && next < state.maximum()) {
                int from = (int) Math.round(next * k);
                for (int col = from; col < maxW; col++) {
                    for (int row = 0; row < DUR_BAR_H; row++) {
                        if ((col + row) % 3 == 0) {
                            p.rect(DUR_BAR_X + col, DUR_BAR_Y + row, 1, 1, ac.durLose());
                        }
                    }
                }
            }
            p.rect(DUR_BAR_X, DUR_BAR_Y, curW, DUR_BAR_H, ac.durCurrent());
            if (next >= 0) {
                p.rect(DUR_BAR_X + (int) Math.round(next * k) - 1, DUR_BAR_Y - 1, 1, DUR_BAR_H + 2, ac.durLose());
            }
        });
    }

    private void renderOrder(GsPainter p, GunsmithTheme t, Snapshot s, int level, boolean running) {
        GunsmithTheme.Palette c = t.palette();
        boolean repair = s.mode() == Mode.REPAIR;
        t.panel(p, ORDER_X, ORDER_Y, ORDER_W, ORDER_H, Component.translatable(K + (repair ? "repair" : "assemble")));
        long fee = fee(repair);
        int need = unlockLevel(repair);
        p.text(Component.translatable(K + "fee"), 14, 160, c.muted(), 0.58F, 0);
        p.text(Component.translatable(K + "fee_value", GunsmithUi.formatCount(fee)), 86, 160, c.text(), 0.6F, RIGHT);
        t.sep(p, 14, 169, 72);
        p.text(Component.translatable(K + "time"), 14, 172, c.muted(), 0.58F, 0);
        p.text(GunsmithUi.formatTicks(GunsmithAssemblyBenchBlockEntity.ASSEMBLY_DURATION_TICKS), 86, 172, c.text(), 0.6F, RIGHT);
        t.sep(p, 14, 181, 72);
        p.text(Component.translatable(K + "level"), 14, 184, c.muted(), 0.58F, 0);
        Component gate = Component.translatable(K + (level >= need ? "level_ok" : "level_locked"), need);
        p.text(gate, 86, 184, level >= need ? c.text() : c.bad(), p.fitScale(gate, 48.0F, 0.58F, 0.35F, false), RIGHT);

        Component why = blockReason(s, level);
        State state = why != null ? State.OFF : p.hov(START_X, START_Y, START_W, START_H) ? State.HOVER : State.IDLE;
        Component label = Component.translatable(K + (running ? (s.mode() == Mode.BUILD ? "assembling" : "start.working")
                : repair ? "start.repair" : "start.assemble"));
        t.btn(p, START_X, START_Y, START_W, START_H, label, ButtonKind.GO, state);
    }

    private void renderOutput(GsPainter p, GunsmithTheme t, Snapshot s, boolean running, float prog) {
        GunsmithTheme.Palette c = t.palette();
        t.panel(p, OUT_PANEL_X, OUT_PANEL_Y, OUT_PANEL_W, OUT_PANEL_H, Component.translatable(K + "output"));
        t.slotOut(p, GunsmithAssemblyMenu.SLOT_OUTPUT_X, GunsmithAssemblyMenu.SLOT_OUTPUT_Y);
        ItemStack output = outputStack();
        boolean hasOutput = !output.isEmpty();
        t.lamp(p, 277, 188, running ? LampState.RUN : hasOutput ? LampState.DONE : LampState.IDLE);
        Component status = Component.translatable(K + (running ? (s.mode() == Mode.BUILD ? "assembling" : "status.working")
                : hasOutput ? "status.done" : "status.idle"));
        p.text(status, 286, 187.5F, running ? c.accent() : hasOutput ? c.good() : c.muted(),
                p.fitScale(status, 36.0F, 0.62F, 0.4F, true), BOLD);
        if (running) {
            p.text(GunsmithUi.formatTicks(menu.animationRemainingTicks()), STAT_R, 187.5F, c.text(), 0.62F, RIGHT);
        }

        Component info = null;
        Component variantNote = null;
        if (hasOutput) {
            GunsmithGunDurability.Managed made = GunsmithGunDurability.tryManaged(output);
            if (made != null) {
                info = Component.translatable(K + "info.output", GunsmithUi.formatCount(made.state().current()),
                        GunsmithUi.formatCount(made.state().maximum()));
            }
        } else if (!running && s.mode() == Mode.BUILD && s.newDurability() > 0) {
            info = Component.translatable(K + "info.new", GunsmithUi.formatCount(s.newDurability()));
            if (s.durabilityMultiplier() < 0.999D) {
                variantNote = Component.translatable(K + "info.variant",
                        String.format(Locale.ROOT, "%.2f", s.durabilityMultiplier()));
            }
        } else if (!running && s.mode() == Mode.REPAIR && s.repairPreview().available()) {
            String next = GunsmithUi.formatCount(s.repairPreview().nextMaximum());
            info = Component.translatable(K + "info.repaired", next, next);
        }
        if (info != null) {
            p.text(info, 276, 198, c.text(), p.fitScale(info, 70.0F, 0.54F, 0.35F, false), 0);
        }
        if (variantNote != null) {
            p.text(variantNote, 276, 206, c.accent(), p.fitScale(variantNote, 70.0F, 0.46F, 0.3F, false), 0);
        }
        t.bar(p, 276, 213, 70, 4, running ? prog : hasOutput ? 1.0F : 0.0F,
                hasOutput ? BarKind.FE : BarKind.PROG, running);
        Component foot = Component.translatable(K + (hasOutput ? "footer.take_first" : "footer.closes"));
        p.text(foot, 276, 221, c.dim(), p.fitScale(foot, 70.0F, 0.46F, 0.3F, false), 0);
    }

    // ================================================================== tooltips

    @Nullable
    @Override
    protected List<Component> screenTooltip(double mx, double my) {
        Snapshot s = snapshot();
        int level = ownerLevel();
        boolean carrying = !menu.getCarried().isEmpty();

        if (!carrying && s.input().isEmpty() && GunsmithUi.inRect(mx, my, GunsmithAssemblyMenu.SLOT_BLUEPRINT_X - 1,
                GunsmithAssemblyMenu.SLOT_BLUEPRINT_Y - 1, 18, 18)) {
            return List.of(tip(Component.translatable(K + "input_tip.title"), GunsmithUi.TIP_TITLE),
                    tip(Component.translatable(K + "input_tip.blueprint"), GunsmithUi.TIP_GRAY),
                    tip(Component.translatable(K + "input_tip.gun"), GunsmithUi.TIP_GRAY));
        }

        for (GunsmithPressPart part : s.parts()) {
            int sx = GunsmithAssemblyMenu.partSlotX(part);
            int sy = GunsmithAssemblyMenu.partSlotY(part);
            if (!carrying && s.views().get(part).state() == PartState.EMPTY
                    && GunsmithUi.inRect(mx, my, sx - 1, sy - 1, 18, 18)) {
                Component role = s.mode() == Mode.REPAIR
                        ? Component.translatable(K + "part_tip.repair")
                        : Component.translatable(K + "part_tip.affects", affectsText(s.platform(), part));
                return List.of(tip(Component.translatable(part.slotKey()), GunsmithUi.TIP_TITLE),
                        tip(role, GunsmithUi.TIP_GRAY),
                        tip(Component.translatable(K + "part_tip.hint", Component.translatable(s.platform().labelKey())),
                                GunsmithUi.TIP_GRAY));
            }
        }

        if (s.mode() == Mode.BUILD) {
            for (int i = 0; i < s.parts().size(); i++) {
                if (GunsmithUi.inRect(mx, my, CHECK_X, checkRowY(i) - 1, CHECK_W, 10)) {
                    return checkRowTooltip(s, s.parts().get(i));
                }
            }
        }

        if (s.mode() == Mode.REPAIR) {
            if (GunsmithUi.inRect(mx, my, DUR_BAR_X - 2, DUR_BAR_Y - 3, DUR_BAR_W + 4, DUR_BAR_H + 6)) {
                return List.of(tip(Component.translatable(K + "dur_tip.title"), GunsmithUi.TIP_TITLE),
                        tip(Component.translatable(K + "dur_tip.current",
                                GunsmithUi.formatCount(s.repair().state().current())), GunsmithUi.TIP_GRAY),
                        tip(Component.translatable(K + "dur_tip.lose"), GunsmithUi.TIP_GRAY),
                        tip(Component.translatable(K + "dur_tip.gone"), GunsmithUi.TIP_GRAY));
            }
        } else {
            for (int i = 0; i < STAT_ROWS.length; i++) {
                if (GunsmithUi.inRect(mx, my, STATS_X + 2, statRowY(i) - 1, STATS_W - 4, STAT_STEP)) {
                    return statTooltip(s, STAT_ROWS[i]);
                }
            }
            if (GunsmithUi.inRect(mx, my, STATS_X + 2, 133, STATS_W - 4, 9)) {
                return List.of(tip(Component.translatable(K + "stat_name.overall"), GunsmithUi.TIP_TITLE),
                        tip(Component.translatable(K + "stat_tip.overall"), GunsmithUi.TIP_GRAY));
            }
        }

        if (GunsmithUi.inRect(mx, my, ORDER_X, 156, ORDER_W, 36)) {
            boolean repair = s.mode() == Mode.REPAIR;
            int need = unlockLevel(repair);
            return List.of(tip(Component.translatable(K + (repair ? "repair" : "assemble")), GunsmithUi.TIP_TITLE),
                    tip(Component.translatable(K + "fee_tip.charged", GunsmithUi.formatCount(fee(repair))),
                            GunsmithUi.TIP_GRAY),
                    tip(Component.translatable(K + "fee_tip.level", need, level),
                            level >= need ? GunsmithUi.TIP_GRAY : GunsmithUi.TIP_RED));
        }

        if (GunsmithUi.inRect(mx, my, START_X, START_Y, START_W, START_H)) {
            Component why = blockReason(s, level);
            if (why != null) {
                return List.of(tip(why, GunsmithUi.TIP_RED));
            }
            if (s.mode() == Mode.REPAIR) {
                return List.of(tip(Component.translatable(K + "start.repair"), GunsmithUi.TIP_TITLE),
                        tip(Component.translatable(K + "start_tip.repair", GunsmithUi.formatCount(fee(true)),
                                Component.translatable(s.repairPreview().requiredPart().slotKey())), GunsmithUi.TIP_GRAY),
                        tip(Component.translatable(K + "start_tip.closes"), GunsmithUi.TIP_GRAY));
            }
            return List.of(tip(Component.translatable(K + "start.assemble"), GunsmithUi.TIP_TITLE),
                    tip(Component.translatable(K + "start_tip.assemble", GunsmithUi.formatCount(fee(false)),
                            s.parts().size()), GunsmithUi.TIP_GRAY),
                    tip(Component.translatable(K + "start_tip.closes"), GunsmithUi.TIP_GRAY));
        }

        if (!carrying && outputStack().isEmpty() && GunsmithUi.inRect(mx, my, GunsmithAssemblyMenu.SLOT_OUTPUT_X - 1,
                GunsmithAssemblyMenu.SLOT_OUTPUT_Y - 1, 18, 18)) {
            return List.of(tip(Component.translatable(K + "output_tip.title"), GunsmithUi.TIP_TITLE),
                    tip(Component.translatable(K + "output_tip.hint"), GunsmithUi.TIP_GRAY));
        }
        return null;
    }

    private List<Component> checkRowTooltip(Snapshot s, GunsmithPressPart part) {
        PartView view = s.views().get(part);
        Component name = Component.translatable(part.slotKey());
        Component affects = tip(Component.translatable(K + "part_tip.affects", affectsText(s.platform(), part)),
                GunsmithUi.TIP_GRAY);
        return switch (view.state()) {
            case EMPTY -> List.of(tip(name, GunsmithUi.TIP_TITLE), affects,
                    tip(Component.translatable(K + "check_tip.missing"), GunsmithUi.TIP_RED));
            case WRONG -> {
                Component have = view.data() != null
                        ? Component.translatable(view.data().platform().labelKey())
                        : Component.literal("?");
                yield List.of(tip(name, GunsmithUi.TIP_TITLE),
                        tip(Component.translatable(K + "check_tip.wrong", have,
                                Component.translatable(s.platform().labelKey())), GunsmithUi.TIP_RED));
            }
            case OK -> {
                List<Component> lines = new ArrayList<>();
                GunsmithPartItem.PartData data = view.data();
                Component partName = data.variant() == GunsmithPartVariant.BASE
                        ? Component.translatable("screen.miningdim.gunsmith_press.product.name_base",
                        Component.translatable(data.platform().labelKey()), Component.translatable(part.labelKey()))
                        : Component.translatable(data.variant().labelKey());
                lines.add(tip(partName, GunsmithUi.TIP_TITLE));
                lines.add(tip(Component.translatable(K + "check_tip.quality",
                        Component.translatable(data.quality().labelKey()),
                        GunsmithUi.format2(data.coefficient())), GunsmithUi.TIP_GRAY));
                lines.add(tip(Component.translatable(data.variant().rarity().labelKey()),
                        GunsmithUi.rarityLight(data.variant().rarity())));
                lines.add(affects);
                yield lines;
            }
        };
    }

    private List<Component> statTooltip(Snapshot s, String id) {
        List<Component> lines = new ArrayList<>();
        lines.add(tip(Component.translatable(K + "stat_name." + id), GunsmithUi.TIP_TITLE));
        String sourceKey = switch (id) {
            case "damage", "headshot", "range", "recoil", "spread" -> id;
            case "ads" -> "handling";
            default -> null;
        };
        if (sourceKey == null) {
            lines.add(tip(Component.translatable(K + "stat_tip.variant_only"), GunsmithUi.TIP_GRAY));
        } else if (s.platform() != null) {
            GunsmithPressPart source = GunsmithAssemblyRecipe.statSourceParts(s.platform()).get(sourceKey);
            lines.add(source != null
                    ? tip(Component.translatable(K + "stat_tip.source", Component.translatable(source.slotKey())),
                    GunsmithUi.TIP_GRAY)
                    : tip(Component.translatable(K + "stat_tip.no_source"), GunsmithUi.TIP_GRAY));
        }
        switch (id) {
            case "recoil", "vertical_recoil", "spread" ->
                    lines.add(tip(Component.translatable(K + "stat_tip.lower_better"), GunsmithUi.TIP_GRAY));
            case "ads" -> lines.add(tip(Component.translatable(K + "stat_tip.ads"), GunsmithUi.TIP_GRAY));
            default -> {
            }
        }
        return lines;
    }

    private static Component tip(Component text, int rgb) {
        return GunsmithUi.tip(text, rgb);
    }

    // ================================================================== input

    /** 只接开工按钮的左键; 其它一律交给原版槽位逻辑。按钮矩形已在类加载自检里验过不压槽位。 */
    @Override
    protected boolean onScreenClick(double mx, double my, int button) {
        if (button != 0 || !GunsmithUi.inRect(mx, my, START_X, START_Y, START_W, START_H)) {
            return false;
        }
        // 置灰时不发 (原因在红字提示里); 服务端 tryStartAssembly 仍会独立复核并扣工费。
        if (blockReason(snapshot(), ownerLevel()) == null) {
            sendButton(GunsmithAssemblyMenu.BUTTON_START_ASSEMBLY);
        }
        return true;
    }

    // ================================================================== state

    private Snapshot snapshot() {
        ItemStack input = menu.blueprint();
        Map<GunsmithPressPart, ItemStack> stacks = menu.partStacks();
        Map<GunsmithPressPart, PartView> views = new EnumMap<>(GunsmithPressPart.class);
        if (GunsmithAssemblyRecipe.isBlueprint(input)) {
            GunsmithBlueprint blueprint = GunsmithAssemblyRecipe.blueprint(input);
            GunsmithPlatform platform = blueprint.platform();
            List<GunsmithPressPart> parts = ordered(blueprint.requiredParts());
            for (GunsmithPressPart part : parts) {
                views.put(part, buildView(stacks.get(part), part, platform));
            }
            Map<GunsmithPressPart, ItemStack> compatible = GunsmithAssemblyRecipe.previewCompatibleParts(blueprint, stacks);
            double durabilityMultiplier = 1.0D;
            for (ItemStack stack : compatible.values()) {
                GunsmithPartItem.PartData data = stack.isEmpty() ? null : GunsmithPartItem.tryPartData(stack);
                if (data != null) {
                    durabilityMultiplier *= data.variant().maximumDurabilityMultiplier();
                }
            }
            ResourceLocation gunId = safeAssembledGunId(input, compatible, blueprint);
            GunsmithTaczClientData client = findClientData(gunId);
            GunsmithBaseStats base = findBaseStats(gunId);
            GunsmithAssemblyRecipe.Preview preview = null;
            if (base != null) {
                try {
                    preview = GunsmithAssemblyRecipe.preview(blueprint, compatible, base);
                } catch (RuntimeException e) {
                    preview = null;
                }
            }
            int newDurability = GunsmithGunDurability.initialMaximum(platform, durabilityMultiplier);
            return new Snapshot(Mode.BUILD, input, blueprint, null, null, platform, parts, views, gunId,
                    client, base, preview, newDurability, durabilityMultiplier);
        }
        // 渲染线程没有外层兜底, 待维修枪一律经菜单的不抛入口读 (审查 2)。
        GunsmithGunDurability.Managed repair = menu.repairTarget();
        if (repair != null) {
            GunsmithPlatform platform = repair.stats().blueprint().platform();
            GunsmithGunDurability.RepairPreview preview = GunsmithGunDurability.repairPreview(repair);
            GunsmithPressPart need = preview.requiredPart();
            views.put(need, repairView(stacks.get(need), repair));
            ResourceLocation gunId = repair.stats().gunId();
            return new Snapshot(Mode.REPAIR, input, null, repair, preview, platform, List.of(need), views, gunId,
                    findClientData(gunId), null, null, 0, 1.0D);
        }
        return new Snapshot(Mode.IDLE, input, null, null, null, null, List.of(), views, null,
                null, null, null, 0, 1.0D);
    }

    private static PartView buildView(@Nullable ItemStack stack, GunsmithPressPart part, GunsmithPlatform platform) {
        if (stack == null || stack.isEmpty()) {
            return new PartView(PartState.EMPTY, null);
        }
        GunsmithPartItem.PartData data = GunsmithPartItem.tryPartData(stack);
        boolean ok = data != null && data.part() == part && data.platform() == platform;
        return new PartView(ok ? PartState.OK : PartState.WRONG, data);
    }

    private static PartView repairView(@Nullable ItemStack stack, GunsmithGunDurability.Managed repair) {
        if (stack == null || stack.isEmpty()) {
            return new PartView(PartState.EMPTY, null);
        }
        GunsmithPartItem.PartData data = GunsmithPartItem.tryPartData(stack);
        boolean ok;
        try {
            ok = data != null && GunsmithGunDurability.isRepairReplacement(repair.stats(), stack);
        } catch (RuntimeException e) {
            ok = false;
        }
        return new PartView(ok ? PartState.OK : PartState.WRONG, data);
    }

    @Nullable
    private static ResourceLocation safeAssembledGunId(ItemStack input, Map<GunsmithPressPart, ItemStack> compatible,
                                                       GunsmithBlueprint blueprint) {
        try {
            return GunsmithAssemblyRecipe.assembledGunId(input, compatible);
        } catch (RuntimeException e) {
            return blueprint.gunId();
        }
    }

    @Nullable
    private static GunsmithTaczClientData findClientData(@Nullable ResourceLocation gunId) {
        if (gunId == null) {
            return null;
        }
        try {
            Optional<GunsmithTaczClientData> data = GunsmithTaczClientData.find(gunId);
            return data.orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static GunsmithBaseStats findBaseStats(@Nullable ResourceLocation gunId) {
        if (gunId == null) {
            return null;
        }
        try {
            return GunsmithTaczBridge.findBaseStats(gunId).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 开工会被拒的原因 (与服务端 tryStartRepair / tryStartAssembly 的判定顺序一致), 可开工时返回 null。
     * 信用点是否够扣只有服务端知道, 不在这里判断。最后再用菜单的 canAssemble 兜一次, 两边口径不会漏开。
     */
    @Nullable
    private Component blockReason(Snapshot s, int level) {
        if (menu.isAnimating()) {
            return Component.translatable(K + "why.busy");
        }
        if (!outputStack().isEmpty()) {
            return Component.translatable(K + "why.output");
        }
        switch (s.mode()) {
            case IDLE -> {
                return Component.translatable(K + "why.no_input");
            }
            case BUILD -> {
                if (!MunitionsLevels.isAssemblyUnlocked(level)) {
                    return Component.translatable(K + "why.level", MunitionsConfig.ASSEMBLY_UNLOCK_LEVEL.get());
                }
                List<GunsmithPressPart> missing = new ArrayList<>();
                for (GunsmithPressPart part : s.parts()) {
                    if (s.views().get(part).state() != PartState.OK) {
                        missing.add(part);
                    }
                }
                if (!missing.isEmpty()) {
                    return Component.translatable(K + "why.missing", partList(missing));
                }
                if (s.client() == null || s.base() == null) {
                    return Component.translatable(K + "why.tacz");
                }
            }
            case REPAIR -> {
                if (level < MunitionsConfig.repairUnlockLevel()) {
                    return Component.translatable(K + "why.repair_level", MunitionsConfig.repairUnlockLevel());
                }
                if (!s.repairPreview().available()) {
                    return Component.translatable(repairStatusKey(s.repairPreview().status()));
                }
                GunsmithPressPart need = s.repairPreview().requiredPart();
                if (s.views().get(need).state() != PartState.OK) {
                    return Component.translatable(K + "why.repair_part", Component.translatable(need.slotKey()));
                }
            }
        }
        return menu.canAssemble() ? null : Component.translatable(K + "why.not_ready");
    }

    private static String repairStatusKey(GunsmithGunDurability.RepairStatus status) {
        return K + switch (status) {
            case AVAILABLE -> "repair_ready";
            case FULL -> "repair_full";
            case INSUFFICIENT_WEAR -> "repair_wait";
            case EXHAUSTED -> "repair_exhausted";
        };
    }

    private ItemStack outputStack() {
        return menu.getSlot(GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT).getItem();
    }

    private float progress() {
        int remaining = menu.animationRemainingTicks();
        if (remaining <= 0) {
            return 0.0F;
        }
        float total = GunsmithAssemblyBenchBlockEntity.ASSEMBLY_DURATION_TICKS;
        return Math.max(0.0F, Math.min(1.0F, 1.0F - remaining / total));
    }

    /** 相对基础值的涨跌百分比 (基础值非正时视为不变)。 */
    private static double relative(double value, double base) {
        return base > 0.0D ? (value / base - 1.0D) * 100.0D : 0.0D;
    }

    private static long fee(boolean repair) {
        return repair ? MunitionsConfig.repairWorkFeeCredits() : MunitionsConfig.ASSEMBLY_WORK_FEE_CREDITS.get();
    }

    private static int unlockLevel(boolean repair) {
        return repair ? MunitionsConfig.repairUnlockLevel() : MunitionsConfig.ASSEMBLY_UNLOCK_LEVEL.get();
    }

    private static Component gunName(Snapshot s) {
        if (s.client() != null) {
            return s.client().gunName();
        }
        if (s.blueprint() != null) {
            return Component.translatable(s.blueprint().nameKey());
        }
        return s.gunId() != null ? Component.literal(s.gunId().getPath().toUpperCase(Locale.ROOT)) : Component.empty();
    }

    /** "伤害"、"射程、开镜" 这类: 该部件在该平台上决定的属性; 一项都不管时是 "—"。 */
    private static Component affectsText(@Nullable GunsmithPlatform platform, GunsmithPressPart part) {
        if (platform == null) {
            return Component.literal("—");
        }
        MutableComponent out = null;
        for (Map.Entry<String, GunsmithPressPart> entry : GunsmithAssemblyRecipe.statSourceParts(platform).entrySet()) {
            if (entry.getValue() != part) {
                continue;
            }
            Component name = Component.translatable(K + "stat_short." + entry.getKey());
            out = out == null ? name.copy() : out.append(Component.translatable(K + "list_sep")).append(name);
        }
        return out != null ? out : Component.literal("—");
    }

    private static Component partList(List<GunsmithPressPart> parts) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                out.append(Component.translatable(K + "list_sep"));
            }
            out.append(Component.translatable(parts.get(i).slotKey()));
        }
        return out;
    }

    // ================================================================== geometry

    /** 部件按槽位上排→下排、左→右排序 (清单行序与槽位的视觉顺序一致)。 */
    private static List<GunsmithPressPart> ordered(Iterable<GunsmithPressPart> parts) {
        List<GunsmithPressPart> list = new ArrayList<>();
        parts.forEach(list::add);
        list.sort(Comparator.comparingInt(GunsmithAssemblyMenu::partSlotY).thenComparingInt(GunsmithAssemblyMenu::partSlotX));
        return list;
    }

    private static int checkRowY(int i) {
        return CHECK_Y + i * CHECK_STEP;
    }

    private static int statRowY(int i) {
        return STAT_Y + i * STAT_STEP;
    }

    private static int[] crop(GunsmithPlatform platform) {
        return platform == GunsmithPlatform.PISTOL ? CROP_PISTOL : CROP_FULL;
    }

    /** 剪影在展示窗里的矩形 {x, y, w, h}: 按源区间的宽高比放进 (展示窗 - 边距), 居中。 */
    private static int[] gunRect(int[] crop) {
        float aspect = crop[1] / (float) GunsmithUi.HUD_H;
        int maxW = DISP_W - 8;
        int maxH = DISP_H - 10;
        int w = Math.min(maxW, Math.round(maxH * aspect));
        int h = Math.round(w / aspect);
        return new int[]{DISP_X + Math.round((DISP_W - w) / 2.0F), DISP_Y + Math.round((DISP_H - h) / 2.0F), w, h};
    }

    private static Map<GunsmithPlatform, Map<GunsmithPressPart, float[]>> anchors() {
        Map<GunsmithPressPart, float[]> rifle = new EnumMap<>(GunsmithPressPart.class);
        rifle.put(GunsmithPressPart.BARREL, new float[]{0.06F, 0.31F});
        rifle.put(GunsmithPressPart.CORE, new float[]{0.24F, 0.28F});
        rifle.put(GunsmithPressPart.SLIDE, new float[]{0.40F, 0.25F});
        rifle.put(GunsmithPressPart.BOLT, new float[]{0.53F, 0.35F});
        rifle.put(GunsmithPressPart.RECEIVER, new float[]{0.50F, 0.36F});
        rifle.put(GunsmithPressPart.FIRING_PIN, new float[]{0.62F, 0.34F});
        rifle.put(GunsmithPressPart.HAMMER, new float[]{0.70F, 0.30F});
        rifle.put(GunsmithPressPart.HANDGUARD, new float[]{0.34F, 0.42F});
        rifle.put(GunsmithPressPart.BIPOD, new float[]{0.36F, 0.62F});
        rifle.put(GunsmithPressPart.TRIGGER, new float[]{0.56F, 0.55F});
        rifle.put(GunsmithPressPart.GRIP, new float[]{0.57F, 0.76F});
        rifle.put(GunsmithPressPart.STOCK, new float[]{0.87F, 0.42F});

        Map<GunsmithPlatform, Map<GunsmithPressPart, float[]>> map = new EnumMap<>(GunsmithPlatform.class);
        for (GunsmithPlatform platform : GunsmithPlatform.values()) {
            map.put(platform, new EnumMap<>(rifle));
        }
        Map<GunsmithPressPart, float[]> ak = map.get(GunsmithPlatform.AK);
        ak.put(GunsmithPressPart.BARREL, new float[]{0.05F, 0.30F});
        ak.put(GunsmithPressPart.CORE, new float[]{0.27F, 0.27F});
        ak.put(GunsmithPressPart.BOLT, new float[]{0.52F, 0.30F});
        ak.put(GunsmithPressPart.HANDGUARD, new float[]{0.34F, 0.40F});
        ak.put(GunsmithPressPart.GRIP, new float[]{0.69F, 0.74F});
        ak.put(GunsmithPressPart.STOCK, new float[]{0.88F, 0.40F});
        Map<GunsmithPressPart, float[]> pistol = map.get(GunsmithPlatform.PISTOL);
        pistol.put(GunsmithPressPart.BARREL, new float[]{0.06F, 0.20F});
        pistol.put(GunsmithPressPart.SLIDE, new float[]{0.40F, 0.17F});
        pistol.put(GunsmithPressPart.HAMMER, new float[]{0.86F, 0.14F});
        pistol.put(GunsmithPressPart.TRIGGER, new float[]{0.56F, 0.44F});
        pistol.put(GunsmithPressPart.GRIP, new float[]{0.78F, 0.72F});
        // 机枪同时要护木 (下排第 1 列) 和脚架 (第 2 列): 护木落点左移, 两根下排横线才不叠在一起。
        map.get(GunsmithPlatform.MACHINE_GUN).put(GunsmithPressPart.HANDGUARD, new float[]{0.22F, 0.42F});
        Map<GunsmithPressPart, float[]> bullpup = map.get(GunsmithPlatform.BULLPUP);
        bullpup.put(GunsmithPressPart.HANDGUARD, new float[]{0.20F, 0.42F});
        bullpup.put(GunsmithPressPart.GRIP, new float[]{0.42F, 0.74F});
        bullpup.put(GunsmithPressPart.RECEIVER, new float[]{0.72F, 0.38F});
        return map;
    }

    // ================================================================== self-check

    /**
     * 开工按钮 (唯一会在 super.mouseClicked 之前吞左键的控件) 不得压到 36 个背包槽与全部容器槽的原版判定盒
     * (槽 x-1, y-1 起 18x18), 相交即抛。
     */
    private static void assertClickTargetsClearOfSlots() {
        List<int[]> slotBoxes = new ArrayList<>();
        for (int row = 0; row < 4; row++) {
            int slotY = row < 3
                    ? GunsmithAssemblyMenu.PLAYER_INV_Y + row * 18
                    : GunsmithAssemblyMenu.PLAYER_INV_Y + 3 * 18 + 4;
            for (int col = 0; col < 9; col++) {
                slotBoxes.add(new int[]{GunsmithAssemblyMenu.PLAYER_INV_X + col * 18 - 1, slotY - 1, 18, 18});
            }
        }
        for (int slot = 0; slot < GunsmithAssemblyBenchBlockEntity.SLOT_COUNT; slot++) {
            slotBoxes.add(new int[]{GunsmithAssemblyMenu.containerSlotX(slot) - 1,
                    GunsmithAssemblyMenu.containerSlotY(slot) - 1, 18, 18});
        }
        int[] t = {START_X, START_Y, START_W, START_H};
        for (int[] s : slotBoxes) {
            if (t[0] < s[0] + s[2] && s[0] < t[0] + t[2] && t[1] < s[1] + s[3] && s[1] < t[1] + t[3]) {
                throw new IllegalStateException("gunsmith assembly start button overlaps the slot hit box at ("
                        + (s[0] + 1) + "," + (s[1] + 1) + ")");
            }
        }
    }
}
