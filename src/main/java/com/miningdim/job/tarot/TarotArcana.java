package com.miningdim.job.tarot;

import net.minecraft.resources.ResourceLocation;

/**
 * 22 张大阿卡纳 0-XXI (TarotReader spec 第六章全表)。本枚举只承载 "牌的身份" (cardId 0-21 + 稳定 id +
 * datapack 资源路径); 该牌正位/逆位/闪耀的实际数值表从 datapack JSON 加载 (spec 第十一章: 每张牌一份,
 * 仿 ORE_USE_DATAPACK 先例, 缺字段报错冒泡)。
 *
 * cardId 即 {@link #ordinal()} (0-21), 是 {@link TarotCardItem} NBT 三键之一与贴图索引; 不得重排成员顺序。
 * datapack 路径: data/miningdim/tarot/cards/&lt;cardId 两位&gt;_&lt;id&gt;.json (spec resources 清单)。
 */
public enum TarotArcana {

    FOOL("fool"),
    MAGICIAN("magician"),
    HIGH_PRIESTESS("high_priestess"),
    EMPRESS("empress"),
    EMPEROR("emperor"),
    HIEROPHANT("hierophant"),
    LOVERS("lovers"),
    CHARIOT("chariot"),
    STRENGTH("strength"),
    HERMIT("hermit"),
    WHEEL_OF_FORTUNE("wheel_of_fortune"),
    JUSTICE("justice"),
    HANGED_MAN("hanged_man"),
    DEATH("death"),
    TEMPERANCE("temperance"),
    DEVIL("devil"),
    TOWER("tower"),
    STAR("star"),
    MOON("moon"),
    SUN("sun"),
    JUDGEMENT("judgement"),
    WORLD("world");

    /** 大阿卡纳总数 (cardId 取值域 [0,21])。 */
    public static final int COUNT = 22;

    private final String id;

    TarotArcana(String id) {
        this.id = id;
    }

    /** cardId (0-21) = 持久化值与贴图索引。 */
    public int cardId() {
        return ordinal();
    }

    /** 小写稳定 id (lang key / datapack 文件名后半段)。 */
    public String id() {
        return id;
    }

    /** datapack 牌效表资源路径 miningdim:tarot/cards/&lt;NN&gt;_&lt;id&gt; (SimpleJsonResourceReloadListener 键)。 */
    public ResourceLocation dataKey() {
        return new ResourceLocation("miningdim", String.format("tarot/cards/%02d_%s", ordinal(), id));
    }

    /** 按 cardId 反查; 越界 (脏 NBT) 抛出冒泡, 由 use 边界兜底 (异常纪律)。 */
    public static TarotArcana byId(int cardId) {
        if (cardId < 0 || cardId >= COUNT) {
            throw new IllegalArgumentException("cardId out of range [0,21]: " + cardId);
        }
        return values()[cardId];
    }
}
