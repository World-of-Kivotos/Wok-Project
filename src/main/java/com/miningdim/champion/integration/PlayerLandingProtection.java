package com.miningdim.champion.integration;

import com.miningdim.champion.ExpiryLedger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家【2s 抗位移落地保护】(精英怪批4 波0 基建; ChampionStarAffix spec 9.3 / 红线 6)。红线 6 原文: 换位/瞬移后玩家获
 * 2.0s 抗位移落地保护, 覆盖原版近战击退 + 所有位移词条。业务背景: 服务端权威守卫把玩家挪到安全落点后, 若紧接着的
 * 原版近战击退或另一个位移词条又把玩家推回岩浆/虚空边缘, 守卫算出的安全落点就白算 —— 故落点后开 2s 窗, 窗内一切
 * 走 {@link LivingKnockBackEvent} 的击退一律取消。
 *
 * 授予方 (波1, 尚未落地): 分跳/混沌击飞/战术换位/闪光等位移词条把玩家挪到守卫安全落点后调 {@link #grant(ServerPlayer)}
 * 开窗。波0 只落地保护基建 (账本 + 拦截 + 清扫), 无调用方属计划内 —— 波1 接线时直接调 grant 即可, 拦截语义已就位。
 *
 * 拦截 (红线 6): {@link #onKnockback} 订阅 {@link LivingKnockBackEvent}, 被击退者为保护窗内的 {@link ServerPlayer}
 * 时 setCanceled(true) 整体取消该次击退。取消是硬闸, 覆盖原版近战击退与一切走本事件的击退词条。绝大多数击退 (未
 * 保护 / 非服务端玩家) 在此早退零副作用。
 *
 * 已知边界 (平台现实与 spec 理想面的差距, 波0 报备): 本闸只拦 {@link LivingKnockBackEvent}。实体体积推挤
 * ({@code Entity.push} 直改 deltaMovement) 与直接 setDeltaMovement 不经该事件, 本闸拦不住 —— 波0 交付"事件层拦截 +
 * 词条位移自查"两道防线, 词条类位移的自查约定 = 位移类效果动手前先查 {@link #isProtected} (自觉不对受保护玩家施
 * 二次位移)。绕过事件的硬推挤属后续批 (需服务端权威守卫直接钳落点)。
 *
 * 设计 (静态账本 + 实例 handler, 与 {@link AoeImmunityBuffer} 同范式): 保护态是全局单例服务, 存于静态 {@link #LEDGER}
 * (代理 A 冻结契约 {@link ExpiryLedger} 提供的到期账本), 故 {@link #grant}/{@link #isProtected}/{@link #reset}
 * 皆静态供位移词条 handler 跨类调用; @SubscribeEvent 拦截/清扫为实例方法, 由 {@code ChampionSystem#register} 以
 * {@code forgeBus.register(new PlayerLandingProtection())} 挂载 (与包内既有 handler 同注册范式)。本 handler 只读事件 +
 * 取消, 无实体位移写操作, 故无需 server.execute 回主线程 (LivingKnockBackEvent 本就在服务端主线程结算)。
 */
public final class PlayerLandingProtection {

    /** 诊断日志: 落地保护拦截真服首验用 (每玩家每保护窗至多一条, 见 {@link #CANCEL_LOG_GATE} 去重)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/landing");

    /** 抗位移落地保护时长 (tick): 2.0s = 40tick (spec 9.3 定值; 覆盖原版近战击退 + 所有位移词条二次击退)。 */
    public static final long PROTECTION_TICKS = 40L;

    /**
     * 账本清扫周期 (tick): 保护条目仅 {@value #PROTECTION_TICKS}tick 短命且读判 {@link #isProtected} 已按 tick 判到期,
     * sweep 只回收已过期条目的内存残留 (登出/掉线玩家等), 故低频清扫即可 (与 {@link AoeImmunityBuffer} 同为波0 短命
     * 窗缓冲, 共用 END 相位每 5s 一次的清扫节奏; 沿用 {@code ChampionSelfEffectHandler} 的 STATE_SWEEP 模式)。
     */
    private static final int SWEEP_INTERVAL_TICKS = 100;

    /**
     * per-玩家抗位移窗到期账本 (玩家 UUID -> 到期 gameTime tick; 纯逻辑, 由代理 A 冻结契约 {@link ExpiryLedger} 提供)。
     * 静态单例: 保护态全局唯一, 静态 API 与实例 handler 共读同一账本。同一玩家重复 grant 由账本取更晚到期 (续窗不缩短),
     * 故账本大小受在线玩家数天然封顶。
     */
    private static final ExpiryLedger LEDGER = new ExpiryLedger();

    /**
     * 取消日志去重闸 (玩家 UUID -> 当前保护窗到期 tick; 键存在=本窗尚可打一条取消日志)。{@link #grant} 每次记账即
     * arm (put 新窗到期), {@link #onKnockback} 首次取消击退时 consume (remove 并打一条), 同窗后续取消因键已消费而静默 ——
     * 满足"每玩家每保护窗至多一条", 防持续被击退时刷屏。整窗未被击退 (从未 consume) 的残留条目由 {@link #onServerTick}
     * 按值 (即到期 tick) 回收。仅用于诊断日志去重, 不参与取消判定。
     */
    private static final Map<UUID, Long> CANCEL_LOG_GATE = new HashMap<>();

    /**
     * 授予某玩家 2s 抗位移落地保护: 到期 tick = 当前 gameTime + {@value #PROTECTION_TICKS}。波1 的分跳/混沌击飞/战术换位/
     * 闪光等位移词条把玩家挪到守卫安全落点【之后】调用 —— 之后 2s 内该玩家受到的一切走 {@link LivingKnockBackEvent}
     * 的击退经 {@link #onKnockback} 取消, 防落点被二次击退推回危险区 (红线 6)。
     *
     * @param player 获保护的服务端玩家 (刚被挪到安全落点者)
     */
    public static void grant(ServerPlayer player) {
        UUID id = player.getUUID();
        long expiry = player.level().getGameTime() + PROTECTION_TICKS;
        LEDGER.grant(id, expiry);
        CANCEL_LOG_GATE.put(id, expiry); // 新窗 arm 一条取消日志额度 (值=窗到期 tick, 兼作残留回收判据)。
    }

    /**
     * 该玩家是否仍在抗位移落地保护窗内 (读账本按当前 gameTime 判到期)。位移类词条动手前应先查此判定, 自觉不对受保护
     * 玩家施二次位移 (波0 词条位移自查约定)。过期条目由周期 {@link #onServerTick} 清扫回收, 但读判独立于清扫 —— 账本
     * {@link ExpiryLedger#isActive} 以 nowTick 判活, 过期未清扫的条目亦返 false。
     *
     * @param player 待查玩家
     * @return 是否受保护
     */
    public static boolean isProtected(ServerPlayer player) {
        return LEDGER.isActive(player.getUUID(), player.level().getGameTime());
    }

    /**
     * 清空保护账本 + 诊断去重闸 (服务端停止清理 + GameTest 隔离共用; 审查修复: 换存档后 gameTime 从小值重计, 残留的
     * 旧到期 tick 会让同 UUID 玩家在新存档被误判仍受保护, 故 {@code ChampionSystem#onServerStopping} 必须清账)。
     * {@link ExpiryLedger} 冻结契约无 clear 方法, 故账本以 {@code sweep(Long.MAX_VALUE)} 令一切条目到期回收 ——
     * 任何真实到期 tick (gameTime + 40) 皆 &lt; Long.MAX_VALUE, 无论 sweep 到期判定含界与否均全清
     * (与 {@link AoeImmunityBuffer#reset} 同法)。
     */
    public static void reset() {
        LEDGER.sweep(Long.MAX_VALUE);
        CANCEL_LOG_GATE.clear();
    }

    /**
     * 取消判定纯逻辑 (GameTest 直接以自建 {@link ExpiryLedger} 驱动精确断言): 玩家在保护窗内即应取消其击退。抽出此
     * 静态方法是因真 {@link ServerPlayer} 在 dev GameTest 不可得, 把可测边界下沉到账本语义 —— 保护窗内真 / 窗外假 /
     * 未记账假, 半开区间边界由 {@link ExpiryLedger} 单一钉死。
     *
     * public static 为测试可见缝 (GameTest 在 champion 根包, 与本 integration 包跨包, 故非 package-private; 与
     * {@link AoeImmunityBuffer#shouldBlockDamage} 同处理)。
     *
     * @param playerId 被击退玩家 UUID
     * @param nowTick  当前 gameTime tick
     * @param ledger   抗位移窗账本
     * @return 是否应取消本次击退
     */
    public static boolean shouldCancelKnockback(UUID playerId, long nowTick, ExpiryLedger ledger) {
        return ledger.isActive(playerId, nowTick);
    }

    /**
     * 击退拦截 (红线 6 核心): 被击退者为保护窗内的服务端玩家时取消整个击退 (setCanceled), 覆盖原版近战击退与一切走
     * 本事件的击退。未保护 / 非服务端玩家在此早退。首次取消打一条诊断日志 (每保护窗至多一条)。
     */
    @SubscribeEvent
    public void onKnockback(LivingKnockBackEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return; // 仅拦服务端玩家 (保护是给玩家的红线 6 落点保护; 怪/客户端幽灵不涉)。
        }
        long nowTick = player.level().getGameTime();
        if (!shouldCancelKnockback(player.getUUID(), nowTick, LEDGER)) {
            return; // 不在保护窗: 放行原版击退结算 (绝大多数击退早退)。
        }
        event.setCanceled(true);
        logCancelOnce(player, event.getStrength(), nowTick);
    }

    /**
     * 每保护窗至多一条取消日志: 消费 arm 闸 (键存在即打一条并移除本窗额度)。同窗后续取消因额度已消费而静默, 防持续
     * 被击退 (窗内可被反复触发击退) 时刷屏。
     */
    private static void logCancelOnce(ServerPlayer player, float strength, long nowTick) {
        if (CANCEL_LOG_GATE.remove(player.getUUID()) == null) {
            return; // 本窗已打过 (额度已消费): 静默。
        }
        LOGGER.info("landing-protect cancel knockback player={} strength={} tick={}",
                player.getName().getString(), String.format("%.3f", strength), nowTick);
    }

    /**
     * 周期回收过期条目 (纯内存卫生): END 相位每 {@value #SWEEP_INTERVAL_TICKS}tick 一次, 按 overworld gameTime 清账本
     * 已到期条目 + 清诊断去重闸整窗未被消费的残留 (残留值即到期 tick, 判据与账本 {@link ExpiryLedger#sweep} 一致
     * nowTick &gt;= expiry)。读判 {@link #isProtected} 已按 tick 判到期, 清扫不影响正确性, 仅回收登出/掉线玩家残留条目。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SWEEP_INTERVAL_TICKS != 0) {
            return;
        }
        long nowTick = server.overworld().getGameTime();
        LEDGER.sweep(nowTick);
        CANCEL_LOG_GATE.values().removeIf(expiry -> nowTick >= expiry);
    }
}
