package com.miningdim.job.tarot;

import com.miningdim.job.tarot.card.TarotCardLoader;
import com.miningdim.job.tarot.craft.TarotCraftService;
import com.miningdim.job.tarot.pack.PackGachaService;

/**
 * 塔罗师运行期单例服务持有 (本包内静态访问点)。{@link TarotCardItem} / 卡包 / 合成等 use 入口需要取用
 * 冷却管理 / 调度器 / 最大生命管理 / 牌效加载器 / 效果引擎, 但这些是有状态的运行期对象, 不宜每处 new。
 *
 * 由 {@link TarotSystem} 在 register 内 {@link #init} 一次性装配 (服务端单进程), 本包类经 getter 取用;
 * 未 init 即取用抛 IllegalStateException (装配缺陷暴露, 异常纪律)。{@link #reset} 在 ServerStopping 清引用。
 *
 * 仅本包内访问 (package-private getter 不外泄), 跨子系统协作仍走 JobServices/seam (模块化铁律 2)。
 */
public final class TarotRuntime {

    private TarotRuntime() {
    }

    private static TarotCooldownManager cooldown;
    private static ScheduledEffectManager scheduler;
    private static MaxHealthModifierManager maxHealth;
    private static TarotCardLoader cardLoader;
    private static TarotEffectEngine effectEngine;
    private static PackGachaService gacha;
    private static TarotCraftService craft;

    static void init(TarotCooldownManager cooldown,
                     ScheduledEffectManager scheduler,
                     MaxHealthModifierManager maxHealth,
                     TarotCardLoader cardLoader,
                     TarotEffectEngine effectEngine,
                     PackGachaService gacha,
                     TarotCraftService craft) {
        TarotRuntime.cooldown = cooldown;
        TarotRuntime.scheduler = scheduler;
        TarotRuntime.maxHealth = maxHealth;
        TarotRuntime.cardLoader = cardLoader;
        TarotRuntime.effectEngine = effectEngine;
        TarotRuntime.gacha = gacha;
        TarotRuntime.craft = craft;
    }

    static void reset() {
        cooldown = null;
        scheduler = null;
        maxHealth = null;
        cardLoader = null;
        effectEngine = null;
        gacha = null;
        craft = null;
    }

    public static TarotCooldownManager cooldown() {
        return require(cooldown, "TarotCooldownManager");
    }

    public static ScheduledEffectManager scheduler() {
        return require(scheduler, "ScheduledEffectManager");
    }

    public static MaxHealthModifierManager maxHealth() {
        return require(maxHealth, "MaxHealthModifierManager");
    }

    public static TarotCardLoader cardLoader() {
        return require(cardLoader, "TarotCardLoader");
    }

    public static TarotEffectEngine effectEngine() {
        return require(effectEngine, "TarotEffectEngine");
    }

    public static PackGachaService gacha() {
        return require(gacha, "PackGachaService");
    }

    public static TarotCraftService craft() {
        return require(craft, "TarotCraftService");
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalStateException("TarotRuntime." + name
                    + " not initialized (TarotSystem.register must run first)");
        }
        return value;
    }
}
