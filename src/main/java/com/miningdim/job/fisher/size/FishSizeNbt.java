package com.miningdim.job.fisher.size;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.UUID;

/**
 * 鱼的体型标签, 挂在物品 NBT 根下的 {@value #ROOT} 复合标签里, 与厨师的 MiningChef 标签互不覆盖
 * (两边都用 getOrCreateTag().put, 不整体替换 tag)。
 *
 * 标准档不写任何东西; 小/大只写 {v, cls}; 奖杯额外写 mm (体长毫米)、mg (体重毫克)、by/byName (钓获者) 与
 * t (钓获时的世界 gameTime)。尺寸只放在 NBT, 绝不写进自定义物品名: 自定义名会让配方书自动填充跳过这组鱼,
 * 还会被厨师盖章整个覆盖。
 */
public final class FishSizeNbt {
    public static final String ROOT = "MiningFish";
    public static final byte FORMAT_VERSION = 1;
    private static final String VERSION = "v";
    private static final String CLASS = "cls";
    private static final String LENGTH_MM = "mm";
    private static final String WEIGHT_MG = "mg";
    private static final String CATCHER = "by";
    private static final String CATCHER_NAME = "byName";
    private static final String CAUGHT_AT = "t";

    private FishSizeNbt() {
    }

    /** 已经做过体型结算 (小/大/奖杯) 的鱼; 标准档没有标签, 与未测量的鱼无法区分, 这是有意为之。 */
    public static boolean isMeasured(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(ROOT, Tag.TAG_COMPOUND);
    }

    public static void stamp(ItemStack stack, FishMeasurement measurement, UUID catcher, String catcherName, long gameTime) {
        if (measurement.sizeClass() == FishSizeClass.STANDARD) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putByte(VERSION, FORMAT_VERSION);
        data.putString(CLASS, measurement.sizeClass().id());
        if (measurement.sizeClass() == FishSizeClass.TROPHY) {
            data.putInt(LENGTH_MM, measurement.lengthMm());
            data.putLong(WEIGHT_MG, measurement.weightMg());
            if (catcher != null) {
                data.putUUID(CATCHER, catcher);
            }
            data.putString(CATCHER_NAME, catcherName == null ? "" : catcherName);
            data.putLong(CAUGHT_AT, gameTime);
        }
        stack.getOrCreateTag().put(ROOT, data);
    }

    /** 读不出合法档位的标签按"没有标签"处理, 不抛异常: tooltip 在客户端渲染线程上调用, 不能被坏数据打断。 */
    public static Optional<Info> read(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ROOT, Tag.TAG_COMPOUND)) {
            return Optional.empty();
        }
        CompoundTag data = tag.getCompound(ROOT);
        FishSizeClass sizeClass = FishSizeClass.byId(data.getString(CLASS));
        if (sizeClass == null || sizeClass == FishSizeClass.STANDARD) {
            return Optional.empty();
        }
        if (sizeClass != FishSizeClass.TROPHY) {
            return Optional.of(new Info(sizeClass, 0, 0L, ""));
        }
        int lengthMm = data.getInt(LENGTH_MM);
        long weightMg = data.getLong(WEIGHT_MG);
        if (lengthMm <= 0 || weightMg <= 0L) {
            return Optional.empty();
        }
        return Optional.of(new Info(sizeClass, lengthMm, weightMg, data.getString(CATCHER_NAME)));
    }

    /** 奖杯才有精确尺寸与钓获者; 小/大的 lengthMm/weightMg 为 0。 */
    public record Info(FishSizeClass sizeClass, int lengthMm, long weightMg, String catcherName) {
    }
}
