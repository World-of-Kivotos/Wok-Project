package com.miningdim.caseopening;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runtime locator populated at server start and cleared before the SQLite connection closes.
 *
 * <p>开箱结算监听 ({@link #registerOpeningListener}) 与 {@code MiningServices.registerInstanceResetListener} 同一
 * 多播范式: 下游模块 (成就) 在 mod 构造期注册进来, 本模块不依赖它们; 监听器列表不随 {@link #reset} 清空。
 */
public final class CaseServices {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/caseopening");

    private static volatile CaseOpeningService service;

    private static final List<CaseOpeningListener> openingListeners = new CopyOnWriteArrayList<>();

    private CaseServices() {
    }

    public static void register(CaseOpeningService next) {
        if (next == null) {
            throw new IllegalArgumentException("case opening service must not be null");
        }
        if (service != null) {
            throw new IllegalStateException("case opening service already registered");
        }
        service = next;
    }

    public static boolean isRegistered() {
        return service != null;
    }

    public static CaseOpeningService service() {
        CaseOpeningService current = service;
        if (current == null) {
            throw new IllegalStateException("case opening service is not ready");
        }
        return current;
    }

    /** 注册一个开箱结算监听器 (见 {@link CaseOpeningListener}); 按注册顺序通知。 */
    public static void registerOpeningListener(CaseOpeningListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("case opening listener must not be null");
        }
        openingListeners.add(listener);
    }

    /**
     * 通知一次已结算的开箱 (落锚事务的提交后队列调用)。逐个吞掉监听器的异常并记日志: 钱与皮肤已经落盘,
     * 让监听器的失败冒回开箱流程, 只会把一次成功的开箱报成失败, 甚至触发不该有的对账。
     */
    static void fireOpeningSettled(SettledOpening opening) {
        for (CaseOpeningListener listener : openingListeners) {
            try {
                listener.onOpeningSettled(opening);
            } catch (RuntimeException failure) {
                LOGGER.error("[miningdim] case opening listener failed for opening {} of {}",
                        opening.openingId(), opening.ownerId(), failure);
            }
        }
    }

    /** 服务端停止时丢掉服务实例; 监听器列表不清。 */
    public static void reset() {
        service = null;
    }
}
