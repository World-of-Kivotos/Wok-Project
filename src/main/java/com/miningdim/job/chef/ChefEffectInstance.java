package com.miningdim.job.chef;

import net.minecraft.nbt.CompoundTag;

/**
 * 一个已掷出并盖在菜上的效果实例 (Chef_Job_DesignSpec 第六章)。不可变载体: 效果种类 + 该菜达成品质档对应的
 * 强度数值快照 (magnitude 的语义随 {@link #type()} 解释, 见 {@link ChefEffectType} 各项注释)。
 *
 * 为何存数值快照而非动态查表: 盖章时 (做菜) 按厨师当时品质从 config 取列写死进 NBT, 这样
 *  (a) 一旦盖好的菜数值固定, 后续 config 改值不影响存量菜 (避免追溯性平衡漂移);
 *  (b) 吃时 {@link ChefConsumeHandler} 无需重查品质, 直接读 magnitude 结算。
 *
 * 序列化进 ItemStack NBT (由 {@link ChefQualityNbt} 装进效果 ListTag)。
 */
public record ChefEffectInstance(ChefEffectType type, int magnitude) {

    private static final String K_TYPE = "type";
    private static final String K_MAGNITUDE = "mag";

    public ChefEffectInstance {
        if (type == null) {
            throw new IllegalArgumentException("ChefEffectInstance type must not be null");
        }
        if (magnitude < 0) {
            throw new IllegalArgumentException("magnitude must be >= 0, got " + magnitude);
        }
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString(K_TYPE, type.id());
        tag.putInt(K_MAGNITUDE, magnitude);
        return tag;
    }

    /**
     * 从 NBT 还原; 未知 type id (跨版本删了某效果 / 坏数据) 返回 null, 调用方据此跳过该条不结算
     * (不静默回退某默认效果掩盖)。
     */
    public static ChefEffectInstance fromNbt(CompoundTag tag) {
        ChefEffectType type = ChefEffectType.fromId(tag.getString(K_TYPE));
        if (type == null) {
            return null;
        }
        return new ChefEffectInstance(type, tag.getInt(K_MAGNITUDE));
    }
}
