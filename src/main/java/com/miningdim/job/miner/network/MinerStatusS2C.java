package com.miningdim.job.miner.network;

import com.miningdim.job.miner.MinerChargeState;
import com.miningdim.job.miner.MinerSkill;
import com.miningdim.job.miner.MinerSkills;
import com.miningdim.job.miner.client.MinerStatusClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C 矿工状态 HUD 同步包 (全库首个原生 GuiOverlay 的数据源)。矿工是 FF14 式非 GUI 职业, 唯一 UI 面是屏幕一角
 * 常驻 HUD ({@link MinerStatusClient} 缓存 + MinerHudOverlay 渲染)。HUD 数据一律服务端权威: 客户端不推算任何
 * CD/充能, 只按本包字段展示。
 *
 * 字段 (均服务端 {@link #capture} 从瞬态 {@link MinerChargeState} 取):
 *  - charge / poolMax: 连锁充能池当前量与上限 (poolMax=0 表示连锁未解锁, 客户端据此不画充能条)。
 *  - toggleBits: 三个偏好开关位打包 (CHAIN/AUTO_COLLECT/AUTO_SMELT), 见 {@link #packToggles}。
 *  - oreScanCdTicks / trapScanCdTicks: 两个探测技能的 CD 剩余 tick。为避免客户端臆算 server gameTime,
 *    服务端直接算好 "剩余 tick" 下发 (readyAt - now, 已就绪为 0), 客户端只做 tick->秒 展示。
 *    {@link #CD_LOCKED} (-1) 是显式协议编码, 表示该技能尚未解锁 (与 "就绪 0" 明确区分, 非静默兜底值)。
 *
 * 客户端类引用 ({@link MinerStatusClient}) 经 DistExecutor 隔离 (与 {@link MinerHighlightS2C} 同范式,
 * 防专用服务器加载期触链)。
 */
public record MinerStatusS2C(int charge, int poolMax, byte toggleBits, int oreScanCdTicks, int trapScanCdTicks) {

    /** CD 字段哨兵: 技能未解锁 (客户端显示 "未解锁", 区别于就绪的 0)。 */
    public static final int CD_LOCKED = -1;

    /** toggleBits 位: 连锁挖矿"当前按住激活中" (连锁已从持久开关改为按住激活, 本位反映 heldUntilTick 是否仍有效, 非开关态)。 */
    private static final int BIT_CHAIN = 0x1;
    /** toggleBits 位: 自动入包开。 */
    private static final int BIT_AUTO_COLLECT = 0x2;
    /** toggleBits 位: 自动熔炼开。 */
    private static final int BIT_AUTO_SMELT = 0x4;

    /**
     * 服务端从瞬态运行态构建一份 HUD 快照 (只读, 不改任何态)。poolMax/解锁判定经 {@link MinerSkills} 按等级重算,
     * 保证与实际能力一致 (客户端无从伪造)。
     */
    public static MinerStatusS2C capture(MinerChargeState state, int level, long now) {
        int charge = state.currentCharge();
        int poolMax = MinerSkills.chainChargePool(level);
        byte bits = packToggles(
                state.chainHeldActive(now), // 连锁位 = 当前按住激活中 (非持久开关; heldUntilTick >= now)。
                state.toggled(MinerSkill.AUTO_COLLECT),
                state.toggled(MinerSkill.AUTO_SMELT));
        int oreCd = cdRemainingTicks(state, MinerSkill.ORE_SCAN, now, MinerSkills.oreScanUnlocked(level));
        int trapCd = cdRemainingTicks(state, MinerSkill.TRAP_SCAN, now, MinerSkills.trapScanUnlocked(level));
        return new MinerStatusS2C(charge, poolMax, bits, oreCd, trapCd);
    }

    /**
     * 探测技能 CD 剩余 tick (服务端算好下发, 客户端不推算时钟):
     * 未解锁返回 {@link #CD_LOCKED}; 已就绪返回 0; 冷却中返回 (readyAt - now) (为正且落在 int 范围内, CD 上限 6000)。
     */
    public static int cdRemainingTicks(MinerChargeState state, MinerSkill skill, long now, boolean unlocked) {
        if (!unlocked) {
            return CD_LOCKED;
        }
        if (state.cooldownReady(skill, now)) {
            return 0;
        }
        long remaining = state.cooldownReadyAt(skill) - now;
        return remaining > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
    }

    /** 三个偏好开关位打包成一个 byte (纯函数, 供 GameTest 断言)。 */
    public static byte packToggles(boolean chain, boolean autoCollect, boolean autoSmelt) {
        int bits = 0;
        if (chain) {
            bits |= BIT_CHAIN;
        }
        if (autoCollect) {
            bits |= BIT_AUTO_COLLECT;
        }
        if (autoSmelt) {
            bits |= BIT_AUTO_SMELT;
        }
        return (byte) bits;
    }

    public boolean chainOn() {
        return (toggleBits & BIT_CHAIN) != 0;
    }

    public boolean autoCollectOn() {
        return (toggleBits & BIT_AUTO_COLLECT) != 0;
    }

    public boolean autoSmeltOn() {
        return (toggleBits & BIT_AUTO_SMELT) != 0;
    }

    public static void encode(MinerStatusS2C msg, FriendlyByteBuf buf) {
        // charge/poolMax 恒非负走 varint 省字节; CD 字段可为 -1 哨兵, 用定长 int 保负值语义。
        buf.writeVarInt(msg.charge);
        buf.writeVarInt(msg.poolMax);
        buf.writeByte(msg.toggleBits);
        buf.writeInt(msg.oreScanCdTicks);
        buf.writeInt(msg.trapScanCdTicks);
    }

    public static MinerStatusS2C decode(FriendlyByteBuf buf) {
        int charge = buf.readVarInt();
        int poolMax = buf.readVarInt();
        byte bits = buf.readByte();
        int oreCd = buf.readInt();
        int trapCd = buf.readInt();
        return new MinerStatusS2C(charge, poolMax, bits, oreCd, trapCd);
    }

    /**
     * 客户端 handler: enqueueWork 切回客户端主线程, 把快照交给 {@link MinerStatusClient} 缓存 (整体替换); 不触任何
     * 世界写。客户端类引用经 DistExecutor 隔离 (专用服务器不触达客户端类)。
     */
    public static void handle(MinerStatusS2C msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> MinerStatusClient.accept(msg)));
        ctx.setPacketHandled(true);
    }
}
