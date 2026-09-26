package com.miningdim.webui.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.LiteralContents;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.Locale;

/**
 * 把一段带样式的 Component 拍平成"文字片段 + 颜色 + 粗体"的序列, 供页面按游戏内的样子原样画出来
 * (称号徽记、成就标题这类逐字渐变的文字)。
 *
 * <h2>为什么发片段而不发一串 HTML 或纯文本</h2>
 * 渐变称号在服务端已经逐字拆成了各带一个 RGB 颜色的片段 ({@code TierPalette.gradient}), 前端自己再算一遍渐变
 * 就是第二份实现, 首尾色标差一位四舍五入就与游戏里对不上。直接把服务端渲染好的结构发过去, 页面照着画, 与聊天框里
 * 看到的逐字相同。
 *
 * <h2>文字与 {@link WebUiItemJson} 的 nameParts 同一套写法</h2>
 * 每个片段要么 {@code {"t": 字面量}} 要么 {@code {"k": 翻译键}} (专用服务端不加载 lang, 单色档称号与成就标题保留
 * translate, 由客户端的 client.i18n 按玩家自己的语言解)。翻译键带 fallback 时另发 {@code "f"}。
 *
 * <h2>样式按原版的继承规则算好再发</h2>
 * 子节点的样式缺省项取父节点的 ({@code Style#applyTo}), 与原版渲染同一口径。颜色写成 {@code #RRGGBB}; 整条链上都没有
 * 颜色时不发 {@code color} 键 (由展示方按场景取默认色, 聊天里是白色)。{@code bold} 恒发。
 */
public final class WebUiTextJson {

    /** 拍平深度上限, 理由同 {@link WebUiItemJson} 的同名常量: 防御递归引用, 我方的文字最多两层。 */
    private static final int MAX_DEPTH = 8;

    private WebUiTextJson() {
    }

    /** 拍平一段文字; 空片段 (空字面量) 不发。 */
    public static JsonArray segments(Component component) {
        JsonArray out = new JsonArray();
        flatten(component, Style.EMPTY, out, 0);
        return out;
    }

    /** 深度优先: 本节点的内容先出, 再出它的 siblings —— 与原版的渲染顺序一致。 */
    private static void flatten(Component component, Style parent, JsonArray out, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        Style style = component.getStyle().applyTo(parent);
        ComponentContents contents = component.getContents();
        JsonObject segment = null;
        if (contents instanceof TranslatableContents translatable) {
            segment = new JsonObject();
            segment.addProperty("k", translatable.getKey());
            if (translatable.getFallback() != null) {
                segment.addProperty("f", translatable.getFallback());
            }
        } else if (contents instanceof LiteralContents literal && !literal.text().isEmpty()) {
            segment = new JsonObject();
            segment.addProperty("t", literal.text());
        }
        if (segment != null) {
            TextColor color = style.getColor();
            if (color != null) {
                segment.addProperty("color", String.format(Locale.ROOT, "#%06X", color.getValue() & 0xFFFFFF));
            }
            segment.addProperty("bold", style.isBold());
            out.add(segment);
        }
        for (Component sibling : component.getSiblings()) {
            flatten(sibling, style, out, depth + 1);
        }
    }
}
