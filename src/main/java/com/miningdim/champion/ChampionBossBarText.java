package com.miningdim.champion;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.BossEvent;

import java.util.List;

/**
 * 精英怪 BOSS 血条文本/样式纯逻辑 (ChampionStarAffix spec 9.7 显示层: vanilla {@code ServerBossEvent} 名/色/分段)。
 *
 * 把"名字 + 星级 + 词条名"拼成 BOSS 条标题, 并按星级映射 条颜色 / 分段样式 / 血量进度。无世界 / 无 Champions 引用,
 * 纯函数可 GameTest; 真数据 (rank/affixes) 的读取与 {@code ServerBossEvent} 生命周期在 integration 包的
 * ChampionBossBarHandler (Champions 已加载时挂)。BOSS 条本身是 vanilla 服务端机制, 加玩家即自动同步渲染, 故无客户端代码。
 */
public final class ChampionBossBarText {

    private ChampionBossBarText() {
    }

    /** 标题里 ★ 符号最大显示个数 (超出用 "★xN" 紧凑表示防标题超宽)。 */
    public static final int MAX_STAR_GLYPHS = 5;

    /**
     * BOSS 条标题 = 名字 + 星级 + 词条名列表 (中间分隔 " . ")。tier 1-10; affixNames 已解析为可显示 Component (词条名)。
     * 无词条时只显示 名字 + 星级。
     */
    public static MutableComponent title(Component championName, int tier, List<Component> affixNames) {
        MutableComponent out = Component.empty().append(championName).append(Component.literal(" ")).append(stars(tier));
        if (affixNames != null && !affixNames.isEmpty()) {
            out.append(Component.literal("  "));
            for (int i = 0; i < affixNames.size(); i++) {
                if (i > 0) {
                    out.append(Component.literal(" · "));
                }
                out.append(affixNames.get(i));
            }
        }
        return out;
    }

    /** 星级符号: tier<=MAX 用 ★xtier, 否则 "★x{tier}" 紧凑 (防 10★ 标题过宽)。 */
    static Component stars(int tier) {
        int t = Math.max(0, tier);
        if (t <= MAX_STAR_GLYPHS) {
            return Component.literal("★".repeat(t));
        }
        return Component.literal("★x" + t);
    }

    /** 星级 -> BOSS 条颜色 (低星白/绿, 中星黄, 高星红/紫; 视觉随星级升级)。 */
    public static BossEvent.BossBarColor colorForTier(int tier) {
        if (tier >= 9) {
            return BossEvent.BossBarColor.PURPLE;
        }
        if (tier >= 7) {
            return BossEvent.BossBarColor.RED;
        }
        if (tier >= 5) {
            return BossEvent.BossBarColor.YELLOW;
        }
        if (tier >= 3) {
            return BossEvent.BossBarColor.GREEN;
        }
        return BossEvent.BossBarColor.WHITE;
    }

    /**
     * 把任意 RGB (Champions rank 的展示色) 映射到最近的 vanilla BOSS 条颜色 (BossBarColor 仅 7 固定色)。
     * 用途: 让 条色 ≈ 文字色 ≈ 粒子色 三者统一 (文字与粒子都已是 rank 色, 条色取最近的离散 BossBarColor)。
     * 按 RGB 欧氏距离取最近的一个。
     */
    public static BossEvent.BossBarColor nearestBossBarColor(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        BossEvent.BossBarColor best = BossEvent.BossBarColor.WHITE;
        long bestDist = Long.MAX_VALUE;
        for (BossEvent.BossBarColor c : BossEvent.BossBarColor.values()) {
            int ref = referenceRgb(c);
            long dr = r - ((ref >> 16) & 0xFF);
            long dg = g - ((ref >> 8) & 0xFF);
            long db = b - (ref & 0xFF);
            long dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = c;
            }
        }
        return best;
    }

    /** 7 个 vanilla BOSS 条颜色的代表 RGB (供 {@link #nearestBossBarColor} 取最近)。 */
    private static int referenceRgb(BossEvent.BossBarColor color) {
        switch (color) {
            case PINK:
                return 0xFF69B4;
            case BLUE:
                return 0x4169E1;
            case RED:
                return 0xFF2020;
            case GREEN:
                return 0x30C030;
            case YELLOW:
                return 0xF0F020;
            case PURPLE:
                return 0xA020F0;
            case WHITE:
            default:
                return 0xFFFFFF;
        }
    }

    /** 星级 -> 分段样式 (spec 9.7 高星血条分段: 8★+ 10 段, 6-7★ 6 段, 低星不分段)。 */
    public static BossEvent.BossBarOverlay overlayForTier(int tier) {
        if (tier >= 8) {
            return BossEvent.BossBarOverlay.NOTCHED_10;
        }
        if (tier >= 6) {
            return BossEvent.BossBarOverlay.NOTCHED_6;
        }
        return BossEvent.BossBarOverlay.PROGRESS;
    }

    /** 血量进度 [0,1] (currentHp/maxHp 钳; maxHp<=0 返 0 防除零)。6★+ 传血池 displayHealth/1024, 否则 vanilla 血/最大血。 */
    public static float progress(double currentHp, double maxHp) {
        if (maxHp <= 0.0D) {
            return 0.0F;
        }
        double p = currentHp / maxHp;
        return (float) Math.max(0.0D, Math.min(1.0D, p));
    }

    // ============================================================
    // BA 式多管血条 (2026-07-07 用户定向): 血量按"每管容量"切管, 条显当前管占比 + 标题尾缀 xN + 逐管换色。
    // ============================================================

    /**
     * 每管容量表 (2026-07-07 用户拍板"3★ 巨大化起管"): 1-5★ 每管 = 该星裸怪基础血 —— 语义 = 管数即"这怪相当于
     * 几只本星裸怪", 裸怪恰 1 管, 低星只有巨大化能破 1 管 (3★ 巨大化 COMMON 468/360 = 2 管 x1); 6★+ 按世界 BOSS
     * 排面收窄 (裸怪 2/3/6/7/10 管, 10★ 巨大化闪耀 204,400 = 28 管 x27)。
     */
    public static double layerCapacityFor(int tier) {
        return switch (tier) {
            case 1 -> 135.0D;
            case 2 -> 225.0D;
            case 3 -> 360.0D;
            case 4 -> 540.0D;
            case 5 -> 765.0D;
            case 6 -> 1_350.0D;
            case 7 -> 2_000.0D;
            case 8 -> 4_500.0D;
            case 9 -> 6_500.0D;
            case 10 -> 7_300.0D;
            default -> throw new IllegalArgumentException("tier out of [1,10]: " + tier);
        };
    }

    /**
     * 剩余管数 (含当前管): ceil(currentHp / capacity)。恰在管界 (currentHp = k×容量) 时算 k 管且当前管满
     * (打掉最后一滴才破管)。死 (currentHp<=0) 为 0 管。
     */
    public static int layersLeft(double currentHp, double capacity) {
        requireCapacity(capacity);
        if (currentHp <= 0.0D) {
            return 0;
        }
        return (int) Math.ceil(currentHp / capacity);
    }

    /** 当前管内进度 [0,1]: (currentHp - 已满管血量) / 容量; 管界恰 1.0; 死 0。 */
    public static float layerProgress(double currentHp, double capacity) {
        requireCapacity(capacity);
        int layers = layersLeft(currentHp, capacity);
        if (layers == 0) {
            return 0.0F;
        }
        double withinLayer = currentHp - (layers - 1) * capacity;
        return (float) Math.max(0.0D, Math.min(1.0D, withinLayer / capacity));
    }

    /**
     * 管色循环 (BA 式逐管换色): 按剩余管数取色, 打破一管即换色 (玩家清晰感知破管)。锚定尾管 —— 最后一管恒红
     * (最危险), 倒数第二恒黄, 依次外推 7 色循环; 相邻管必不同色。仅多管怪 (满血 ≥2 管) 用本色, 单管怪维持
     * 星级 signature 色 ({@link #nearestBossBarColor}) 三色统一。
     */
    public static BossEvent.BossBarColor layerColor(int layersLeft) {
        if (layersLeft < 1) {
            throw new IllegalArgumentException("layersLeft must be >= 1 for color, got " + layersLeft);
        }
        BossEvent.BossBarColor[] palette = {
                BossEvent.BossBarColor.RED,
                BossEvent.BossBarColor.YELLOW,
                BossEvent.BossBarColor.GREEN,
                BossEvent.BossBarColor.BLUE,
                BossEvent.BossBarColor.PURPLE,
                BossEvent.BossBarColor.PINK,
                BossEvent.BossBarColor.WHITE};
        return palette[(layersLeft - 1) % palette.length];
    }

    /** 标题管数尾缀: 当前管之后还剩几管 (≥1 管时 " x{N}", BA 式); 只剩当前管/单管怪返回空串。 */
    public static String layerSuffix(int layersLeft) {
        if (layersLeft >= 2) {
            return " x" + (layersLeft - 1);
        }
        return "";
    }

    private static void requireCapacity(double capacity) {
        if (!(capacity > 0.0D) || Double.isNaN(capacity)) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
    }
}
