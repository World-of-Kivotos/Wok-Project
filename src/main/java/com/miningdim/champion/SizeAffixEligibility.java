package com.miningdim.champion;

import java.util.Set;

/**
 * 体型词条 (巨大化/缩小化) 附身实体白名单纯逻辑校验 (ChampionStarAffix spec 9A.4 体型词条实体白名单)。
 *
 * 业务约束 (spec 9A.4): 巨大化/缩小化经原版实体缩放改碰撞箱, 仅对【规则矩形碰撞箱的人形/亚人形实体】
 * (僵尸/骷髅/卫道士/玩家形等) 的视觉与命中合理; 附身蜘蛛/史莱姆/末影龙等异形碰撞箱会产生穿地/无法命中/
 * 悬浮等崩坏。故本白名单是生成器装配阶段 (roller) 的硬校验: 非白名单实体 roll 到体型词条则改 roll 同池
 * 等成本词条 (由 {@link AffixRoller} 在候选池装配阶段剔除 SIZE 族实现)。
 *
 * 纯逻辑不 import 任何 Forge/MC 运行时注册表: 输入是形如 "minecraft:zombie" 的完整实体类型 id (由 integration
 * 层经 {@code ForgeRegistries.ENTITY_TYPES.getKey} 反查注册 id 转 String 传入), 输出布尔资格。白名单为主线裁定
 * 的 13 条原版规则碰撞箱实体, 逐字硬编码, 不做运行期扩展 —— datapack 若引入新人形实体须在此显式登记后才可体型化。
 */
public final class SizeAffixEligibility {

    /**
     * 允许体型词条附身的实体类型 id 白名单 (主线裁定; spec 9A.4 "僵尸/骷髅/卫道士/玩家形等规则碰撞箱")。
     * 全为原版人形/亚人形直立碰撞箱实体 (三类僵尸系 + 三类骷髅系 + 掠夺者系 + 女巫 + 猪灵系), 蜘蛛/史莱姆/
     * 末影龙/苦力怕等异形碰撞箱一律不在列。
     */
    private static final Set<String> WHITELIST = Set.of(
            "minecraft:zombie",
            "minecraft:husk",
            "minecraft:drowned",
            "minecraft:zombified_piglin",
            "minecraft:skeleton",
            "minecraft:stray",
            "minecraft:wither_skeleton",
            "minecraft:vindicator",
            "minecraft:pillager",
            "minecraft:evoker",
            "minecraft:witch",
            "minecraft:piglin",
            "minecraft:piglin_brute");

    private SizeAffixEligibility() {
    }

    /**
     * 该实体类型是否允许 roll 体型词条 (巨大化/缩小化)。
     *
     * @param entityTypeId 完整实体类型 id (形如 "minecraft:zombie"); null 视为不合格 —— 装配期反查不到注册 id
     *                     属"未知异形"兜底, 按不给体型处理 (宁可少一词条也不让异形碰撞箱缩放崩坏), 非掩盖空值
     * @return 在白名单内返回 true, 否则 (含 null) false
     */
    public static boolean isEligible(String entityTypeId) {
        if (entityTypeId == null) {
            return false;
        }
        return WHITELIST.contains(entityTypeId);
    }
}
