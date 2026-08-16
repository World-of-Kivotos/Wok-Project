package com.miningdim.quest;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.EconomyServices;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.TradeWithVillagerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

/**
 * 原版事件到 {@link QuestFacts} 的翻译层 (不含任何可选 mod; TaCZ 侧另见 {@link QuestTaczHooks})。
 *
 * 本类只做三件事: 判定事件是否来自服务端玩家、翻译成事实、把结果的进度提示发给玩家。任何业务判定 (哪条任务
 * 计数、要不要标脏存档) 都在 {@link QuestService} 里, 这里一行都不做。
 */
public final class QuestEventHooks {

    /** 玩家进度提示的颜色; 与领奖提示区分开, 避免刷屏时看不出哪条是"可以领奖了"。 */
    private static final ChatFormatting PROGRESS_COLOR = ChatFormatting.GRAY;

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        // 玩家自己死在矿洞里: 本趟行程作废, 之后即使走出去也不算撤离 (死了被抬出去不是撤离)。
        if (event.getEntity() instanceof ServerPlayer dying
                && dying.level().dimension().equals(MiningConstants.MINING_LEVEL)) {
            QuestMiningVisits.onDiedInMining(dying);
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        post(new QuestFacts.EntityKill(player, event.getEntity()));
    }

    /**
     * 进出矿洞维度: 开始 / 结算一趟行程。
     *
     * 行程跟踪无条件执行 (不看任务系统开关), 只有最终事实的投递才过 {@link #post} 的闸 —— 否则运营方中途开关
     * 一次配置, 所有在途玩家的行程就会凭空消失。
     */
    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        boolean toMining = event.getTo().equals(MiningConstants.MINING_LEVEL);
        boolean fromMining = event.getFrom().equals(MiningConstants.MINING_LEVEL);
        if (toMining && !fromMining) {
            QuestMiningVisits.onEnterMining(player);
            return;
        }
        if (fromMining && !toMining) {
            QuestFacts.MiningExtraction extraction =
                    QuestMiningVisits.onLeaveMining(player, QuestConfig.EXTRACTION_MIN_DWELL_TICKS.get());
            if (extraction != null) {
                post(extraction);
            }
        }
    }

    /** 掉线: 丢弃在途行程。掉线不是撤离。 */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            QuestMiningVisits.onLoggedOut(player);
        }
    }

    /**
     * 登录: 人若仍在矿洞里 (断线重连回原实例), 重开一趟行程并从此刻起算。
     *
     * 重连不走维度切换事件 (玩家本来就在那个维度), 所以必须在这里补一次, 否则掉线过的玩家这一趟无论怎么走
     * 都不会被判成撤离。计时不接续掉线前的进度 —— 理由见 {@link QuestMiningVisits#onLoggedOut}。
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            QuestMiningVisits.onReconnect(player);
        }
    }

    /**
     * 破坏方块。用 LOWEST 优先级: 保护插件/领地系统通常在更高优先级取消事件, 跑在它们后面才不会把"被拦下的
     * 破坏"也算进任务进度。默认 {@code receiveCanceled = false}, 已取消的事件根本不会到达本方法。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        post(new QuestFacts.BlockMine(serverPlayer, event.getState()));
    }

    @SubscribeEvent
    public void onTradeWithVillager(TradeWithVillagerEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        post(new QuestFacts.VillagerTrade(player, event.getAbstractVillager()));
    }

    /**
     * 降频扫描玩家所在结构, 命中村庄则请求抛一条特殊任务。
     *
     * 结构查询要读区块的 structure starts, 不是零成本, 因此按 {@code structureScanIntervalTicks} 降频;
     * 用 {@code player.tickCount} 取模而非全局 tick, 天然把不同玩家的扫描错开到不同 tick, 避免整服同时查。
     */
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (!QuestServices.active() || isEconomyFrozen(player)) {
            return;
        }
        if (player.tickCount % QuestConfig.STRUCTURE_SCAN_INTERVAL_TICKS.get() != 0) {
            return;
        }
        ServerLevel level = player.serverLevel();
        StructureStart village = level.structureManager()
                .getStructureWithPieceAt(player.blockPosition(), StructureTags.VILLAGE);
        if (!village.isValid()) {
            return;
        }
        QuestProgress offered = QuestServices.service().tryOfferSpecial(player, level.getRandom());
        if (offered != null) {
            player.sendSystemMessage(Component.literal(
                            "接到特殊任务: " + offered.definition().title() + " (" + offered.definition().objective().describe() + ")")
                    .withStyle(ChatFormatting.AQUA));
        }
    }

    /**
     * 把一次事实交给任务服务, 并把有变化的任务回报给玩家。
     *
     * AFK 冻结期一律不计: 挂机刷怪塔能把"击杀僵尸 15 只"这种日常任务全自动跑完, 而任务奖励是信用点 faucet
     * —— 不拦就等于给挂机开了一条印钞路径。判据复用经济子系统已有的 AFK 判定, 不另立一套口径。
     */
    static void post(QuestFacts facts) {
        ServerPlayer player = facts.player();
        if (!QuestServices.active() || isEconomyFrozen(player)) {
            return;
        }
        List<QuestProgress> changed = QuestServices.service().record(facts);
        for (QuestProgress progress : changed) {
            announce(player, progress);
        }
    }

    static boolean isEconomyFrozen(ServerPlayer player) {
        return EconomyServices.isRegistered() && EconomyServices.economyService().isAfkFrozen(player);
    }

    /**
     * 播报一条任务的进度。只在达标那一次用醒目颜色提示可领奖, 中间进度用暗色 —— 一次群体击杀可能同时推进
     * 好几条任务, 全用醒目色会盖住真正需要玩家动作的那一条。
     */
    private static void announce(ServerPlayer player, QuestProgress progress) {
        QuestDefinition definition = progress.definition();
        if (progress.isComplete()) {
            player.sendSystemMessage(Component.literal(
                            "任务完成: " + definition.title() + " —— 用 /quest claim " + definition.id() + " 领奖")
                    .withStyle(ChatFormatting.GREEN));
            return;
        }
        player.sendSystemMessage(Component.literal(
                        definition.title() + " " + progress.count() + "/" + progress.requiredCount())
                .withStyle(PROGRESS_COLOR));
    }
}
