package com.miningdim.champion;

import com.miningdim.core.Difficulty;
import net.minecraft.world.entity.Mob;

/**
 * 压力刷怪 -> 精英怪升格的跨子系统接线点 (seam holder, 同 {@link com.miningdim.pressure.DangerJobFactor} /
 * {@code entrance.EntranceHooks} 范式)。模块化铁律 2: 压力子系统不得硬 import champion 实现类, 故在此暴露一个
 * 绑定点 —— champion 子系统在服务端启动期 {@link #bind} 注入升格实现 (走真 Champions API), 压力子系统在
 * {@code MobPressureSystem.spawnMob} 成功落地一只怪后调 {@link #promote} 把它按矿洞难度升格为冠军。
 *
 * compileOnly 铁律: 本类是纯逻辑层, 不 import 任何 top.theillusivec4.champions.* —— 注入的 {@link Promoter}
 * 实现落在 {@code com.miningdim.champion.integration} 隔离包 (唯一触 Champions 的点), 且只在
 * {@code ModList.isLoaded("champions")} 为真时由 ChampionSystem 装配。Champions 未加载 (dev / 未装) 时
 * provider 恒为空, {@link #promote} 直接短路返回, 刷怪照常进行 (无冠军升格), 不抛、不打断刷怪评估。
 *
 * 绑定/解绑只在主线程的启动/停止事件发生, 运行期只读 (volatile 防可见性), 故无需加锁。
 */
public final class ChampionSpawnSeam {

    private ChampionSpawnSeam() {
    }

    /**
     * 升格实现 (champion 子系统启动期注入)。把一只已落地的 mob 按矿洞难度升格为某星级冠军 + 盖章词条。
     * 实现方负责: 守 ModList -> 取 IChampion capability -> 按 difficulty 掷星 -> 用 PointBudget 选词条 ->
     * ChampionBuilder.spawnPreset 盖章。失败 (非生物/无 capability/星级 roll 失败) 实现方内部吞掉只记日志,
     * 不向压力子系统冒泡 (刷怪不因升格失败而中断)。
     */
    @FunctionalInterface
    public interface Promoter {
        void promote(Mob mob, Difficulty difficulty);
    }

    private static volatile Promoter promoter;

    /** champion 子系统启动期注入升格实现 (null 抛 IllegalArgumentException; 仅 ModList 守卫为真时调用)。 */
    public static void bind(Promoter impl) {
        if (impl == null) {
            throw new IllegalArgumentException("ChampionSpawnSeam.Promoter must not be null");
        }
        promoter = impl;
    }

    /** 服务端停止时清空, 防跨存档/跨重启脏引用 (供 ServerStoppingEvent 调用)。 */
    public static void unbind() {
        promoter = null;
    }

    /** 是否已接入升格实现 (Champions 已加载且注入)。压力子系统可据此跳过升格相关的计数预留。 */
    public static boolean isBound() {
        return promoter != null;
    }

    /**
     * 对一只刚落地的怪尝试升格为冠军。未接线 (Champions 未加载) 直接短路返回, 不抛 —— 刷怪照常 (普通怪)。
     * 升格逻辑全在注入实现内, 本类不解释星级/词条 (避免压力子系统编译期触 Champions)。
     *
     * @param mob        已 addFreshEntity 落地的怪
     * @param difficulty 该怪所属矿洞实例难度 (决定星级分布)
     */
    public static void promote(Mob mob, Difficulty difficulty) {
        Promoter p = promoter;
        if (p == null || mob == null) {
            return;
        }
        p.promote(mob, difficulty);
    }
}
