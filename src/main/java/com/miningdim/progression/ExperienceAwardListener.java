package com.miningdim.progression;

import net.minecraft.world.entity.player.Player;

/**
 * 经验发放完成后的监听器 (Achievement_System_DesignSpec 9.10)。成就等只读消费方经
 * {@link ExperienceServices#registerAwardListener} 注册, 路由器在一笔经验落到轨道、读回发放后快照之后逐个通知,
 * 经验模块不引用任何消费方。
 *
 * <p>异常由消费方自己捕获并记录日志; 路由器不替它们吞掉, 漏接的异常会照常冒到发放经验的调用方。
 */
@FunctionalInterface
public interface ExperienceAwardListener {

    /**
     * @param player      获得经验的玩家 (可能是 FakePlayer 或非服务端玩家, 由消费方自行过滤)
     * @param award       本次发放的结果, 其中快照是发放之后的总经验与等级
     * @param levelBefore 发放之前该轨道的等级
     */
    void onAward(Player player, ExperienceAward award, int levelBefore);
}
