package com.miningdim.freshness;

import net.minecraft.network.FriendlyByteBuf;

import java.util.function.Supplier;

import net.minecraftforge.network.NetworkEvent;

/**
 * 服务端 -> 客户端的时钟与保质期同步包 (规格「客户端显示」: 客户端只读物品上的章 + **同步下来的时钟和
 * 保质期配置** + 自己的「物品 -> 类别」缓存)。
 *
 * 只发"算剩余所需的最小集合", 不发任何物品数据:
 * <ul>
 *   <li>{@code nowMinute}: 服务端当下的分钟序号 (含停服补偿与偏移), 客户端收到后按自己的流逝时间往前推,
 *       于是两次同步之间显示也在走;</li>
 *   <li>{@code offsetHours}: 累计偏移, 只用于 `/freshness clock` 那类展示与排障;</li>
 *   <li>{@code mode}: 总开关档位 (关 / 试运行 / 开启) —— 关掉时客户端不画条, 免得画出服务端根本不管的东西;</li>
 *   <li>{@code shelfLives}: 六类保质期 (顺序 = {@link FreshnessCategory} 序), 客户端只读这一份,
 *       绝不去碰 SERVER 配置。</li>
 * </ul>
 *
 * 解码时对越界值做兜底 (网络来的东西一律当不可信): 保质期钳到 1..8760, 档位越界按试运行算。
 */
public record FreshnessSyncS2C(long nowMinute, long offsetHours, int mode, int[] shelfLives) {

    /** 保质期的合法上界 (与配置的 8760 小时一致)。 */
    private static final int MAX_SHELF_LIFE_HOURS = 8_760;

    public static void encode(FreshnessSyncS2C msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.nowMinute);
        buf.writeLong(msg.offsetHours);
        buf.writeByte(msg.mode);
        int[] values = msg.shelfLives;
        buf.writeByte(values == null ? 0 : values.length);
        if (values != null) {
            for (int value : values) {
                buf.writeVarInt(value);
            }
        }
    }

    public static FreshnessSyncS2C decode(FriendlyByteBuf buf) {
        long nowMinute = buf.readLong();
        long offsetHours = buf.readLong();
        int mode = buf.readByte();
        int length = buf.readByte() & 0xFF;
        int[] values = new int[length];
        for (int i = 0; i < length; i++) {
            int raw = buf.readVarInt();
            values[i] = Math.max(1, Math.min(MAX_SHELF_LIFE_HOURS, raw));
        }
        FreshnessCategory[] categories = FreshnessCategory.values();
        if (length != categories.length) {
            // 长度对不上 (版本不一致): 退回出厂默认值, 而不是拿半份数组去索引。
            // 用 fallbackHours 而不是 currentValues: 解码跑在客户端, 而客户端读不到 SERVER 配置。
            values = new int[categories.length];
            for (int i = 0; i < categories.length; i++) {
                values[i] = FreshnessShelfLives.fallbackHours(categories[i]);
            }
            mode = FreshnessConfig.Mode.DRY_RUN.ordinal();
        }
        return new FreshnessSyncS2C(nowMinute, offsetHours, mode, values);
    }

    /** 客户端 handler: 只在客户端线程写缓存, 不做任何渲染 (渲染自己去读缓存)。 */
    public static void handle(FreshnessSyncS2C msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> com.miningdim.freshness.client.FreshnessClientState.apply(msg)));
        ctx.setPacketHandled(true);
    }

    /** 档位 (越界按试运行算, 与解码同一条兜底)。 */
    public FreshnessConfig.Mode modeOrDefault() {
        FreshnessConfig.Mode[] all = FreshnessConfig.Mode.values();
        return mode >= 0 && mode < all.length ? all[mode] : FreshnessConfig.Mode.DRY_RUN;
    }
}
