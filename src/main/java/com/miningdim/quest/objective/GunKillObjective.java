package com.miningdim.quest.objective;

import com.miningdim.quest.QuestFacts;
import com.miningdim.quest.QuestObjective;

/**
 * 用 TaCZ 枪械按指定条件击杀 N 只生物。隐藏任务线"神射手"的主力判据。
 *
 * 三个可选约束 (枪型 / 爆头 / 最小距离) 合在一个目标里而不是拆成三个类: 它们在任务线里总是组合出现
 * ("50 米外用狙击枪爆头击杀"), 拆开会逼出一个组合器抽象, 复杂度与问题规模不匹配。
 *
 * <b>全部约束都取自 TaCZ 的权威判定, 无自研几何近似。</b> 爆头即 TaCZ 自己的 {@code isHeadShot()} (按配置的
 * 头部 AABB + 延迟补偿盒算), 距离是击杀瞬间射手与目标的直线距离。TaCZ 1.1.8 的事件层不暴露四肢部位, 故本类
 * 没有"命中腿部"一类判据 —— 那需要自研射线-包围盒分段, 精度低于 TaCZ 自身判定且只对人形怪有意义。
 *
 * @param gunType        限定的 TaCZ 枪械分类 (pistol / sniper / rifle / shotgun / smg / rpg / mg);
 *                       <b>null 表示不限枪型</b>。比对忽略大小写。
 * @param requireHeadshot 是否要求爆头
 * @param minDistance    最小击杀距离 (米); 0 表示不限
 * @param requiredCount  需要击杀的只数
 */
public record GunKillObjective(String gunType, boolean requireHeadshot, double minDistance, int requiredCount)
        implements QuestObjective {

    public GunKillObjective {
        if (gunType != null && gunType.isBlank()) {
            // 空串既不是"不限"也不是合法分类, 是调用方拼错了 —— 让它痛, 别退化成静默不限。
            throw new IllegalArgumentException("gunType must be null (any) or a non-blank TaCZ gun type");
        }
        if (minDistance < 0) {
            throw new IllegalArgumentException("minDistance must be >= 0, got " + minDistance);
        }
        if (requiredCount < 1) {
            throw new IllegalArgumentException("requiredCount must be >= 1, got " + requiredCount);
        }
    }

    /** 用指定枪型击杀 N 只 (不要求爆头, 不限距离)。 */
    public static GunKillObjective ofType(String gunType, int requiredCount) {
        return new GunKillObjective(gunType, false, 0, requiredCount);
    }

    /** 用指定枪型爆头击杀 N 只 (不限距离)。 */
    public static GunKillObjective headshot(String gunType, int requiredCount) {
        return new GunKillObjective(gunType, true, 0, requiredCount);
    }

    /** 在 minDistance 米外用指定枪型爆头击杀 N 只。 */
    public static GunKillObjective longRangeHeadshot(String gunType, double minDistance, int requiredCount) {
        return new GunKillObjective(gunType, true, minDistance, requiredCount);
    }

    @Override
    public String describe() {
        StringBuilder text = new StringBuilder();
        if (minDistance > 0) {
            text.append((long) minDistance).append(" 米外");
        }
        text.append("用").append(gunType == null ? "枪械" : localizedGunType(gunType));
        if (requireHeadshot) {
            text.append("爆头");
        }
        text.append("击杀 x").append(requiredCount);
        return text.toString();
    }

    @Override
    public int match(QuestFacts facts) {
        if (!(facts instanceof QuestFacts.GunKill kill)) {
            return 0;
        }
        if (requireHeadshot && !kill.headshot()) {
            return 0;
        }
        if (kill.distance() < minDistance) {
            return 0;
        }
        if (gunType == null) {
            return 1;
        }
        // gunType 为 null 表示 TaCZ 资源索引没能解析出该枪的分类; 限定枪型时一律不计入, 不猜。
        return gunType.equalsIgnoreCase(kill.gunType()) ? 1 : 0;
    }

    /**
     * TaCZ 分类字符串转中文显示名。未收录的分类原样回显 —— 整合包可能装了自定义分类的枪械资源包,
     * 回显原文比显示"未知武器"更有助于玩家自己对上号。
     */
    static String localizedGunType(String type) {
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "pistol" -> "手枪";
            case "sniper" -> "狙击枪";
            case "rifle" -> "步枪";
            case "shotgun" -> "霰弹枪";
            case "smg" -> "冲锋枪";
            case "rpg" -> "火箭筒";
            case "mg" -> "机枪";
            default -> type;
        };
    }
}
