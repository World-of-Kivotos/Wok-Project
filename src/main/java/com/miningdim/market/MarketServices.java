package com.miningdim.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 跳蚤市场服务定位器 (共享契约第 7 节; 仿 {@link com.miningdim.economy.EconomyServices} /
 * {@link com.miningdim.job.JobServices} 平行定位器范式, 避免改 core.MiningServices)。
 *
 * 范式对齐: 显式强类型私有静态字段 + 非空注入 + require 取用 (未注入抛 IllegalStateException, 严禁返回 null
 * 掩盖装配缺陷) + reset()。门面 {@link MarketEngine} 由 {@link MarketSubsystem} 在 ServerStartingEvent 建 SQLite
 * 连接 + 引擎后注入, ServerStoppingEvent 经 {@link #reset} 清引用防跨存档脏引用。
 *
 * 成交监听 ({@link #registerTradeListener}) 与 {@code MiningServices.registerInstanceResetListener} 同一多播范式:
 * 下游模块 (成就) 在 mod 构造期注册进来, 本模块不依赖它们; 监听器列表不随 {@link #reset} 清空。
 *
 * 全部静态, 生命周期 = mod 进程。注入只在服务端启动期发生 (单线程), 故无需锁; 运行期只读。
 */
public final class MarketServices {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/market");

    private MarketServices() {
    }

    private static MarketEngine marketEngine;

    private static final List<TradeListener> tradeListeners = new CopyOnWriteArrayList<>();

    /** 市场子系统在服务端启动期注入交易引擎门面 (null 抛 IllegalArgumentException)。 */
    public static void registerMarketEngine(MarketEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("Cannot register null MarketEngine");
        }
        marketEngine = engine;
    }

    /** 取交易引擎门面 (未注入抛 IllegalStateException, 不返回 null)。 */
    public static MarketEngine marketEngine() {
        if (marketEngine == null) {
            throw new IllegalStateException(
                    "MarketServices: MarketEngine not registered yet (market subsystem binds it at server start)");
        }
        return marketEngine;
    }

    /** 是否已注入 (接线就绪判定)。 */
    public static boolean isRegistered() {
        return marketEngine != null;
    }

    /** 注册一个成交监听器 (见 {@link TradeListener}); 按注册顺序通知。 */
    public static void registerTradeListener(TradeListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Cannot register null TradeListener");
        }
        tradeListeners.add(listener);
    }

    /**
     * 通知一笔已提交的成交 ({@link MarketEngine#buy} 经经济层的提交后队列调用)。逐个吞掉监听器的异常并记日志:
     * 钱货已经两清, 让监听器的失败冒回买家的请求只会把一笔成功的购买报成失败。
     */
    static void fireTrade(MarketTrade trade) {
        for (TradeListener listener : tradeListeners) {
            try {
                listener.onTrade(trade);
            } catch (RuntimeException failure) {
                LOGGER.error("[miningdim] market trade listener failed for {} buying {} x{} from {}",
                        trade.buyer().getGameProfile().getName(), trade.itemId(), trade.count(), trade.seller(),
                        failure);
            }
        }
    }

    /** 服务端停止时清空, 防跨存档/跨重启脏引用 (供 ServerStoppingEvent 调用)。监听器列表不清。 */
    public static void reset() {
        marketEngine = null;
    }
}
