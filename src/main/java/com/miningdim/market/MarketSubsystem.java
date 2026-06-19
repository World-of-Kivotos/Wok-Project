package com.miningdim.market;

import com.miningdim.core.Subsystem;
import com.miningdim.market.store.MarketDaoSqlite;
import com.miningdim.market.store.MarketDb;
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
 * forge 生命周期事件接 SQLite 开关与登录结算, 并把 6 个 market.* action 注册进已落地的
 * {@link com.miningdim.webui.server.WebUiServerDispatcher}。
 *
 * 生命周期 (契约第 2 节, 服务端单连接契合 SQLite 单写者):
 *  - {@link ServerStartingEvent}: 经 A 的 {@link MarketDb#open} 开世界存档目录下的 miningdim_market.db 连接 (WAL +
 *    foreign_keys) + initSchema 建表, 构 {@link MarketEngine}, 经 {@link MarketServices} 注入门面。
 *  - {@link ServerStoppingEvent}: 关连接 + {@link MarketServices#reset} 清引用防跨存档脏引用。
 *  - {@link PlayerEvent.PlayerLoggedInEvent}: {@link MarketEngine#settlePendingOnLogin} 结清离线期 pending_payout。
 *
 * 跨子系统协作只经货币门面定位器 ({@link com.miningdim.economy.EconomyServices}, 由引擎内部取用) 与 webui 派发器,
 * 不 import 经济实现类 (模块化铁律 2)。action 注册表与门面注入在服务端启动期 (单线程) 完成, 运行期只读。
 */
public final class MarketSubsystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/market");

    /** 本子系统持有的 SQLite DAO (服务端单连接, 启动期建、停止期关; 门面引用由 MarketServices 单一持有, 此处仅留关连接句柄)。 */
    private MarketDaoSqlite dao;

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 全部是 forge 总线运行期事件 (服务端启停 / 玩家登录), 不涉及 mod 总线注册。
        forgeBus.register(this);
        // action 注册表是进程级静态注册 (与门面就绪无关; 派发时引擎未就绪会经 MarketServices 自然抛, Gateway 兜底)。
        MarketActions.registerAll();
        LOGGER.info("[miningdim] market subsystem registered (6 market.* actions; SQLite P2P trade channel)");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        // 经 A 的 MarketDb 开世界存档目录下的 miningdim_market.db (契约第 2 节: getWorldPath(ROOT)/miningdim_market.db,
        // WAL + foreign_keys), MarketDb.open 内部已 initSchema 建表; 失败自然冒泡 (启动期硬错, 不静默 fallback 掩盖)。
        this.dao = MarketDb.open(server);
        MarketServices.registerMarketEngine(new MarketEngine(this.dao, server));
        LOGGER.info("[miningdim] market: SQLite ledger bound, MarketEngine registered (place/buy/cancel/settle live)");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 先清门面引用 (运行期取用方随即在 MarketServices 自然抛, 暴露已下线), 再关连接 (释放 SQLite 文件锁)。
        // 连接关闭经 A 的静态 MarketDb.close(dao) 编排 (契约第 2 节: 连接由 MarketDb open/close 管, 幂等)。
        MarketServices.reset();
        if (this.dao != null) {
            MarketDb.close(this.dao);
            this.dao = null;
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 引擎未就绪 (维度缺失等导致未注入) 时不结算 (取用方自然抛会打断登录链路, 故先判 isRegistered)。
        if (!MarketServices.isRegistered()) {
            return;
        }
        MarketServices.marketEngine().settlePendingOnLogin(player);
    }

    @Override
    public String name() {
        return "MarketSubsystem";
    }
}
