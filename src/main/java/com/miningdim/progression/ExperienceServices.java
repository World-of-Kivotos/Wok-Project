package com.miningdim.progression;

import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-lifetime access point for the server-wide experience service.
 *
 * <p>发放监听 (Achievement_System_DesignSpec 9.10) 与服务门面一样随进程存在: 生产代码只在 mod 构造期注册监听器, 没有
 * 重装配入口, 本模块也没有任何停服钩子会清它们。
 */
public final class ExperienceServices {
    private static IExperienceService experienceService;

    /** 多播: 成就等消费方各自注册, 按注册顺序逐个通知。 */
    private static final List<ExperienceAwardListener> AWARD_LISTENERS = new CopyOnWriteArrayList<>();

    private ExperienceServices() {
    }

    public static synchronized void register(IExperienceService service) {
        if (service == null) {
            throw new IllegalArgumentException("Cannot register null IExperienceService");
        }
        if (experienceService != null && experienceService != service) {
            throw new IllegalStateException("IExperienceService is already registered");
        }
        experienceService = service;
    }

    public static IExperienceService experienceService() {
        IExperienceService service = experienceService;
        if (service == null) {
            throw new IllegalStateException(
                    "ExperienceServices: service not registered (check WOK module order)");
        }
        return service;
    }

    /** 注册一个经验发放监听器 (mod 构造期调用); 可以注册多个。 */
    public static void registerAwardListener(ExperienceAwardListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Cannot register null ExperienceAwardListener");
        }
        AWARD_LISTENERS.add(listener);
    }

    /**
     * 广播一笔已经落到轨道的经验 (由路由器在 award 末尾调用)。按注册顺序逐个通知, 不吞监听器异常 ——
     * 捕获与记录是消费方自己的责任, 见 {@link ExperienceAwardListener}。
     */
    static void fireAward(Player player, ExperienceAward award, int levelBefore) {
        for (ExperienceAwardListener listener : AWARD_LISTENERS) {
            listener.onAward(player, award, levelBefore);
        }
    }
}
