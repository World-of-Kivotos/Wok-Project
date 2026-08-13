package com.miningdim.webui.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.LiteralContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;

/**
 * Web UI 的物品序列化助手：把一个 ItemStack 上"同 id 不同实例"的差异补进回执。
 *
 * <h2>为什么需要它</h2>
 * 前端此前只拿到 {@code itemId} 与 {@code descriptionId}，二者都是 <b>Item 级</b>的。对绝大多数物品这没问题，
 * 但对<b>靠 NBT 区分变体</b>的物品是错的，而本 mod 恰好有一大类这样的物品：
 *
 * <p>枪匠零件全部注册在同一个 {@code miningdim:gunsmith_part} 之下（195 张贴图 + 196 个模型共用一个 id），
 * 平台/部位/品质/变体由 NBT 决定，显示名由 {@code GunsmithPartItem#getName(ItemStack)} 现拼、贴图由
 * {@code CustomModelData} 经模型 overrides 选。于是在补这一层之前，跳蚤市场里 195 种零件<b>全部</b>显示成
 * 同一个名字"枪匠零件"、同一张图标。
 *
 * <h2>名字为什么发结构而不发字符串</h2>
 * 不能在服务端 {@code stack.getHoverName().getString()} 之后发过去：<b>专用服务端不加载 mod 的 lang 文件</b>，
 * 那样解出来的是原始翻译键而不是中文。这正是既有 {@code descriptionId} 字段存在的理由——键由服务端给，
 * 中文由客户端的 {@code client.i18n} 经 MC 自己的 I18n 解。
 *
 * <p>但 {@code getName(ItemStack)} 拼出来的名字不是<b>一个</b>键，而是若干键与字面量的序列
 * （枪匠零件是 "平台键 + 部位键 + 字面空格 + 品质键"）。故本助手把 Component 树拍平成
 * {@code nameParts}：每项要么 {@code {"k": 翻译键}} 要么 {@code {"t": 字面量}}，前端逐项解析后原样拼接。
 * 这是通用做法，不是给枪匠零件开的特例——任何覆写了 {@code getName(ItemStack)} 的物品都自动受益。
 *
 * <h2>两个字段都是可选的</h2>
 * 名字与 Item 级默认值一致时不发 {@code nameParts}，没有 CustomModelData 时不发 {@code customModelData}。
 * 绝大多数物品两个都不发，回执形状与之前逐字节相同 —— 前端的既有 {@code descriptionId} 路径不受影响。
 */
public final class WebUiItemJson {

    /** 原版给自定义模型编号约定的 NBT 键名。模型 JSON 的 overrides 谓词 {@code custom_model_data} 读的就是它。 */
    private static final String CUSTOM_MODEL_DATA_KEY = "CustomModelData";

    /**
     * 拍平 Component 树时的最大深度。
     *
     * <p>不是防御我方数据（我方的名字最多两层），是防御<b>递归引用</b>：Component 允许任意嵌套，
     * 而第三方 mod 的 {@code getName} 我们无从约束。渲染线程之外的一次栈溢出会把整条 action 打成 500，
     * 而症状是"市场页打不开"，与真正的原因隔了十万八千里。
     */
    private static final int MAX_DEPTH = 8;

    private WebUiItemJson() {
    }

    /**
     * 把一件物品的变体信息补进已有的回执对象。
     *
     * @param target 调用方已经填好 itemId / descriptionId / count 的对象，本方法只<b>追加</b>字段
     * @param stack  该条目对应的真实 ItemStack
     */
    public static void appendVariant(JsonObject target, ItemStack stack) {
        int customModelData = customModelDataOf(stack);
        if (customModelData != 0) {
            target.addProperty("customModelData", customModelData);
        }
        JsonArray nameParts = nameParts(stack);
        if (nameParts != null) {
            target.add("nameParts", nameParts);
        }
    }

    /**
     * 该物品栈的 CustomModelData；没有则返回 0。
     *
     * <p>0 同时是"没有这个键"与"值就是 0"的返回值，这里不做区分是有依据的：原版模型 overrides 的谓词
     * 按 {@code >= n} 匹配，0 恒等于"用默认模型"，与键缺席的效果完全一致。
     */
    private static int customModelDataOf(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(CUSTOM_MODEL_DATA_KEY)) {
            return 0;
        }
        return tag.getInt(CUSTOM_MODEL_DATA_KEY);
    }

    /**
     * 显示名拍平成"键与字面量的序列"；名字与 Item 级默认值无异时返回 null（调用方据此不发这个字段）。
     *
     * <p>判据是<b>结构比较</b>而不是字符串比较：服务端解不出中文，两边 {@code getString()} 都会退成键，
     * 拿它们比等于什么都没比。这里比的是"名字是否就是那一个 descriptionId 键"。
     */
    private static JsonArray nameParts(ItemStack stack) {
        Component name = stack.getHoverName();
        JsonArray parts = new JsonArray();
        flatten(name, parts, 0);
        if (parts.isEmpty()) {
            return null;
        }
        // 恰好一项且就是 Item 级翻译键 —— 那是原版默认名, 前端走既有 descriptionId 路径即可, 不必多发一份。
        if (parts.size() == 1) {
            JsonObject only = parts.get(0).getAsJsonObject();
            if (only.has("k") && only.get("k").getAsString().equals(stack.getDescriptionId())) {
                return null;
            }
        }
        return parts;
    }

    /**
     * 深度优先拍平：本节点的内容先出，再出它的 siblings。这个顺序就是 MC 自己的渲染顺序，
     * 换成别的顺序会让"平台 部位 品质"变成"品质 平台 部位"。
     *
     * <p>{@code TranslatableContents} 的 args 刻意<b>不</b>展开：本 mod 拼名字不用带参翻译，
     * 而支持它意味着前端也要实现一遍 {@code String.format} 的占位符语义。真出现了再补，
     * 现在展开成一个只有键、丢掉参数的项会得到一句缺了半截的名字，比明确不支持更糟——
     * 故带参的项照样只发键，参数丢失时前端展示的是模板原文，肉眼可见而不是静默错误。
     */
    private static void flatten(Component component, JsonArray out, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        ComponentContents contents = component.getContents();
        if (contents instanceof TranslatableContents translatable) {
            JsonObject part = new JsonObject();
            part.addProperty("k", translatable.getKey());
            out.add(part);
        } else if (contents instanceof LiteralContents literal) {
            String text = literal.text();
            if (!text.isEmpty()) {
                JsonObject part = new JsonObject();
                part.addProperty("t", text);
                out.add(part);
            }
        }
        for (Component sibling : component.getSiblings()) {
            flatten(sibling, out, depth + 1);
        }
    }
}
