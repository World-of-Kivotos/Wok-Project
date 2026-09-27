package com.miningdim.job.munitions.client.style;

import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.AbstractMiningScreen;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 军火台 / 机械冲压机新界面的共同基类 (360x240)。负责:
 * <ul>
 *   <li>每帧按玩家选的风格画底图、标题栏、右上角操作员块和风格按钮, 再交给子类 {@link #renderScreen} 画其余部分;</li>
 *   <li>风格面板 (浮层) 与子类自己的模态框 (如"取消制作"确认) 的输入规则: 浮层打开时在 mouseClicked 最顶部吞掉
 *       一切点击并记下, 吞掉与之配对的 mouseReleased (原版在松开时放下手持物品) 与拖拽; Esc 只关浮层,
 *       其余按键全部吞掉 (防 1-9 / Q 作用在下面的格子上); 滚轮吞掉; 传给 super.render 的鼠标换成
 *       (-10000, -10000), 下层格子不高亮、不出提示; isHovering 也兜底返回 false;</li>
 *   <li>浮层画在 render 里 super.render 之后、z = 350 (物品约 z 150-250, 提示框 z 400);</li>
 *   <li>getSlotColor 返回当前风格的槽位高亮色 (浅色风格要看得见)。</li>
 * </ul>
 *
 * 子类只实现钩子, 不要覆写 render / mouseClicked / mouseReleased / keyPressed 等 (已 final)。
 * 钩子里的坐标一律是 GUI 相对坐标 (界面左上角为原点)。
 */
public abstract class GunsmithStyledScreen<T extends AbstractMiningMenu> extends AbstractMiningScreen<T> {

    public static final int W = GunsmithUi.GUI_W;
    public static final int H = GunsmithUi.GUI_H;

    /** 物品之上的前景层 (冲头、火花)。 */
    public static final float FRONT_Z = 300.0F;
    /** 浮层 / 模态框。 */
    public static final float OVERLAY_Z = 350.0F;

    private final StylePicker stylePicker = new StylePicker();
    /** 每个鼠标键一位: 该键的按下被本类吞掉, 配对的松开也要吞掉。 */
    private int swallowedButtons;
    private long frameTime;

    /**
     * @param fallbackBackground AbstractMiningScreen 要求非空的底图; 本类不用它画 (底图由风格运行时生成),
     *                           传现有的界面贴图即可
     */
    protected GunsmithStyledScreen(T menu, Inventory inv, Component title, ResourceLocation fallbackBackground) {
        super(menu, inv, title, fallbackBackground, W, H);
        this.titleLabelX = 0;
        this.titleLabelY = 0;
        this.inventoryLabelX = 0;
        this.inventoryLabelY = 0;
    }

    // ================================================================== hooks for subclasses

    /** 本帧标题栏数据。 */
    protected abstract GunsmithTheme.Header header();

    /**
     * 主层: 面板、页签、卡片、按钮、槽位框、文字。画在原版槽位物品之下 (z 0)。
     * 浮层打开时 p 的鼠标已失焦 (hov 恒 false)。底图、标题栏、操作员块、风格按钮已由基类画好。
     */
    protected abstract void renderScreen(GsPainter p, GunsmithTheme theme);

    /** 前景层 (z 300, 物品之上、提示框之下), 如冲压机压下的冲头与火花。默认不画。 */
    protected void renderFront(GsPainter p, GunsmithTheme theme) {
    }

    /**
     * 界面自己的悬停提示 (只在没有浮层时询问)。返回 null 或空表示没有, 此时落到原版槽位物品提示。
     * 需要让物品提示优先时, 先判断 {@link #hoveredSlot}。风格按钮与操作员经验提示由基类处理。
     */
    @Nullable
    protected List<Component> screenTooltip(double relX, double relY) {
        return null;
    }

    /** 左键/右键点在界面上 (无浮层时)。返回 true 表示已处理 (不会再交给原版槽位逻辑, 配对的松开也会被吞)。 */
    protected boolean onScreenClick(double relX, double relY, int button) {
        return false;
    }

    /** 滚轮 (无浮层时)。返回 true 表示已处理。 */
    protected boolean onScreenScroll(double relX, double relY, double delta) {
        return false;
    }

    /** 按键 (无浮层时, 在原版处理之前)。返回 true 表示已处理。 */
    protected boolean onScreenKeyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    /** 子类自己的模态框是否打开 (如军火台"取消制作"确认)。打开时风格面板会被关掉。 */
    protected boolean isModalOpen() {
        return false;
    }

    /**
     * 画模态框 (z 350, 真实鼠标)。先用 theme.shade() 盖住整个界面 (含物品), 再画对话框。
     */
    protected void renderModal(GsPainter p, GunsmithTheme theme) {
    }

    /** 模态框打开时的点击 (整次点击已被吞掉, 这里只判断落在哪个按钮上)。 */
    protected void onModalClick(double relX, double relY, int button) {
    }

    /** Esc 关闭模态框 (只关模态框, 不关界面)。 */
    protected void closeModal() {
    }

    /** 操作员块显示的等级 (默认本机玩家的军火商等级)。 */
    protected int ownerLevel() {
        return GunsmithUi.playerLevel();
    }

    // ================================================================== helpers for subclasses

    /** 当前风格 (每帧读客户端配置)。 */
    protected final GunsmithTheme theme() {
        return GunsmithTheme.current();
    }

    protected final StylePicker stylePicker() {
        return stylePicker;
    }

    /** 风格面板或模态框是否打开 (下层整体失焦)。 */
    protected final boolean overlayOpen() {
        return stylePicker.isOpen() || isModalOpen();
    }

    /** 发菜单按钮 (clickMenuButton id)。 */
    protected final void sendButton(int id) {
        GunsmithUi.sendButton(menu, id);
    }

    /** 本帧毫秒时钟 (与画笔的 now 相同)。 */
    protected final long frameTime() {
        return frameTime != 0L ? frameTime : Util.getMillis();
    }

    /** 给玩家背包 36 格画槽位框 (背包位置由 Menu 决定, 与风格无关)。 */
    protected final void drawPlayerInventorySlots(GsPainter p, GunsmithTheme theme) {
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory && slot.isActive()) {
                theme.slot(p, slot.x, slot.y);
            }
        }
    }

    // ================================================================== rendering

    @Override
    public final void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (stylePicker.isOpen() && isModalOpen()) {
            stylePicker.close();
        }
        frameTime = Util.getMillis();
        boolean overlay = overlayOpen();
        int mx = overlay ? GsPainter.NO_MOUSE : mouseX;
        int my = overlay ? GsPainter.NO_MOUSE : mouseY;
        // 失焦鼠标传给原版: 下层格子不高亮、不出物品提示, hoveredSlot 为 null。
        super.render(graphics, mx, my, partialTick);

        GunsmithTheme theme = theme();
        GsPainter front = new GsPainter(graphics, this.font, this.leftPos, this.topPos, mx, my, frameTime);
        front.atZ(FRONT_Z, () -> renderFront(front, theme));

        if (isModalOpen()) {
            GsPainter real = front.withMouse(mouseX, mouseY);
            real.atZ(OVERLAY_Z, () -> renderModal(real, theme));
        } else if (stylePicker.isOpen()) {
            GsPainter real = front.withMouse(mouseX, mouseY);
            real.atZ(OVERLAY_Z, () -> stylePicker.renderPopover(real, theme));
        }
    }

    @Override
    protected final void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        GunsmithTheme theme = theme();
        GsPainter p = new GsPainter(graphics, this.font, this.leftPos, this.topPos, mouseX, mouseY, frameTime());
        theme.bg(p);
        theme.header(p, header());
        Minecraft mc = Minecraft.getInstance();
        Component name = mc.player != null ? mc.player.getName() : Component.empty();
        ResourceLocation skin = mc.player != null ? mc.player.getSkinTextureLocation() : null;
        theme.owner(p, ownerLevel(), GunsmithUi.playerXpFraction(), name, skin);
        stylePicker.renderButton(p, theme);
        renderScreen(p, theme);
    }

    @Override
    protected final void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 标题与"物品栏"字样由标题栏自己画, 不要原版标签。
    }

    @Override
    protected final void renderTooltip(GuiGraphics graphics, int x, int y) {
        if (overlayOpen()) {
            return;
        }
        double relX = x - this.leftPos;
        double relY = y - this.topPos;
        List<Component> lines;
        if (StylePicker.overButton(relX, relY)) {
            lines = StylePicker.buttonTooltip();
        } else if (GunsmithUi.inRect(relX, relY, GunsmithUi.OWNER_X, GunsmithUi.OWNER_Y,
                GunsmithUi.OWNER_W, GunsmithUi.OWNER_H)) {
            lines = List.of(GunsmithUi.xpTooltip());
        } else {
            lines = screenTooltip(relX, relY);
        }
        if (lines != null && !lines.isEmpty()) {
            graphics.renderComponentTooltip(this.font, lines, x, y);
            return;
        }
        super.renderTooltip(graphics, x, y);
    }

    @Override
    public int getSlotColor(int index) {
        return theme().slotHighlight();
    }

    @Override
    protected final boolean isHovering(int x, int y, int width, int height, double mouseX, double mouseY) {
        if (overlayOpen()) {
            return false;
        }
        return super.isHovering(x, y, width, height, mouseX, mouseY);
    }

    // ================================================================== input

    @Override
    public final boolean mouseClicked(double mouseX, double mouseY, int button) {
        double relX = mouseX - this.leftPos;
        double relY = mouseY - this.topPos;
        // 浮层打开: 最顶部吞掉任意键的点击, 下层什么都收不到。
        if (stylePicker.isOpen()) {
            swallowRelease(button);
            stylePicker.mouseClicked(relX, relY, button);
            return true;
        }
        if (isModalOpen()) {
            swallowRelease(button);
            onModalClick(relX, relY, button);
            return true;
        }
        if (stylePicker.mouseClicked(relX, relY, button)) {
            swallowRelease(button);
            return true;
        }
        if (onScreenClick(relX, relY, button)) {
            swallowRelease(button);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public final boolean mouseReleased(double mouseX, double mouseY, int button) {
        int bit = buttonBit(button);
        if ((swallowedButtons & bit) != 0) {
            swallowedButtons &= ~bit;
            return true;
        }
        if (overlayOpen()) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public final boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (overlayOpen() || (swallowedButtons & buttonBit(button)) != 0) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public final boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (overlayOpen()) {
            return true;
        }
        if (onScreenScroll(mouseX - this.leftPos, mouseY - this.topPos, delta)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public final boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (stylePicker.isOpen()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                stylePicker.close();
            }
            return true;
        }
        if (isModalOpen()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                closeModal();
            }
            return true;
        }
        if (onScreenKeyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public final boolean charTyped(char codePoint, int modifiers) {
        if (overlayOpen()) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private void swallowRelease(int button) {
        swallowedButtons |= buttonBit(button);
    }

    private static int buttonBit(int button) {
        return 1 << (button & 31);
    }
}
