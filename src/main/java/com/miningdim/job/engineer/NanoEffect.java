package com.miningdim.job.engineer;

/**
 * 四个纳米特效 (MillenniumEngineer_Mod_DesignSpec 6.2)。特效是修复掷出的一次性副产品, 非永久词条,
 * 存自定义 ItemStack NBT (不写原版 Enchantments), 再次纳米修复时清空 (6.1)。
 *
 * 全部战斗向以 % 最大血量 / % 最大耐久 建模 (部署环境 80 血铁律):
 *  - RESHAPE 纳米重塑: 回护甲自身耐久; 按件独立, 安全。
 *  - VITALITY 纳米机能修复: 回穿戴者血量 (% 最大血量); 按件 + 递减安全阀 100/50/25/12.5% 防滚雪球。
 *  - SHIELD  纳米多重护盾: 触发后 X 秒全免疫; 每 60s 生成一次, 5 次用尽; 按件各自 5 次, 有界。
 *  - TOTEM   纳米末影心肺反应器: 拦截致死, 复活到 % 最大血量; 人级共享 CD 30min; 叠穿=冗余非多命。
 */
public enum NanoEffect {

    RESHAPE("reshape"),
    VITALITY("vitality"),
    SHIELD("shield"),
    TOTEM("totem");

    private final String id;

    NanoEffect(String id) {
        this.id = id;
    }

    /** 稳定小写 id (NBT 列表元素 / 粒子分支用)。 */
    public String id() {
        return id;
    }

    /** 按 id 反查; 未知 id 返回 null (NBT 读回兜底, 调用方据此跳过未知特效, 不崩)。 */
    public static NanoEffect byId(String id) {
        for (NanoEffect e : values()) {
            if (e.id.equals(id)) {
                return e;
            }
        }
        return null;
    }
}
