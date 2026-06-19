package com.miningdim.client.webui;

import org.lwjgl.glfw.GLFW;

/**
 * GLFW -> CEF 输入映射助手 (客户端宿主侧, 抢救自 InputHandler, 去掉重复的坐标缩放分支)。
 *
 * 仅做纯映射与修饰键状态机, 不持有浏览器引用 (坐标换算归 {@link WebUiScreen}, 职责分离)。
 * CEF 事件标志位 (EVENTFLAG_*) 与 JCEF 端常量一致, 用于 sendMouseWheel 的 modifiers 传参。
 *
 * 设计契约 (共享契约 8 已知必修): 鼠标按钮必须经 {@link #toCefMouseButton} 把 GLFW button 映射为
 * CEF 的 left=0 / middle=1 / right=2, 绝不把原始 GLFW button (left=0/right=1/middle=2) 直传 CEF,
 * 否则右键与中键在网页里互换。
 */
public final class WebUiInput {

    // CEF 事件修饰标志 (org.cef EventFlags 对齐; 仅本宿主滚轮/修饰键场景需要的子集)。
    public static final int EVENTFLAG_NONE = 0;
    public static final int EVENTFLAG_CAPS_LOCK_ON = 1;
    public static final int EVENTFLAG_SHIFT_DOWN = 1 << 1;
    public static final int EVENTFLAG_CONTROL_DOWN = 1 << 2;
    public static final int EVENTFLAG_ALT_DOWN = 1 << 3;
    public static final int EVENTFLAG_NUM_LOCK_ON = 1 << 8;

    // 当前修饰键状态 (供滚轮事件附带; 按下/抬起修饰键时由 Screen 调 updateModifiers 维护)。
    private int currentModifiers = EVENTFLAG_NONE;

    /**
     * 把 GLFW 鼠标按钮映射到 CEF 按钮编号。
     * GLFW: LEFT=0, RIGHT=1, MIDDLE=2; CEF: LEFT=0, MIDDLE=1, RIGHT=2。两套编号右/中相反, 必须显式映射。
     */
    public int toCefMouseButton(int glfwButton) {
        return switch (glfwButton) {
            case GLFW.GLFW_MOUSE_BUTTON_LEFT -> 0;
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> 1;
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> 2;
            // 其它扩展键 (X1/X2 ...) CEF 不消费, 归一到左键避免越界 (而非直传 GLFW 编号造成误判)。
            default -> 0;
        };
    }

    /** 把单次 GLFW modifiers 位掩码翻译为 CEF 事件标志 (用于 keyPress/keyTyped 即时修饰)。 */
    public int fromGlfwModifiers(int glfwMods) {
        int cef = EVENTFLAG_NONE;
        if ((glfwMods & GLFW.GLFW_MOD_SHIFT) != 0) {
            cef |= EVENTFLAG_SHIFT_DOWN;
        }
        if ((glfwMods & GLFW.GLFW_MOD_CONTROL) != 0) {
            cef |= EVENTFLAG_CONTROL_DOWN;
        }
        if ((glfwMods & GLFW.GLFW_MOD_ALT) != 0) {
            cef |= EVENTFLAG_ALT_DOWN;
        }
        if ((glfwMods & GLFW.GLFW_MOD_CAPS_LOCK) != 0) {
            cef |= EVENTFLAG_CAPS_LOCK_ON;
        }
        if ((glfwMods & GLFW.GLFW_MOD_NUM_LOCK) != 0) {
            cef |= EVENTFLAG_NUM_LOCK_ON;
        }
        return cef;
    }

    /**
     * 维护持续按下的修饰键状态 (滚轮事件本身不带 modifiers, 需用本状态补)。
     * 仅处理修饰键自身的按下/抬起; 普通键不改变状态。
     */
    public void updateModifiers(int keyCode, boolean pressed) {
        int flag = modifierFlagFor(keyCode);
        if (flag == EVENTFLAG_NONE) {
            return;
        }
        if (pressed) {
            currentModifiers |= flag;
        } else {
            currentModifiers &= ~flag;
        }
    }

    /** 当前累积修饰键标志 (供 sendMouseWheel 的 modifiers 参数)。 */
    public int currentModifiers() {
        return currentModifiers;
    }

    /** 界面关闭/失焦时清零修饰键, 防卡键状态泄漏到下次打开。 */
    public void reset() {
        currentModifiers = EVENTFLAG_NONE;
    }

    private int modifierFlagFor(int keyCode) {
        return switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> EVENTFLAG_SHIFT_DOWN;
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> EVENTFLAG_CONTROL_DOWN;
            case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> EVENTFLAG_ALT_DOWN;
            case GLFW.GLFW_KEY_CAPS_LOCK -> EVENTFLAG_CAPS_LOCK_ON;
            case GLFW.GLFW_KEY_NUM_LOCK -> EVENTFLAG_NUM_LOCK_ON;
            default -> EVENTFLAG_NONE;
        };
    }
}
