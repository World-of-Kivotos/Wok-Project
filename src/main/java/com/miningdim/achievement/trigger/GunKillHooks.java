package com.miningdim.achievement.trigger;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.util.FakePlayer;

import java.util.function.Predicate;

/**
 * 枪械击杀的判定 (Achievement_System_DesignSpec 6.1 gun_kills / gun_headshot_kills、6.2 gun_kill), 不引用 TaCZ。
 * TaCZ 事件在 {@link TaczGunKillHooks} 里翻译成对本类的调用; 分成两个类, 是为了让没装 TaCZ 的服务器永远不加载任何
 * {@code com.tacz.*} 类, 同时判定本身在没有 TaCZ 的开发环境里也能直接测。
 */
public final class GunKillHooks {

    private GunKillHooks() {
    }

    /**
     * 玩家用枪击杀了一只生物。被击杀者须过 6.4 的击杀过滤 (矿区维度、带实例标记); 射手不能是 FakePlayer。
     * 挂机冻结的射手不加两项计数统计, 但 gun_kill 这类一次性成就照常判定。
     *
     * @param afkFrozen 挂机判据 (事件路径传 {@link KillFilter#isAfkFrozen})
     */
    static void onGunKill(ServerPlayer shooter, LivingEntity victim, boolean headshot,
                          Predicate<ServerPlayer> afkFrozen) {
        if (shooter instanceof FakePlayer || !KillFilter.isInstanceMob(victim)) {
            return;
        }
        if (!afkFrozen.test(shooter)) {
            AchievementStats.award(shooter, AchievementStats.GUN_KILLS, 1);
            if (headshot) {
                AchievementStats.award(shooter, AchievementStats.GUN_HEADSHOT_KILLS, 1);
            }
        }
        AchievementTriggers.GUN_KILL.trigger(shooter, horizontalDistance(shooter, victim), headshot);
    }

    /**
     * 击杀瞬间射手与目标的水平距离 (只算 dx、dz)。TaCZ 不暴露子弹的起点, 只能取击杀瞬间双方的位置; 远距离狙击时双方的
     * 位移可以忽略。
     */
    static double horizontalDistance(Entity shooter, Entity victim) {
        double dx = shooter.getX() - victim.getX();
        double dz = shooter.getZ() - victim.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
