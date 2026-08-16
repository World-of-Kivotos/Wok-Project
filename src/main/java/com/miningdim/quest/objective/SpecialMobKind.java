package com.miningdim.quest.objective;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;

/**
 * 原版里"不是独立 EntityType, 但玩家一眼认得出"的稀有变体。
 *
 * 单独立一个枚举而不是塞进 {@link KillEntityObjective}: 这些目标的判据是<b>组合条件</b> (幼年 + 骑乘某种坐骑 /
 * 被雷劈过), 用 EntityType 表达不了。反过来, 凋灵、远古守卫者、唤魔者、猪灵蛮兵这些本身就是独立 EntityType 的
 * 稀有怪照旧用 {@link KillEntityObjective}, 不必进本枚举。
 *
 * 判据都取击杀瞬间的状态: 骑士类要求<b>击杀骑乘者本人</b> (先把坐骑打掉会让骑乘者下马, 那时它就只是普通怪),
 * 这与玩家对"打掉了一个小鸡骑士"的直觉一致。
 */
public enum SpecialMobKind {

    /** 小鸡骑士: 幼年僵尸骑鸡。约占僵尸生成的 0.25% (5% 幼年, 其中 5% 骑鸡)。 */
    CHICKEN_JOCKEY("小鸡骑士") {
        @Override
        public boolean matches(LivingEntity victim) {
            return victim instanceof Zombie zombie && zombie.isBaby() && zombie.getVehicle() instanceof Chicken;
        }
    },

    /** 蜘蛛骑士: 骷髅骑蜘蛛 (含凋灵骷髅变体, 故判 AbstractSkeleton)。约占蜘蛛生成的 1%。 */
    SPIDER_JOCKEY("蜘蛛骑士") {
        @Override
        public boolean matches(LivingEntity victim) {
            return victim instanceof AbstractSkeleton && victim.getVehicle() instanceof Spider;
        }
    },

    /** 闪电苦力怕: 被雷击充能过的苦力怕。 */
    CHARGED_CREEPER("闪电苦力怕") {
        @Override
        public boolean matches(LivingEntity victim) {
            return victim instanceof Creeper creeper && creeper.isPowered();
        }
    };

    private final String displayName;

    SpecialMobKind(String displayName) {
        this.displayName = displayName;
    }

    /** 面向玩家的中文名。 */
    public String displayName() {
        return displayName;
    }

    /** 该被击杀的生物是否属于本变体。 */
    public abstract boolean matches(LivingEntity victim);

    /** 任一变体是否命中 (供"击杀任意稀有怪"式的复合任务用)。 */
    public static boolean matchesAny(LivingEntity victim) {
        for (SpecialMobKind kind : values()) {
            if (kind.matches(victim)) {
                return true;
            }
        }
        return false;
    }
}
