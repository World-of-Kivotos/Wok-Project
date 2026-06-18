package com.miningdim.job.tarot;

/**
 * 塔罗牌 5 品质 (TarotReader spec 第一/三章)。承载三件事:
 *  1. 档位索引 a/b/c/d = R/SR/SSR/UR (闪耀不取档位, 走牌专属签名大招); 见 {@link #tierIndex()}。
 *  2. 用牌等级门控 (spec 9.4): L1 用 R / L3 用 SR / L5 用 SSR / L8 用 UR / L10 用 闪耀; 见 {@link #requiredLevel()}。
 *  3. 合成链顺序 (spec 第八章): R->SR->SSR->UR->闪耀; 见 {@link #next()}。
 *
 * 稳定序号 (ordinal) 即 NBT 持久化值 (单一 {@link TarotCardItem} 三键之一), 不得重排成员顺序 (会废旧存档牌)。
 */
public enum TarotQuality {

    /** 低级 R: 档 a, 门控 L1。 */
    R("r", 0, 1),
    /** 中级 SR: 档 b, 门控 L3。 */
    SR("sr", 1, 3),
    /** 高级 SSR: 档 c, 门控 L5。 */
    SSR("ssr", 2, 5),
    /** 超凡 UR: 档 d, 门控 L8。 */
    UR("ur", 3, 8),
    /** 闪耀: 签名大招品质, 门控 L10; 无档位索引 (tierIndex 返回 -1)。 */
    SHINY("shiny", -1, 10);

    private final String id;
    private final int tierIndex;
    private final int requiredLevel;

    TarotQuality(String id, int tierIndex, int requiredLevel) {
        this.id = id;
        this.tierIndex = tierIndex;
        this.requiredLevel = requiredLevel;
    }

    /** 小写稳定 id (lang key / datapack 字段名)。 */
    public String id() {
        return id;
    }

    /** 四档缩放索引 a/b/c/d = 0/1/2/3; 闪耀返回 -1 (走签名大招分支, 不读四档)。 */
    public int tierIndex() {
        return tierIndex;
    }

    /** 用牌门控所需塔罗师等级 (spec 9.4)。 */
    public int requiredLevel() {
        return requiredLevel;
    }

    /** 合成链中本品质的上一档 (spec 第八章 R->SR->SSR->UR->闪耀); 闪耀已是顶档返回 null。 */
    public TarotQuality next() {
        int ord = ordinal();
        if (ord + 1 >= values().length) {
            return null;
        }
        return values()[ord + 1];
    }

    /** 按持久化 ordinal 反查; 越界 (脏 NBT) 抛出, 由 use/合成边界兜底 (异常纪律: 不静默回退默认)。 */
    public static TarotQuality byOrdinal(int ord) {
        TarotQuality[] all = values();
        if (ord < 0 || ord >= all.length) {
            throw new IllegalArgumentException("TarotQuality ordinal out of range: " + ord);
        }
        return all[ord];
    }

    /** 按小写 id 反查; 未知 (datapack 拼写错误) 抛出冒泡, 不静默给默认 (spec 第十一章 C9)。 */
    public static TarotQuality byId(String id) {
        for (TarotQuality q : values()) {
            if (q.id.equals(id)) {
                return q;
            }
        }
        throw new IllegalArgumentException("Unknown TarotQuality id: " + id);
    }
}
