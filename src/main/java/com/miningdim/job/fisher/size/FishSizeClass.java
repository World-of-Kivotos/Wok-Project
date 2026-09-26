package com.miningdim.job.fisher.size;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 体型档。档位只看本次钓获的标准正态分位 z, 与鱼种无关, 所以每个鱼种的出档概率完全相同:
 * 小 20% / 标准 65% / 大 14% / 奖杯 1%。
 *
 * 物品栈只保存档位, 不保存精确尺寸: 标准档一个标签都不写 (与没测量过的鱼、生物掉落与箱子里的鱼照常堆叠),
 * 小/大只写档位 (同档可堆叠), 只有奖杯写精确长度、重量与钓获者, 刻意独一份。精确尺寸另记在玩家的钓获记录里。
 */
public enum FishSizeClass {
    SMALL("small", ChatFormatting.GRAY),
    STANDARD("standard", ChatFormatting.WHITE),
    LARGE("large", ChatFormatting.GREEN),
    TROPHY("trophy", ChatFormatting.GOLD);

    /** Phi^-1(0.20): 低于此分位为小。 */
    public static final double SMALL_BELOW_Z = -0.8416212335729143;
    /** Phi^-1(0.85): 自此分位起为大。 */
    public static final double LARGE_FROM_Z = 1.0364333894937898;
    /** Phi^-1(0.99): 自此分位起为奖杯。 */
    public static final double TROPHY_FROM_Z = 2.3263478740408408;

    private final String id;
    private final ChatFormatting color;

    FishSizeClass(String id, ChatFormatting color) {
        this.id = id;
        this.color = color;
    }

    public String id() {
        return id;
    }

    public ChatFormatting color() {
        return color;
    }

    public MutableComponent displayName() {
        return Component.translatable("fishing.miningdim.size." + id).withStyle(color);
    }

    public static FishSizeClass forZ(double z) {
        if (z >= TROPHY_FROM_Z) {
            return TROPHY;
        }
        if (z >= LARGE_FROM_Z) {
            return LARGE;
        }
        return z < SMALL_BELOW_Z ? SMALL : STANDARD;
    }

    public static FishSizeClass byId(String id) {
        for (FishSizeClass sizeClass : values()) {
            if (sizeClass.id.equals(id)) {
                return sizeClass;
            }
        }
        return null;
    }
}
