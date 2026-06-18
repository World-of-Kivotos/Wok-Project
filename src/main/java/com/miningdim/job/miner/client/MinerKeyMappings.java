package com.miningdim.job.miner.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.miner.MinerSkill;
import com.miningdim.job.miner.network.MinerNetwork;
import com.miningdim.job.miner.network.MinerToggleC2S;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.lwjgl.glfw.GLFW;

/**
 * 矿工键位注册与按键发包 (Miner_Job_DesignSpec 第十一章: KeyMapping + C2S 翻 per-player 开关)。仅客户端加载。
 *
 * RegisterKeyMappingsEvent (modBus) 注册键位; 客户端 tick (forgeBus) 轮询 consumeClick 发 {@link MinerToggleC2S}。
 * 全部默认未绑定 (GLFW.GLFW_KEY_UNKNOWN), 玩家在控制设置里自绑, 避免与原版/其它 mod 默认键冲突。
 * 键位分类 lang key = category.miningdim.miner (GUI 分组)。
 *
 * 注册事件 {@link RegisterKeyMappingsEvent} 在 mod 总线 (内部静态 {@link ModBus}); 客户端 tick 在 forge 总线
 * (本类自身, 默认 FORGE 订阅)。两类事件总线不同, 故拆两个 EventBusSubscriber。
 */
@Mod.EventBusSubscriber(modid = MiningConstants.MODID, value = Dist.CLIENT)
public final class MinerKeyMappings {

    private MinerKeyMappings() {
    }

    /** RegisterKeyMappingsEvent 是 mod 总线事件, 单独挂 MOD 总线订阅器 (与 tick 的 forge 订阅分开)。 */
    @Mod.EventBusSubscriber(modid = MiningConstants.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {

        private ModBus() {
        }

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(ORE_SCAN);
            event.register(TRAP_SCAN);
            event.register(TUNNEL);
            event.register(EVACUATE);
            event.register(DECOY);
            event.register(CHAIN);
            event.register(AUTO_COLLECT);
            event.register(AUTO_SMELT);
        }
    }

    private static final String CATEGORY = "category." + MiningConstants.MODID + ".miner";

    public static final KeyMapping ORE_SCAN = make("ore_scan");
    public static final KeyMapping TRAP_SCAN = make("trap_scan");
    public static final KeyMapping TUNNEL = make("tunnel");
    public static final KeyMapping EVACUATE = make("evacuate");
    public static final KeyMapping DECOY = make("decoy");
    public static final KeyMapping CHAIN = make("chain");
    public static final KeyMapping AUTO_COLLECT = make("auto_collect");
    public static final KeyMapping AUTO_SMELT = make("auto_smelt");

    private static KeyMapping make(String name) {
        return new KeyMapping(
                "key." + MiningConstants.MODID + ".miner." + name,
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                CATEGORY);
    }

    /** forgeBus 客户端 tick: 轮询 consumeClick, 命中即发对应技能的 C2S (一次按下一次发, 服务端权威处理)。 */
    @SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        // consumeClick 在 while 内排空一次按下队列 (避免漏键), 每个键对应一个 MinerSkill。
        sendIfClicked(ORE_SCAN, MinerSkill.ORE_SCAN);
        sendIfClicked(TRAP_SCAN, MinerSkill.TRAP_SCAN);
        sendIfClicked(TUNNEL, MinerSkill.TUNNEL);
        sendIfClicked(EVACUATE, MinerSkill.EVACUATE);
        sendIfClicked(DECOY, MinerSkill.DECOY);
        sendIfClicked(CHAIN, MinerSkill.CHAIN);
        sendIfClicked(AUTO_COLLECT, MinerSkill.AUTO_COLLECT);
        sendIfClicked(AUTO_SMELT, MinerSkill.AUTO_SMELT);
    }

    private static void sendIfClicked(KeyMapping key, MinerSkill skill) {
        boolean fired = false;
        while (key.consumeClick()) {
            fired = true;
        }
        if (fired) {
            MinerNetwork.CHANNEL.sendToServer(new MinerToggleC2S((byte) skill.ordinal()));
        }
    }
}
