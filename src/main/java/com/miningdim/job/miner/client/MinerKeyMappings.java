package com.miningdim.job.miner.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.miner.MinerConstants;
import com.miningdim.job.miner.MinerSkill;
import com.miningdim.job.miner.network.MinerChainHoldC2S;
import com.miningdim.job.miner.network.MinerChainPreviewC2S;
import com.miningdim.job.miner.network.MinerNetwork;
import com.miningdim.job.miner.network.MinerToggleC2S;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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

    // ---- 连锁按住态跟踪 (FTB Ultimine 式; 客户端逻辑端单例, 客户端 tick 单线程访问) ----
    /** 上一 tick CHAIN 键是否按下 (用于识别按下/松开沿)。 */
    private static boolean chainWasDown = false;
    /** 按住期间距上次心跳的 tick 数 (达 {@link MinerConstants#CHAIN_HOLD_HEARTBEAT_TICKS} 重发 held=true)。 */
    private static int chainHeartbeatTicks = 0;
    /** 距上次预览请求的 tick 数 (兜底节流; 达 {@link MinerConstants#CHAIN_PREVIEW_REQUEST_INTERVAL_TICKS} 即便目标未变也重发)。 */
    private static int previewIntervalTicks = 0;
    /** 上次已请求预览的准星目标块 (目标变化即立即重发; null=未请求/无目标)。 */
    private static BlockPos lastPreviewTarget = null;

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
        // 连锁已改按住激活 (非 consumeClick 的一次性触发), 单独走 isDown 沿检测 + 心跳 + 预览请求。
        handleChainHold();
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

    /**
     * 连锁按住检测 + 预览请求 (FTB Ultimine 式): 用 {@link KeyMapping#isDown()} 检测按下/松开沿, 沿即时上报
     * {@link MinerChainHoldC2S}; 按住期间每 {@link MinerConstants#CHAIN_HOLD_HEARTBEAT_TICKS} 心跳重发 held=true 续期,
     * 并按准星目标块变化 (外加兜底节流) 发 {@link MinerChainPreviewC2S} 预览请求。松开即本地清预览槽 (更跟手, 不等 expire)。
     */
    private static void handleChainHold() {
        boolean down = CHAIN.isDown();
        if (down && !chainWasDown) {
            MinerNetwork.CHANNEL.sendToServer(new MinerChainHoldC2S(true)); // 按下沿: 立即上报激活。
            chainHeartbeatTicks = 0;
            previewIntervalTicks = 0;
            lastPreviewTarget = null; // 复位, 使按住后首个 tick 的目标必判"变化"立即请求预览。
        } else if (!down && chainWasDown) {
            MinerNetwork.CHANNEL.sendToServer(new MinerChainHoldC2S(false)); // 松开沿: 立即上报失效。
            MinerHighlightRenderer.clearPreview(); // 本地即清预览槽 (跟手; 服务端侧随 expire 自然停发)。
            lastPreviewTarget = null;
        } else if (down) {
            if (++chainHeartbeatTicks >= MinerConstants.CHAIN_HOLD_HEARTBEAT_TICKS) {
                MinerNetwork.CHANNEL.sendToServer(new MinerChainHoldC2S(true)); // 心跳续期。
                chainHeartbeatTicks = 0;
            }
            requestPreviewIfNeeded();
        }
        chainWasDown = down;
    }

    /** 按住期间发预览请求: 准星目标块变化即发, 外加每 {@link MinerConstants#CHAIN_PREVIEW_REQUEST_INTERVAL_TICKS} 兜底节流一次。 */
    private static void requestPreviewIfNeeded() {
        previewIntervalTicks++;
        BlockPos target = crosshairBlock();
        if (target == null) {
            lastPreviewTarget = null; // 未指向方块: 停发, 预览槽随 expire 自清。
            return;
        }
        boolean changed = !target.equals(lastPreviewTarget);
        if (changed || previewIntervalTicks >= MinerConstants.CHAIN_PREVIEW_REQUEST_INTERVAL_TICKS) {
            MinerNetwork.CHANNEL.sendToServer(new MinerChainPreviewC2S(target.immutable()));
            lastPreviewTarget = target.immutable();
            previewIntervalTicks = 0;
        }
    }

    /** 当前客户端准星指向的方块坐标 (Minecraft.hitResult 为 BLOCK 型时); 否则 null。 */
    private static BlockPos crosshairBlock() {
        HitResult hr = Minecraft.getInstance().hitResult;
        if (hr != null && hr.getType() == HitResult.Type.BLOCK) {
            return ((BlockHitResult) hr).getBlockPos();
        }
        return null;
    }
}
