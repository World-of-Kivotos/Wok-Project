package com.miningdim.champion.bloodpool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 6★+ 冠军自定义血池注册表 (ChampionStarAffix spec 6.2 血池权威)。entity UUID -> {@link BloodPool}, 服务端唯一
 * 影子血池账本。spawn 期 6★+ 冠军升格后 {@link #install} 建池; 受击 handler 经 {@link #get} 取池做净减伤/拦死;
 * 死亡/卸载 {@link #remove} 回收。
 *
 * 纯逻辑层: 不 import 任何 Champions 类、不碰 LivingEntity (只持 UUID + double 血池数学), 故 dev GameTest 触达
 * 安全。事件接线 (LivingHurtEvent 改 amount / LivingDeathEvent 拦死 / tick 渲染镜像同步) 在 integration 隔离包
 * 的 handler, 由它经本注册表读写血池。
 *
 * 线程纪律: 血池读写只在服务端主线程 (受击/tick 串行); ConcurrentHashMap 仅防跨线程读 (调试快照/卸载回收)
 * 的可见性, 不替代主线程串行写。
 */
public final class BloodPoolRegistry {

    private BloodPoolRegistry() {
    }

    /** entity UUID -> 影子血池 (6★+ 冠军才入表)。 */
    private static final ConcurrentHashMap<UUID, BloodPool> POOLS = new ConcurrentHashMap<>();

    /**
     * spawn 期为 6★+ 冠军建满血血池 (覆盖同 UUID 旧池, 防重生残留)。
     *
     * @param entityId 冠军实体 UUID
     * @param maxHp    该冠军有效最大血量 (按星表 + 巨大化后实际有效血; 必须 &gt;0, 通常 &gt;1024)
     * @return 新建的血池
     */
    public static BloodPool install(UUID entityId, double maxHp) {
        return install(entityId, maxHp, maxHp);
    }

    /**
     * spawn 期或 F040 载入重建期为冠军建血池, 按指定当前血 (从 NBT 还原可 &lt; maxHp; 覆盖同 UUID 旧池, 防重生残留)。
     *
     * @param entityId  冠军实体 UUID
     * @param maxHp     该冠军有效最大血量 (必须 &gt;0)
     * @param currentHp 当前血量 (必须 &gt;0 且 &lt;=maxHp; 越界抛 IllegalArgumentException, 见 BloodPool 构造校验)
     * @return 新建的血池
     */
    public static BloodPool install(UUID entityId, double maxHp, double currentHp) {
        if (entityId == null) {
            throw new IllegalArgumentException("entityId must not be null");
        }
        BloodPool pool = new BloodPool(maxHp, currentHp);
        POOLS.put(entityId, pool);
        return pool;
    }

    /** 取某实体的影子血池; 不在表 (非 6★+ 冠军) 返回 null (调用方据此判定走 vanilla 还是血池)。 */
    public static BloodPool get(UUID entityId) {
        if (entityId == null) {
            return null;
        }
        return POOLS.get(entityId);
    }

    /** 是否有影子血池 (= 是 6★+ 冠军且已建池)。 */
    public static boolean has(UUID entityId) {
        return entityId != null && POOLS.containsKey(entityId);
    }

    /** 死亡/卸载回收血池 (返回被移除的池, 无则 null)。 */
    public static BloodPool remove(UUID entityId) {
        if (entityId == null) {
            return null;
        }
        return POOLS.remove(entityId);
    }

    /** 服务端停止清空, 防跨存档脏引用 (供 ServerStoppingEvent)。 */
    public static void reset() {
        POOLS.clear();
    }

    /** 当前在册血池数 (诊断/测试用)。 */
    public static int size() {
        return POOLS.size();
    }

    /** 在册血池是否为空 (F039: 供每 tick 镜像 handler 空表早退, 省无谓遍历/复制)。 */
    public static boolean isEmpty() {
        return POOLS.isEmpty();
    }

    /**
     * 在册血池只读快照 (entity UUID -> 影子血池)。供需要"独立副本"的场景 (GameTest 断言 / 遍历中可能改表的调用方)
     * 使用。返回独立副本 (LinkedHashMap 稳定序), 遍历期间不锁原表; 期间 install/remove 不影响本次快照。
     *
     * 与 {@link #live()} 的分工: snapshot 每次调用分配一份新副本 (有拷贝开销), live 零拷贝直接暴露只读视图。
     * 每 tick 的渲染镜像遍历走 live, 不走本方法 (F039: 空表也复制是纯浪费, 主线程串行遍历中无 install/remove)。
     *
     * @return 在册血池 UUID -> BloodPool 的不可变副本 (空表示无 6★+ 血池冠军)
     */
    public static Map<UUID, BloodPool> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(POOLS));
    }

    /**
     * 在册血池零拷贝只读视图 (F039)。供主线程串行的每 tick 镜像遍历用: ConcurrentHashMap 的弱一致迭代器在
     * 遍历期间即便原表变化也不抛 ConcurrentModificationException, 而本表的写 (install/remove) 只发生在受击/
     * spawn/死亡等同样串行在主线程的时点, 遍历中途无并发写入, 故此处零拷贝视图安全。与 {@link #snapshot()}
     * 的独立副本语义不同, 不供需要遍历中改表或跨线程持有的调用方使用。
     *
     * @return 在册血池 UUID -> BloodPool 的不可变视图 (随原表实时变化, 非副本)
     */
    public static Map<UUID, BloodPool> live() {
        return Collections.unmodifiableMap(POOLS);
    }
}
