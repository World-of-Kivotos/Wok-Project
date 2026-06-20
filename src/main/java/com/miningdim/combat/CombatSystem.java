package com.miningdim.combat;

import com.miningdim.core.Subsystem;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 战斗基建子系统: 注册玩家受击减伤的单点结算 handler ({@link PlayerDamageReduction}) 到 forgeBus。各职业把自己
 * 的命名减伤源 register 进 PlayerDamageReduction 的静态注册表; 本处只挂结算 handler。静态注册表对 register 顺序
 * 不敏感 (handler 运行期读表), 故本子系统在职业之前或之后注册均可。
 */
public final class CombatSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/combat");

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        forgeBus.register(new PlayerDamageReduction());
        LOGGER.info("[miningdim] combat subsystem registered (player damage-reduction resolver)");
    }

    @Override
    public String name() {
        return "CombatSystem";
    }
}
