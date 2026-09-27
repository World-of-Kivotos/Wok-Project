package com.miningdim.job.munitions.client.style;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.ClientJobState;
import com.miningdim.job.JobId;
import com.miningdim.job.JobXpCurve;
import com.miningdim.job.munitions.MunitionsCaliber;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPartRarity;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.Locale;
import java.util.Map;

/**
 * 军火台与冲压机两块新界面共用的小工具: 发按钮、命中判定、时长/数量格式化、军火商等级徽章与经验进度、
 * 品质/稀有度色表、弹药展示图行号、提示框配色。纯客户端 (引用 Minecraft)。
 */
public final class GunsmithUi {

    public static final int GUI_W = 360;
    public static final int GUI_H = 240;

    /** 提示框配色 (预览 TIP): 标题白、说明灰、问题红、操作金。 */
    public static final int TIP_TITLE = 0xFFFFFF;
    public static final int TIP_GRAY = 0xA8B3C4;
    public static final int TIP_RED = 0xFF6B6B;
    public static final int TIP_GOLD = 0xFFD479;

    /** 右上角头像/等级块的命中区 (三种风格的外接矩形), 悬停显示军火商经验。 */
    public static final int OWNER_X = 256;
    public static final int OWNER_Y = 5;
    public static final int OWNER_W = 97;
    public static final int OWNER_H = 19;

    /** 现有的弹药展示图 (A/B 两种风格的口径展示区沿用): 每行 462x120 源像素, 画到 154x40 GUI 像素。 */
    public static final ResourceLocation AMMO_PROFILES =
            new ResourceLocation(MiningConstants.MODID, "textures/gui/container/munitions_ammo_profiles.png");
    public static final int AMMO_PROFILE_W = 154;
    public static final int AMMO_PROFILE_H = 40;
    public static final int AMMO_PROFILE_SRC_W = 462;
    public static final int AMMO_PROFILE_SRC_H = 120;
    public static final int AMMO_PROFILE_ROWS = 10;

    /** TaCZ 枪械 HUD 图的标称尺寸 (默认包都是 384x128; 实际分辨率不同也按这个比例归一取源矩形)。 */
    public static final int HUD_W = 384;
    public static final int HUD_H = 128;

    /**
     * 口径 -> 展示图行号 (按枚举名映射, 这样合入更多口径的分支时不需要改这里的签名;
     * 表里没有的口径回退到旧逻辑 clamp(index, 0, 8))。
     */
    private static final Map<String, Integer> AMMO_PROFILE_ROW = Map.ofEntries(
            Map.entry("PISTOL", 0),
            Map.entry("PISTOL_45ACP", 0),
            Map.entry("RIFLE", 1),
            Map.entry("SHOTGUN", 2),
            Map.entry("BATTLE", 3),
            Map.entry("SNIPER", 4),
            Map.entry("SNIPER_3006", 4),
            Map.entry("SNIPER_792", 4),
            Map.entry("SNIPER_303", 4),
            Map.entry("BIG_PISTOL", 5),
            Map.entry("ANTI_MATERIEL", 6),
            Map.entry("EXPLOSIVE", 7),
            Map.entry("SPECIAL", 8),
            Map.entry("RIFLE_556", 9));

    private GunsmithUi() {
    }

    // ------------------------------------------------------------------ input

    /** 发一个菜单按钮 (clickMenuButton id), 与旧界面同一条原版通道, 不新增网络包。 */
    public static void sendButton(AbstractContainerMenu menu, int id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) {
            mc.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    /** 左闭右开矩形命中 [x, x+w) x [y, y+h)。坐标系由调用方统一 (GUI 相对或屏幕均可)。 */
    public static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ formatting

    /**
     * 时长 (tick) -> "m:ss" / "h:mm:ss" (预览 fmtT)。&lt;= 0 显示 "--" (与旧军火台一致: 没有在跑的批次就不显示 0:00)。
     */
    public static String formatTicks(long ticks) {
        if (ticks <= 0) {
            return "--";
        }
        long seconds = Math.max(1L, (ticks + 19L) / 20L);
        long hours = seconds / 3600L;
        long minutes = seconds % 3600L / 60L;
        long secs = seconds % 60L;
        String head = hours > 0 ? hours + ":" + pad2(minutes) : String.valueOf(minutes);
        return head + ":" + pad2(secs);
    }

    /** 千分位整数 (预览 fmtN, en-US 逗号分组): 1240 -> "1,240"。 */
    public static String formatCount(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** FE -> 以百万为单位保留一位小数 (预览 fmtFE): 21_400_000 -> "21.4M"。 */
    public static String formatMegaFe(long fe) {
        return String.format(Locale.ROOT, "%.1fM", fe / 1_000_000.0D);
    }

    /** 两位小数 (品质系数区间等)。 */
    public static String format2(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String pad2(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    // ------------------------------------------------------------------ munitions level

    /** 军火商等级徽章: 名字 (翻译) + 颜色。 */
    public record Badge(Component name, int color) {
    }

    /**
     * 军火商等级 -> 档位徽章 (0-2 低级, 3-4 中级, 5-6 高级, 7-8 极品, 9 超凡, 10 闪耀)。
     * 与军火台六档外观 (基础/中/高/极品/超凡/闪耀) 同一套分档。
     */
    public static Badge tierBadge(int level) {
        String key;
        int color;
        if (level >= 10) {
            key = "radiant";
            color = 0xFFFFD34F;
        } else if (level >= 9) {
            key = "transcendent";
            color = 0xFFB57CF2;
        } else if (level >= 7) {
            key = "superior";
            color = 0xFFEBA34A;
        } else if (level >= 5) {
            key = "high";
            color = 0xFF5AA9F5;
        } else if (level >= 3) {
            key = "medium";
            color = 0xFF57D08B;
        } else {
            key = "basic";
            color = 0xFFB7C0CE;
        }
        return new Badge(Component.translatable("gui.miningdim.gunsmith_ui.tier." + key), color);
    }

    /** "L7" 这类短等级标签 (锁定角标、蓝图风格等级格)。 */
    public static Component levelShort(int level) {
        return Component.translatable("gui.miningdim.gunsmith_ui.level_short", level);
    }

    /** 本机玩家的军火商等级 (客户端镜像, 夹到 [1, 10])。 */
    public static int playerLevel() {
        return Math.max(JobXpCurve.MIN_LEVEL, Math.min(JobXpCurve.MAX_LEVEL, ClientJobState.level(JobId.MUNITIONS)));
    }

    /** 本机玩家的军火商累计经验 (夹到 [0, 毕业经验])。 */
    public static long playerShownXp() {
        return Math.max(0L, Math.min(JobXpCurve.GRADUATION_XP, ClientJobState.xp(JobId.MUNITIONS)));
    }

    /** 下一级所需的累计经验 (满级时是毕业经验)。 */
    public static long playerNextLevelXp() {
        int level = playerLevel();
        if (level >= JobXpCurve.MAX_LEVEL) {
            return JobXpCurve.GRADUATION_XP;
        }
        return JobXpCurve.cumulativeXpForLevel(level + 1);
    }

    /** 本级经验进度 0..1 (满级恒 1)。与旧界面的经验条同一算法。 */
    public static float playerXpFraction() {
        int level = playerLevel();
        long xp = playerShownXp();
        long levelStart = JobXpCurve.cumulativeXpForLevel(level);
        long next = playerNextLevelXp();
        if (level >= JobXpCurve.MAX_LEVEL || next <= levelStart) {
            return 1.0F;
        }
        long inside = Math.max(0L, Math.min(next - levelStart, xp - levelStart));
        return Math.max(0.0F, Math.min(1.0F, (float) inside / (float) (next - levelStart)));
    }

    /** 经验提示 ("经验: 当前/下一级"), 沿用旧界面的键。 */
    public static Component xpTooltip() {
        return Component.translatable("gui.miningdim.munitions.xp_tooltip", playerShownXp(), playerNextLevelXp());
    }

    // ------------------------------------------------------------------ colours

    /** 深底用的品质色 (预览 QUAL.col)。 */
    public static int qualityColor(GunsmithPartQuality quality) {
        return switch (quality) {
            case COMMON -> 0xFFE9EEF7;
            case IMPROVED -> 0xFF47E37C;
            case MILSPEC -> 0xFF56A8FF;
            case PRECISION -> 0xFFC56CFF;
            case LEGENDARY -> 0xFFFF4F5E;
        };
    }

    /** 浅底用的品质色 (预览 QUAL.colL, 学园风格)。 */
    public static int qualityColorLight(GunsmithPartQuality quality) {
        return switch (quality) {
            case COMMON -> 0xFF6F7B90;
            case IMPROVED -> 0xFF1FA255;
            case MILSPEC -> 0xFF1F7BDB;
            case PRECISION -> 0xFF9243D6;
            case LEGENDARY -> 0xFFDD3140;
        };
    }

    /** 稀有度主色 (预览 RAR.main)。 */
    public static int rarityMain(GunsmithPartRarity rarity) {
        return switch (rarity) {
            case STANDARD -> 0xFF8E96A7;
            case MODIFIED -> 0xFF2F77DD;
            case SPECIAL -> 0xFF2FA85C;
            case ADVANCED -> 0xFF9057EA;
            case PROTOTYPE -> 0xFFDE3B3B;
        };
    }

    /** 稀有度亮色 (预览 RAR.light, 深底文字与提示框)。 */
    public static int rarityLight(GunsmithPartRarity rarity) {
        return switch (rarity) {
            case STANDARD -> 0xFFE3E8F1;
            case MODIFIED -> 0xFF82BEFF;
            case SPECIAL -> 0xFF7BE89A;
            case ADVANCED -> 0xFFCDA8FF;
            case PROTOTYPE -> 0xFFFF8A78;
        };
    }

    /** 稀有度色标上的档位刻度数 (1..5)。 */
    public static int rarityBars(GunsmithPartRarity rarity) {
        return rarity.ordinal() + 1;
    }

    // ------------------------------------------------------------------ caliber art

    /** 口径在 {@link #AMMO_PROFILES} 里的行号。 */
    public static int ammoProfileRow(MunitionsCaliber caliber) {
        Integer row = AMMO_PROFILE_ROW.get(caliber.name());
        if (row != null) {
            return row;
        }
        return Math.max(0, Math.min(8, caliber.index()));
    }

    // ------------------------------------------------------------------ tooltip helpers

    /** 一行带颜色的提示 (颜色 0xRRGGBB, 例如 {@link #TIP_GRAY})。 */
    public static Component tip(Component text, int rgb) {
        return text.copy().withStyle(Style.EMPTY.withColor(rgb & 0xFFFFFF));
    }

    /**
     * 缩到最小字号仍放不下时截断并补省略号 (英文名远长于中文)。放得下时原样返回同一个对象,
     * 调用方据此判断"是否截断过"。
     */
    public static Component ellipsize(GsPainter p, Component text, float maxW, float scale, boolean bold) {
        if (p.textWidth(text, scale, bold) <= maxW) {
            return text;
        }
        String full = text.getString();
        String ellipsis = "…";
        int end = full.length();
        while (end > 0 && p.textWidth(full.substring(0, end) + ellipsis, scale, bold) > maxW) {
            end--;
        }
        return Component.literal(full.substring(0, end).trim() + ellipsis);
    }
}
