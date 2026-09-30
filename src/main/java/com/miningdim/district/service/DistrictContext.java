package com.miningdim.district.service;

import com.miningdim.district.access.AccessResolver;
import com.miningdim.district.flan.DistrictFlanSync;
import com.miningdim.district.flan.DistrictReconciler;
import com.miningdim.district.flan.FlanGateway;
import com.miningdim.district.flan.LogThrottle;
import com.miningdim.district.guard.DistrictZoneIndex;
import com.miningdim.district.guard.GuardSettings;
import com.miningdim.district.notice.DistrictNotices;
import com.miningdim.district.store.DistrictRepository;
import com.miningdim.economy.IEconomyService;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 一次绑定的全部协作者 (设计文档 6.1)。各服务从这里取依赖; 服务本身无状态, 按需构造。
 *
 * @param economy 经济门面在调用时再取: 它在 ServerStartedEvent 才绑定 (比本模块晚), 且只有矿山维度存在时才会绑定;
 *                取不到时返回 null, 买地报 ECONOMY_OFFLINE, district.plots 的 viewerBalance 为 null
 * @param zones   按坐标查区与地块的空间索引 (22.3); 只有 {@link #of} 建它, 顺带做第一次重建并挂到仓储的几何监听上
 * @param guards  守卫的配置 (22.1), 开服读一次
 * @param onlinePlayers 按 UUID 找在线玩家 (没在线为 null): 生产取服务器的玩家列表, GameTest 取 GameTest 服务端的
 *                      玩家列表 (22.11 的即时投递与只发在线的广播用它)
 * @param notices 聊天通知 (22.10–22.12); 只有 {@link #of} 建它
 */
public record DistrictContext(
        DistrictRepository repo,
        FlanGateway gateway,
        DistrictFlanSync flanSync,
        LongSupplier clock,
        Supplier<IEconomyService> economy,
        PlayerDirectory players,
        LogThrottle logThrottle,
        DistrictZoneIndex zones,
        GuardSettings guards,
        Function<UUID, ServerPlayer> onlinePlayers,
        DistrictNotices notices) {

    /** 谁都不在线 (没有服务器的场合, 例如只测存储的夹具)。 */
    public static final Function<UUID, ServerPlayer> NOBODY_ONLINE = uuid -> null;

    public DistrictContext {
        Objects.requireNonNull(repo, "repo");
        Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(flanSync, "flanSync");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(economy, "economy");
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(logThrottle, "logThrottle");
        Objects.requireNonNull(zones, "zones");
        Objects.requireNonNull(guards, "guards");
        Objects.requireNonNull(onlinePlayers, "onlinePlayers");
        Objects.requireNonNull(notices, "notices");
    }

    /** 由仓储与网关建出领域级推送, 其余协作者原样注入; 守卫按代码默认值; 谁都不在线。 */
    public static DistrictContext of(DistrictRepository repo, FlanGateway gateway, LongSupplier clock,
                                     Supplier<IEconomyService> economy, PlayerDirectory players) {
        return of(repo, gateway, clock, economy, players, GuardSettings.defaults());
    }

    /** 同上, 指定守卫的配置; 谁都不在线。 */
    public static DistrictContext of(DistrictRepository repo, FlanGateway gateway, LongSupplier clock,
                                     Supplier<IEconomyService> economy, PlayerDirectory players,
                                     GuardSettings guards) {
        return of(repo, gateway, clock, economy, players, guards, NOBODY_ONLINE);
    }

    /**
     * 同上, 指定守卫的配置与在线玩家的查法。建空间索引、做第一次重建, 并把它挂到仓储的几何监听上 (改几何的写入提交之后
     * 重建); 建聊天通知。
     */
    public static DistrictContext of(DistrictRepository repo, FlanGateway gateway, LongSupplier clock,
                                     Supplier<IEconomyService> economy, PlayerDirectory players,
                                     GuardSettings guards, Function<UUID, ServerPlayer> onlinePlayers) {
        LogThrottle throttle = new LogThrottle(clock);
        DistrictZoneIndex zones = new DistrictZoneIndex(repo, throttle);
        repo.onLayoutChanged(zones::rebuild);
        zones.rebuild();
        return new DistrictContext(repo, gateway, new DistrictFlanSync(repo, gateway, throttle), clock, economy,
                players, throttle, zones, guards, onlinePlayers, new DistrictNotices(repo, clock, onlinePlayers));
    }

    /** 对账器 (定时对账、/district resync 与 inspect 共用同一个"每小时至多 WARN 一次"的节流)。 */
    public DistrictReconciler reconciler() {
        return new DistrictReconciler(flanSync, logThrottle);
    }

    public long now() {
        return clock.getAsLong();
    }

    @Nullable
    public IEconomyService economyOrNull() {
        return economy.get();
    }

    public AccessResolver access() {
        return new AccessResolver(repo);
    }

    public DistrictQueryService queries() {
        return new DistrictQueryService(this);
    }

    public ResidentService residents() {
        return new ResidentService(this);
    }

    public DistrictAdminService admin() {
        return new DistrictAdminService(this);
    }

    public DistrictPermissionService permissions() {
        return new DistrictPermissionService(this);
    }

    public PlotOwnerService plotOwners() {
        return new PlotOwnerService(this);
    }

    public PlotLayoutService layout() {
        return new PlotLayoutService(this);
    }

    public PlotMarketService market() {
        return new PlotMarketService(this);
    }

    public PlotFreezeService freezes() {
        return new PlotFreezeService(this);
    }

    public FirstLoginActivation firstLogin() {
        return new FirstLoginActivation(this);
    }
}
