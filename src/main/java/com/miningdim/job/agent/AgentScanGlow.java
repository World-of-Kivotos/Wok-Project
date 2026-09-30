package com.miningdim.job.agent;

import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战术扫描 L8 "Glowing 高亮"的逐玩家实现 (SpecialAgent_Job_DesignSpec 第四章 L8 / 五章"原版发光, 穿墙可见")。
 *
 * 为什么不用原版发光药水 / setGlowingTag: 两者都改实体的共享标志位, 原版会把它广播给<b>所有</b>追踪该实体的玩家
 * —— 干员扫一次, 全场的人都白拿一次穿墙透视, 探测支线的等级门就被旁人绕过了。本类只给发起扫描的那名干员发一份
 * "发光位已置"的共享标志位实体数据包, 服务端实体本身一个比特都不改, 其他玩家的客户端完全不知道这回事。
 *
 * 为什么要每 tick 盯着重发: 服务端实体的共享标志位是一个字节 (着火 / 潜行 / 疾跑 / 游泳 / 隐身 / 发光 / 滑翔),
 * 只要其中任一位变化 (精英起跑、被点燃), 原版就会把<b>真实</b>字节 (发光位为 0) 广播给全部追踪者, 干员的客户端
 * 随之熄灭。故每 tick 比对"真实字节 | 发光位"与上次发给该干员的值, 不同即补发; 本处理挂在服务端 tick 的 END 相位,
 * 晚于同 tick 内原版 ServerEntity 的脏数据广播, 所以同一 tick 内就把它盖回去, 最多只闪一帧。另两条重同步路径:
 * 干员走出追踪距离再走回来时原版会重发全量实体数据 (发光位同样被冲掉) —— 由 {@link Handler#onStartTracking} 在
 * 配对数据发出后立刻补发; 其它无法预见的重同步 (别的 mod 自己发实体数据包等) 由每
 * {@value #REASSERT_INTERVAL_TICKS} tick 一次的无条件重申兜住, 最多暗一秒。
 *
 * 生命期与快照严格同长 (与 {@code AgentWebUiActions.ScanPulse} 同一个 pulseTick / cooldownTicks 派生): 快照一过期就
 * 给干员补发真实字节把高亮熄掉, 干员重扫时先熄旧的再点新的。会话按干员 UUID 存在进程内存 (与脉冲记录同档,
 * 重启归零); 干员离线期间会话照常计时, 过期即丢, 回来时若仍在有效期内由周期重申重新点亮 —— 与脉冲记录"跨重连
 * 保留"的口径一致。干员换维度后, 目标按网络 id + UUID 在其<b>当前</b>维度里查不到, 自然不发包。
 *
 * 线程纪律: 扫描 action 与 tick 处理都在服务端主线程; ConcurrentHashMap 只为跨线程读 (调试) 的可见性。
 */
public final class AgentScanGlow {

    /** 原版共享标志位里的发光位下标 (原版 {@code Entity.FLAG_GLOWING} 为私有常量, 值固定为 6, 此处按同值自持)。 */
    static final int FLAG_GLOWING = 6;

    private static final byte GLOWING_MASK = (byte) (1 << FLAG_GLOWING);

    /** 无条件重申间隔 (tick): 兜住预见不到的重同步路径, 1 秒一次, 每名干员至多 8 个目标, 开销可忽略。 */
    static final long REASSERT_INTERVAL_TICKS = 20L;

    /** 干员 UUID -&gt; 当前高亮会话。 */
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private AgentScanGlow() {
    }

    /**
     * 高亮目标: 网络 id 用于查实体, UUID 用于防网络 id 在目标死亡后被另一只实体复用 —— 不核对 UUID 就会把一只
     * 刚刷出来、从没被扫过的怪点亮。
     */
    public record Target(int networkId, UUID entityUuid) {
    }

    /** 一名干员的高亮会话: 起止 tick (与脉冲同源) + 目标表 + 每个目标上次发出的字节 (比对用)。 */
    private static final class Session {
        private long startTick;
        private long expiryTick;
        private final List<Target> targets;
        private final Map<Integer, Byte> lastSent = new HashMap<>();

        private Session(long startTick, long expiryTick, List<Target> targets) {
            this.startTick = startTick;
            this.expiryTick = expiryTick;
            this.targets = List.copyOf(targets);
        }
    }

    /**
     * 开一轮高亮 (扫描脉冲成功后调用)。先熄掉该干员上一轮残留的高亮, 再对本轮目标逐个立即点亮。
     *
     * @param agent      扫描干员
     * @param targets    本次快照里的全部目标
     * @param nowTick    脉冲 tick (快照起点)
     * @param expiryTick 快照到期 tick (= 脉冲 tick + 脉冲 CD; 到点即熄)
     */
    public static void start(ServerPlayer agent, List<Target> targets, long nowTick, long expiryTick) {
        clear(agent);
        if (targets.isEmpty()) {
            return; // 扫空: 没有要点亮的, 也不留一条空会话在 tick 里空转。
        }
        Session session = new Session(nowTick, expiryTick, targets);
        SESSIONS.put(agent.getUUID(), session);
        assertAll(agent, session, true);
    }

    /** 立即熄掉该干员当前全部高亮并结束会话 (无会话时空操作)。 */
    public static void clear(ServerPlayer agent) {
        Session session = SESSIONS.remove(agent.getUUID());
        if (session != null) {
            restoreAll(agent, session);
        }
    }

    /** 该干员当前是否正在高亮某个网络 id 的目标 (面板回执 / GameTest 用)。 */
    public static boolean isHighlighting(UUID agentId, int networkId) {
        Session session = SESSIONS.get(agentId);
        if (session == null) {
            return false;
        }
        for (Target target : session.targets) {
            if (target.networkId() == networkId) {
                return true;
            }
        }
        return false;
    }

    /**
     * 仅供同包 GameTest: 把该干员会话的起止 tick 整体往回拨 (与 {@code AgentWebUiActions.rewindPulseForTest} 同步
     * 调用, 保证"快照到期"与"高亮到期"在测试里仍是同一刻)。
     */
    static void rewindForTest(UUID agentId, long deltaTicks) {
        Session session = SESSIONS.get(agentId);
        if (session != null) {
            session.startTick -= deltaTicks;
            session.expiryTick -= deltaTicks;
        }
    }

    /**
     * 每服务端 tick 推进一次全部会话: 到期 (或世界时钟倒退, 与脉冲记录同一脏记录判据) 的熄灭并丢弃; 未到期的对
     * 在线干员按"字节变了就补发 + 每秒无条件重申"维持高亮。包可见性给 GameTest 直接驱动。
     */
    public static void tick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        // 各维度共用主世界的游戏时钟 (次级维度的世界数据委托主世界), 与脉冲 tick 同源。
        long now = server.overworld().getGameTime();
        Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Session> entry = it.next();
            Session session = entry.getValue();
            ServerPlayer agent = server.getPlayerList().getPlayer(entry.getKey());
            if (now >= session.expiryTick || now < session.startTick) {
                it.remove();
                if (agent != null) {
                    restoreAll(agent, session);
                }
                continue;
            }
            if (agent != null) {
                assertAll(agent, session, (now - session.startTick) % REASSERT_INTERVAL_TICKS == 0L);
            }
        }
    }

    /** 服务端停止清空 (防跨存档脏引用; 客户端随断线整体丢弃, 无需补发)。 */
    static void reset() {
        SESSIONS.clear();
    }

    /** 对会话内每个仍能查到的目标, 在字节变化或强制时补发"真实字节 | 发光位"。 */
    private static void assertAll(ServerPlayer agent, Session session, boolean force) {
        for (Target target : session.targets) {
            Entity entity = resolve(agent, target);
            if (entity == null) {
                continue; // 已死 / 已卸载 / 不在干员当前维度: 客户端也没有它, 不发。
            }
            byte desired = (byte) (realFlags(entity) | GLOWING_MASK);
            Byte last = session.lastSent.get(target.networkId());
            if (force || last == null || last != desired) {
                send(agent, entity, desired);
                session.lastSent.put(target.networkId(), desired);
            }
        }
    }

    /**
     * 熄灭: 补发实体<b>真实</b>字节, 而不是"真实字节去掉发光位"—— 目标若本来就在真发光 (中了发光药水、正被凯撒
     * 换位预兆点亮), 那份发光属于全场可见的公开信息, 不能被本类顺手熄掉。
     */
    private static void restoreAll(ServerPlayer agent, Session session) {
        for (Target target : session.targets) {
            Entity entity = resolve(agent, target);
            if (entity != null) {
                send(agent, entity, realFlags(entity));
            }
        }
    }

    private static Entity resolve(ServerPlayer agent, Target target) {
        Entity entity = agent.serverLevel().getEntity(target.networkId());
        if (entity == null || entity.isRemoved() || !entity.getUUID().equals(target.entityUuid())) {
            return null;
        }
        return entity;
    }

    private static byte realFlags(Entity entity) {
        return entity.getEntityData().get(Entity.DATA_SHARED_FLAGS_ID);
    }

    private static void send(ServerPlayer agent, Entity entity, byte flags) {
        agent.connection.send(new ClientboundSetEntityDataPacket(entity.getId(),
                List.of(SynchedEntityData.DataValue.create(Entity.DATA_SHARED_FLAGS_ID, flags))));
    }

    /** forge 事件接线 (由 {@link AgentSystem#register} 挂 forgeBus)。 */
    public static final class Handler {

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            tick(event.getServer());
        }

        /**
         * 干员重新开始追踪某目标 (走出追踪距离又走回来 / 目标所在区块重新进入视距): 原版刚发完的全量实体数据里
         * 发光位是 0, 立刻补一份。本事件在配对数据包发出之后才触发, 故补发一定晚于被冲掉的那份。
         */
        @SubscribeEvent
        public void onStartTracking(PlayerEvent.StartTracking event) {
            if (!(event.getEntity() instanceof ServerPlayer agent)) {
                return;
            }
            Session session = SESSIONS.get(agent.getUUID());
            if (session == null) {
                return;
            }
            Entity entity = event.getTarget();
            for (Target target : session.targets) {
                if (target.networkId() == entity.getId() && target.entityUuid().equals(entity.getUUID())) {
                    byte desired = (byte) (realFlags(entity) | GLOWING_MASK);
                    send(agent, entity, desired);
                    session.lastSent.put(target.networkId(), desired);
                    return;
                }
            }
        }
    }
}
