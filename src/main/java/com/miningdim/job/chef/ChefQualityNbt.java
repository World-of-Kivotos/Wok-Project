package com.miningdim.job.chef;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 品质 NBT 盖章/读取/显示工具 (Chef_Job_DesignSpec 第三章核心: 跨 mod 通用靠不注册新物品, 只盖 ItemStack NBT)。
 *
 * 物理上仍是原 ItemStack (minecraft:bread / farmersdelight:hamburger / ...), 只在其 tag 下挂一个子 compound
 * {@value #ROOT_TAG} 携带 chefQuality + 效果列表。这样对任意食物 mod 自动支持, 零 per-mod 代码。
 *
 * 盖章三步 (第三章):
 *  1. 写 NBT (品质 id + 效果 ListTag);
 *  2. {@code setHoverName} 改显示名为 "超凡面包" (带品质颜色 Component, 去斜体);
 *  3. tooltip 追加由 {@link ChefTooltipHandler} 在 ItemTooltipEvent 读 NBT 完成 (此处只负责名)。
 *
 * 全工具方法无状态; 仅服务端做章 (做菜流程), 客户端经同步的 ItemStack NBT 读品质显示。
 */
public final class ChefQualityNbt {

    private ChefQualityNbt() {
    }

    /** ItemStack tag 下的厨师品质子 compound 键。 */
    static final String ROOT_TAG = "MiningChef";
    private static final String K_QUALITY = "quality";
    private static final String K_EFFECTS = "effects";
    /** 记录做这道菜的厨师 UUID (谁做谁得经验已在做菜阶段入账; 此键留作显示/审计, 非经验再结算源)。 */
    private static final String K_OPERATOR = "operator";

    /**
     * 给一个食物 ItemStack 盖品质 + 效果列表 (第三章)。非食物 (getFoodProperties==null 的判定由调用方在
     * 做菜入口做) 不应到达此处; 本法只负责写 NBT + 改名, 不校验食物性 (做菜流程的职责)。
     *
     * @param stack    被盖章的成品菜 (原地修改其 tag)
     * @param quality  达成品质档
     * @param effects  掷出的效果实例 (个数已受 quality.maxEffects 约束)
     */
    public static void stamp(ItemStack stack, ChefQuality quality, List<ChefEffectInstance> effects) {
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("cannot stamp empty stack");
        }
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        CompoundTag root = new CompoundTag();
        root.putString(K_QUALITY, quality.id());
        ListTag list = new ListTag();
        for (ChefEffectInstance inst : effects) {
            list.add(inst.toNbt());
        }
        root.put(K_EFFECTS, list);
        stack.getOrCreateTag().put(ROOT_TAG, root);

        // 显示名 "超凡面包": 品质前缀 (带颜色) + 原物品名; 去斜体 (setHoverName 默认会斜体化自定义名)。
        stack.setHoverName(buildHoverName(stack, quality));
    }

    /** 读品质 (无章返回 null, 调用方据此短路 — 非厨师菜不结算厨师加成)。 */
    public static ChefQuality readQuality(ItemStack stack) {
        if (!stack.hasTag()) {
            return null;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ROOT_TAG, Tag.TAG_COMPOUND)) {
            return null;
        }
        return ChefQuality.fromId(tag.getCompound(ROOT_TAG).getString(K_QUALITY));
    }

    /** 是否带厨师品质章 (快速判定, tooltip/结算入口用)。 */
    public static boolean hasQuality(ItemStack stack) {
        return readQuality(stack) != null;
    }

    /** 读效果列表 (无章返回空 list; 坏 NBT 条目跳过, 不掩盖为默认效果)。 */
    public static List<ChefEffectInstance> readEffects(ItemStack stack) {
        List<ChefEffectInstance> out = new ArrayList<>();
        if (!stack.hasTag()) {
            return out;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ROOT_TAG, Tag.TAG_COMPOUND)) {
            return out;
        }
        ListTag list = tag.getCompound(ROOT_TAG).getList(K_EFFECTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ChefEffectInstance inst = ChefEffectInstance.fromNbt(list.getCompound(i));
            if (inst != null) {
                out.add(inst);
            }
        }
        return out;
    }

    /** 写操作者 UUID (审计/显示; 经验已在做菜阶段记给操作者, 此处仅留痕)。 */
    public static void setOperator(ItemStack stack, java.util.UUID operator) {
        if (!stack.hasTag() || stack.getTag() == null || !stack.getTag().contains(ROOT_TAG, Tag.TAG_COMPOUND)) {
            return;
        }
        stack.getTag().getCompound(ROOT_TAG).putUUID(K_OPERATOR, operator);
    }

    /** 读操作者 UUID (做这道菜的厨师; 无章或未写返回 null —— 谁做谁得归属凭据, 吃端不改写)。 */
    public static java.util.UUID readOperator(ItemStack stack) {
        if (!stack.hasTag()) {
            return null;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ROOT_TAG, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag root = tag.getCompound(ROOT_TAG);
        return root.hasUUID(K_OPERATOR) ? root.getUUID(K_OPERATOR) : null;
    }

    /**
     * 构造 "超凡面包" 显示名: {品质前缀 (彩色)} + 空格 + {原物品名}。去斜体 (Style.EMPTY.withItalic(false))
     * —— setHoverName 会把整名标斜体, 显式覆盖回非斜体以保持品质名观感一致。
     */
    public static Component buildHoverName(ItemStack stack, ChefQuality quality) {
        Component prefix = Component.translatable(quality.prefixKey())
                .withStyle(quality.color());
        // 原物品名: getItem().getName(stack) 取该物品的默认名 (跨 mod 通用), 不带已盖的 hoverName 防递归套娃。
        Component base = stack.getItem().getName(stack);
        MutableComponent name = Component.empty()
                .append(prefix)
                .append(Component.literal(" "))
                .append(base)
                .withStyle(Style.EMPTY.withItalic(false).withColor(quality.color()));
        return name;
    }
}
