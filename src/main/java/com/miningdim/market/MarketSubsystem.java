package com.miningdim.market;

import com.miningdim.core.Subsystem;
import com.miningdim.economy.EconomyServices;
import com.miningdim.market.store.MarketDaoSqlite;
import com.miningdim.market.store.MarketDb;
import com.miningdim.store.MiningStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 跳蚤市场子系统入口 (共享契约第 7 节; 模块化铁律 3)。纯服务端 (无 MCEF / 无 Dist 守卫需求): 在 register 内订阅
 * forge 生命周期事件接 SQLite 开关与登录结算, 并把 market.* / admin.* / player.* 三组 action 注册进已落地的
 * {@link com.miningdim.webui.server.WebUiServerDispatcher}。
 *
 * 生命周期 (服务端单连接契合 SQLite 单写者):
 *  - {@link ServerStartingEvent}: 在统一库连接 ({@link MiningStore}, 已于 ServerAboutToStart 开好并完成 schema
 *    迁移) 上构 DAO 与 {@link MarketEngine}, 经 {@link MarketServices} 注入门面。
 *  - {@link ServerStoppingEvent}: {@link MarketServices#reset} 清引用防跨存档脏引用; 连接【不在此关闭】,
 *    它是全服共享的, 归存储子系统在 ServerStopped 统一释放。
 *  - {@link PlayerEvent.PlayerLoggedInEvent}: {@link MarketEngine#settlePendingOnLogin} 结清离线期 pending_payout。
 *
 * 跨子系统协作只经货币门面定位器 ({@link com.miningdim.economy.EconomyServices}, 由引擎内部取用) 与 webui 派发器,
 * 不 import 经济实现类 (模块化铁律 2)。action 注册表与门面注入在服务端启动期 (单线程) 完成, 运行期只读。
 */
public final class MarketSubsystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/market");

    /** 本子系统建在统一连接上的 DAO; 连接的开关不归本子系统。 */
    private MarketDaoSqlite dao;

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 全部是 forge 总线运行期事件 (服务端启停 / 玩家登录), 不涉及 mod 总线注册。
        forgeBus.register(this);
        // action 注册表是进程级静态注册 (与门面就绪无关; 派发时引擎未就绪会经 MarketServices 自然抛, Gateway 兜底)。
        MarketActions.registerAll();
        // V0 基准价 admin curate 动作 (OP 门控): admin.setBaseValue / admin.listItems。
        MarketAdminActions.registerAll();
        // 玩家自身数据与账号级偏好动作 (顶栏余额 / 挂单选物 / 首屏聚合 / 物品详情 / 偏好读写) —— 让真桥脱离
        // 前端 Mock。动作名不在此逐条重列: 上一版就是列了两条然后随 registerAll 扩到七条而没跟上。
        PlayerWebUiActions.registerAll();
        // 条数是"action 到底注册上没有"在运行期的唯一证据, 与上面三个 registerAll 的实际条数必须逐个对上。
        LOGGER.info("[miningdim] market subsystem registered (12 market.* + 2 admin.* + 7 player.* actions; SQLite P2P trade channel)");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        // 市场表已并入统一库 miningdim.db (建表归 MiningSchema 的版本化迁移)。取连接失败自然冒泡
        // (启动期硬错, 不静默 fallback 掩盖)。
        this.dao = MarketDb.on(MiningStore.connection());
        MarketServices.registerMarketEngine(new MarketEngine(this.dao, server));
        LOGGER.info("[miningdim] market: SQLite ledger bound, MarketEngine registered (place/buy/cancel/settle live)");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 清门面引用 (运行期取用方随即在 MarketServices 自然抛, 暴露已下线) 并丢弃 DAO。
        // 连接是全服共享的, 由存储子系统在 ServerStopped 关闭 —— 在此关掉会连带打断开箱与后续的经济写入。
        MarketServices.reset();
        this.dao = null;
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 引擎或货币门面未就绪 (维度缺失等导致未注入) 时不结算 —— 取用方自然抛会打断整条登录链路, 故先判。
        // 结算现在是"取删待结款 + 入账"的单个事务, 一进门就要货币门面, 因此这里必须连它一起判。
        if (!MarketServices.isRegistered() || !EconomyServices.isRegistered()) {
            return;
        }
        MarketServices.marketEngine().settlePendingOnLogin(player);
    }

    @Override
    public String name() {
        return "MarketSubsystem";
    }
}
