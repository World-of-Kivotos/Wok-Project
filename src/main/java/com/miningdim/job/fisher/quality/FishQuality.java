package com.miningdim.job.fisher.quality;

import com.miningdim.core.MiningConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

/**
 * 鱼种品质: 每个鱼种一个固定档位, 档位决定物品名颜色 (Rarity)。与厨师给成品菜盖的加工品质是两套, 互不换算;
 * 与每次钓获随机的体型 ({@code job.fisher.size}) 也无关。
 *
 * 归档数据是六个物品标签 {@code #miningdim:fish_quality/<id>}: 原版会把标签同步给客户端, 服务器可用数据包覆写,
 * 不需要自建网络同步。标签由 {@code tools/fishing/build_fish_quality_tags.ps1} 按"几率 + 达成条件"模型生成,
 * 模型与阈值见 docs/Fisher_Job_DesignSpec.md。同一物品落在多个档位标签里时取最高档。
 *
 * 前四档复用原版稀有度; 传说与神话是运行期扩展的稀有度, 名字带 MININGDIM_ 前缀: Forge 的
 * {@code Rarity.create} 按名字忽略大小写去重, 裸用 LEGENDARY 可能静默拿到别的 mod 的同名实例和颜色。
 * 扩展必须发生在 mod 构造期 (见 {@link #bootstrap()}), 两个物理端都要跑, 否则服务端拼出的聊天物品名颜色会分叉。
 */
public enum FishQuality {
    COMMON("common", ChatFormatting.WHITE, Rarity.COMMON),
    FINE("fine", ChatFormatting.YELLOW, Rarity.UNCOMMON),
    RARE("rare", ChatFormatting.AQUA, Rarity.RARE),
    EPIC("epic", ChatFormatting.LIGHT_PURPLE, Rarity.EPIC),
    LEGENDARY("legendary", ChatFormatting.GOLD, Rarity.create("MININGDIM_LEGENDARY", ChatFormatting.GOLD)),
    MYTHIC("mythic", ChatFormatting.RED, Rarity.create("MININGDIM_MYTHIC", ChatFormatting.RED));

    private static final FishQuality[] HIGHEST_FIRST = {MYTHIC, LEGENDARY, EPIC, RARE, FINE, COMMON};

    private final String id;
    private final ChatFormatting color;
    private final Rarity rarity;
    private final TagKey<Item> tag;

    FishQuality(String id, ChatFormatting color, Rarity rarity) {
        this.id = id;
        this.color = color;
        this.rarity = rarity;
        this.tag = TagKey.create(Registries.ITEM, new ResourceLocation(MiningConstants.MODID, "fish_quality/" + id));
    }

    /** 在 mod 构造期触发类初始化, 把两档扩展稀有度建在任何物品查询之前。 */
    public static void bootstrap() {
        // 调用本方法即完成枚举常量 (含 Rarity.create) 的初始化, 方法体本身无事可做。
    }

    public String id() {
        return id;
    }

    public ChatFormatting color() {
        return color;
    }

    public Rarity rarity() {
        return rarity;
    }

    public TagKey<Item> tag() {
        return tag;
    }

    public String translationKey() {
        return "fishing.miningdim.quality." + id;
    }

    public MutableComponent displayName() {
        return Component.translatable(translationKey()).withStyle(color);
    }

    /** 该物品所在的最高档位; 不是已归档的鱼 (或标签尚未绑定) 时返回 null。 */
    public static FishQuality of(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        for (FishQuality quality : HIGHEST_FIRST) {
            if (stack.is(quality.tag)) {
                return quality;
            }
        }
        return null;
    }

    /** 供 {@code core.ItemRarityOverrides} 调用的解析器: 已归档的鱼返回档位稀有度, 其余物品不认领。 */
    public static Rarity rarityOverride(ItemStack stack) {
        FishQuality quality = of(stack);
        return quality == null ? null : quality.rarity;
    }

    public static FishQuality byId(String id) {
        for (FishQuality quality : values()) {
            if (quality.id.equals(id)) {
                return quality;
            }
        }
        return null;
    }
}
