package com.miningdim.job.brewer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * 酒的品质/年份 NBT 盖章/读取工具 (沿用厨师 {@link com.miningdim.job.chef.ChefQualityNbt} 范式: 只盖 ItemStack
 * tag 子 compound, 不为每个品质另注册变体物品)。
 *
 * 酒类型即物品身份 (九种酒各一 item), 故 NBT 只存品质 + 年份 (+ 酿造者 UUID 审计); 类型由物品自身决定, 不重复存。
 * 年份用 double 存 (陈酿按 gameTime tick 差累加小数年份, 见 {@link VintageClock}); 喝酒结算按 {@link #strength}
 * 取强度 S = 年份 × 品质系数。
 */
public final class WineNbt {

    private WineNbt() {
    }

    /** ItemStack tag 下的酿酒子 compound 键。 */
    static final String ROOT_TAG = "MiningBrewer";
    private static final String K_QUALITY = "quality";
    private static final String K_VINTAGE = "vintage";
    private static final String K_BREWER = "brewer";

    /** 给基酒盖品质章 (年份初始 0; 酿酒台产出时调用)。原地修改 stack 的 tag。 */
    public static void stamp(ItemStack stack, WineQuality quality, UUID brewer) {
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("cannot stamp empty stack");
        }
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        CompoundTag root = new CompoundTag();
        root.putString(K_QUALITY, quality.id());
        root.putDouble(K_VINTAGE, 0.0D);
        if (brewer != null) {
            root.putUUID(K_BREWER, brewer);
        }
        stack.getOrCreateTag().put(ROOT_TAG, root);
    }

    private static CompoundTag root(ItemStack stack) {
        if (!stack.hasTag()) {
            return null;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ROOT_TAG, Tag.TAG_COMPOUND)) {
            return null;
        }
        return tag.getCompound(ROOT_TAG);
    }

    /** 是否带酒章 (品质已盖; 陈酿/喝酒入口快速判定)。 */
    public static boolean isWine(ItemStack stack) {
        return readQuality(stack) != null;
    }

    /** 读品质 (无章返回 null, 调用方短路 —— 非酒不结算)。 */
    public static WineQuality readQuality(ItemStack stack) {
        CompoundTag r = root(stack);
        return r == null ? null : WineQuality.fromId(r.getString(K_QUALITY));
    }

    /** 读年份 (无章返回 0)。 */
    public static double readVintage(ItemStack stack) {
        CompoundTag r = root(stack);
        return r == null ? 0.0D : r.getDouble(K_VINTAGE);
    }

    /** 写年份 (陈酿结算用; 无章则忽略, 不静默建章)。 */
    public static void setVintage(ItemStack stack, double years) {
        CompoundTag r = root(stack);
        if (r != null) {
            r.putDouble(K_VINTAGE, Math.max(0.0D, years));
        }
    }

    /** 累加年份 (返回累加后的值; 无章返回 0)。 */
    public static double addVintage(ItemStack stack, double deltaYears) {
        CompoundTag r = root(stack);
        if (r == null) {
            return 0.0D;
        }
        double v = Math.max(0.0D, r.getDouble(K_VINTAGE) + deltaYears);
        r.putDouble(K_VINTAGE, v);
        return v;
    }

    /** 读酿造者 UUID (无则 null; 审计/显示用)。 */
    public static UUID readBrewer(ItemStack stack) {
        CompoundTag r = root(stack);
        return (r != null && r.hasUUID(K_BREWER)) ? r.getUUID(K_BREWER) : null;
    }

    /** 强度值 S = 年份 × 品质系数 (喝酒增益强度的统一入口; 无章返回 0)。 */
    public static double strength(ItemStack stack) {
        WineQuality q = readQuality(stack);
        if (q == null) {
            return 0.0D;
        }
        return readVintage(stack) * q.coefficient();
    }
}
