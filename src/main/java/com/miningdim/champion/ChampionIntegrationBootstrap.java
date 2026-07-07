package com.miningdim.champion;

import com.miningdim.champion.integration.ChampionAttackHandler;
import com.miningdim.champion.integration.ChampionBloodPoolHandler;
import com.miningdim.champion.integration.ChampionBossBarHandler;
import com.miningdim.champion.integration.ChampionDotTickHandler;
import com.miningdim.champion.integration.ChampionParticleHandler;
import com.miningdim.champion.integration.ChampionPromoter;
import com.miningdim.champion.integration.ChampionRewardHandler;
import com.miningdim.champion.integration.ChampionSelfEffectHandler;
import com.miningdim.champion.integration.affix.MiningAffixTypes;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 精英怪集成层装配入口 (Champions 触点收敛中转; compileOnly 铁律落点)。本类引用 integration 包的类
 * ({@link MiningAffixTypes} / {@link ChampionPromoter} / 血池+奖励 handler), 而这些类 import
 * top.theillusivec4.champions.* —— 故本类的加载会连带触发 Champions 类加载。
 *
 * 铁律: 本类只能被 {@link ChampionSystem#register} 在 {@code ModList.isLoaded("champions")} 守卫为真时调用。
 * JVM 惰性类加载: {@code ChampionSystem} 对 {@link #assemble} 的调用在守卫 if 之后才执行, 故 Champions 未加载时
 * 本类及 integration 包永不被类加载 (dev GameTest 安全, 不触 NoClassDefFoundError)。
 *
 * 装配三件事:
 *  1. 词条注册: {@link MiningAffixTypes#register} 触发 35 词条向 AffixRegistry.AFFIXES 注册 (static 初始化)。
 *  2. 升格 seam: {@link ChampionSpawnSeam#bind} 注入 {@link ChampionPromoter}, 压力子系统 spawnMob 成功后回调。
 *  3. 血池/奖励 handler 挂 forgeBus。
 */
final class ChampionIntegrationBootstrap {

    private ChampionIntegrationBootstrap() {
    }

    /**
     * 装配集成层 (仅 Champions 已加载时调用)。
     *
     * @param forgeBus forge 事件总线 (挂血池/奖励 handler)
     */
    static void assemble(IEventBus forgeBus) {
        // 1. 词条注册 (触发 MiningAffixTypes static block, 把 35 条目追加到 Champions 的 DeferredRegister)。
        MiningAffixTypes.register();

        // 2. 升格 seam 绑定: 压力子系统 spawnMob 成功后经 ChampionSpawnSeam.promote 回调本 promoter 升格冠军。
        ChampionSpawnSeam.bind(new ChampionPromoter());

        // 3. 血池受击 (改伤/拦死) + 奖励 (贡献累计/死亡结算) + 自建 BOSS 血条显示 (名/星级/词条名) handler 挂 forgeBus。
        forgeBus.register(new ChampionBloodPoolHandler());
        forgeBus.register(new ChampionRewardHandler());
        forgeBus.register(new ChampionBossBarHandler());

        // 4. 攻击类词条效果 (即时伤合并/DoT 刷层/寒霜减速/撕裂易伤/强酸损甲/混沌限频) + DoT 秒结算 tick handler。
        forgeBus.register(new ChampionAttackHandler());
        forgeBus.register(new ChampionDotTickHandler());

        // 5. 词条环境指示粒子 (服务端主动播每词条签名粒子, 显示层视觉反馈)。
        forgeBus.register(new ChampionParticleHandler());

        // 6. 自身被动词条 (Stage2 批1): 脱战/战斗回血 + 高速移动移速 modifier + 反震反伤 (每秒扫近玩家冠军 + 受击点)。
        forgeBus.register(new ChampionSelfEffectHandler());
    }
}
