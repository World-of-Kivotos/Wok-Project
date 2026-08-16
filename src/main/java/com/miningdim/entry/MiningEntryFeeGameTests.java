package com.miningdim.entry;

import com.miningdim.chunk.ChunkServices;
import com.miningdim.config.MiningServerConfig;
import com.miningdim.core.Difficulty;
import com.miningdim.core.GenState;
import com.miningdim.core.IMiningNetwork;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyConstants.HighValueOre;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.job.JobId;
import com.miningdim.job.miner.MinerConstants;
import com.miningdim.network.TeleportResultS2C;
import com.miningdim.testutil.MockGameTestPlayers;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MiningEntryFeeGameTests {

    private static final String EMPTY = "empty";
    private static final String SYNC_BATCH = "entry_fee_sync";
    private static final String PROBE_BATCH = "entry_fee_probe";
    private static final String FREE_BATCH = "entry_fee_free";
    private static final String PAID_BATCH = "entry_fee_paid";
    private static final String RESETTING_BATCH = "entry_fee_resetting";
    private static final String BALANCE_DRIFT_BATCH = "entry_fee_balance_drift";
    private static final int ASYNC_TIMEOUT_TICKS = 400;
    private static final int MAX_PUMP_ATTEMPTS = 300;
    private static final String ENTERED_MESSAGE_KEY = "message.miningdim.enter.entered_hint";
    private static final String RESETTING_MESSAGE_KEY = "message.miningdim.enter.resetting";
    private static final String INSUFFICIENT_ACTIONBAR_KEY =
            "message.miningdim.gate.insufficient_funds_actionbar";
    private static final ResourceLocation MAIN_CHANNEL = new ResourceLocation(MiningConstants.MODID, "main");
    private static final int TELEPORT_RESULT_DISCRIMINATOR = 2;

    private MiningEntryFeeGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = SYNC_BATCH)
    public static void insufficientBalanceIsRejectedSynchronouslyWithoutCharging(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EmbeddedChannel channel = channelOf(player);
        drainSystemMessages(channel);
        setMaximumMinerLevel(player);
        EconomyService economy = newTestEconomy();
        economy.grant(player, Currency.CREDIT, 40L);

        InstanceState target = requireFixedInstance(helper, Difficulty.EASY);
        int refsBefore = target.refCount();
        MiningYieldProbe.clear();
        long previousFee = MiningServerConfig.ENTRY_FEE_EASY.get();
        try {
            MiningServerConfig.ENTRY_FEE_EASY.set(50L);
            withEconomyService(economy,
                    () -> new EntryGateway(helper.getLevel().getServer())
                            .requestEnter(player, Difficulty.EASY, false));
        } finally {
            MiningServerConfig.ENTRY_FEE_EASY.set(previousFee);
        }

        FeedbackPackets feedback = drainFeedbackPackets(channel);
        List<SystemMessage> messages = feedback.systemMessages();
        SystemMessage insufficient = findMessage(messages, GateResult.INSUFFICIENT_FUNDS.reasonKey());
        TeleportResultS2C insufficientResult = findTeleportResult(
                feedback.teleportResults(), INSUFFICIENT_ACTIONBAR_KEY);
        long balanceAfter = economy.creditBalance(player);
        IMiningPlayerData data = dataOf(player);
        boolean hasFallbackAfter = data.hasFallback();
        long instanceAfter = data.currentInstanceId();
        int refsAfter = target.refCount();
        MiningYieldProbe.YieldSample unexpectedProbe = MiningYieldProbe.finish(player).orElse(null);
        MiningYieldProbe.clear();

        removePlayer(player);
        helper.assertTrue(insufficient != null,
                "missing system message " + GateResult.INSUFFICIENT_FUNDS.reasonKey()
                        + "; observed=" + messages);
        helper.assertTrue(balanceAfter == 40L,
                "an initial balance below the quoted fee must remain exactly 40, got " + balanceAfter);
        helper.assertTrue(!hasFallbackAfter && instanceAfter == IMiningPlayerData.NO_INSTANCE,
                "the synchronous balance gate must reject before writing fallback or instance capability state");
        helper.assertTrue(refsAfter == refsBefore,
                "the synchronous balance gate must not change target refCount; before="
                        + refsBefore + " after=" + refsAfter);
        helper.assertTrue(unexpectedProbe == null,
                "a synchronous balance rejection must not start a yield probe session");
        helper.assertTrue(insufficient.args().length == 1
                        && insufficient.args()[0] instanceof Long shortfall
                        && shortfall == 10L,
                "the insufficient-funds system message must carry the exact shortfall 10, got "
                        + java.util.Arrays.toString(insufficient.args()));
        helper.assertTrue(insufficientResult != null
                        && insufficientResult.resultEnum() == IMiningNetwork.TeleportResult.REJECTED_FULL
                        && insufficientResult.instanceId() == -1L
                        && insufficientResult.queuePos() == -1,
                "synchronous insufficient funds must send REJECTED_FULL with instanceId=-1, queuePos=-1, and the "
                        + "dedicated no-argument actionbar key; observed=" + feedback.teleportResults());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = PROBE_BATCH)
    public static void realOreSettlementRecordsExactGrossInTheActiveYieldProbe(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        EconomyService economy = new EconomyService(
                SqliteEconomyLedger.openInMemory(),
                new AbuseGuard(),
                id -> states.computeIfAbsent(id, ignored -> new PlayerAbuseState()));

        final long credited;
        MiningYieldProbe.clear();
        try {
            long firstTier = EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER;
            economy.grantDaily(player, firstTier, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY, firstTier);
            MiningYieldProbe.start(player, Difficulty.HARD);
            credited = economy.settleOreSale(player, HighValueOre.DIAMOND, 1, 321.75D);
        } catch (RuntimeException | Error failure) {
            MiningYieldProbe.clear();
            removePlayer(player);
            throw failure;
        }
        helper.runAfterDelay(2, () -> {
            MiningYieldProbe.YieldSample sample;
            try {
                sample = MiningYieldProbe.finish(player).orElseThrow(
                        () -> new IllegalStateException("active yield probe did not produce a sample"));
            } finally {
                MiningYieldProbe.clear();
                removePlayer(player);
            }

            helper.assertTrue(credited == 192L,
                    "after one full faucet tier, net credit must be floor(321 * 0.6) = 192, got " + credited);
            helper.assertTrue(sample.difficulty() == Difficulty.HARD,
                    "the sample must retain the entry difficulty HARD, got " + sample.difficulty());
            helper.assertTrue(sample.oreDrops() == 1L && sample.creditGross() == 321L,
                    "the probe must record pre-decay gross 321 rather than net 192, got drops="
                            + sample.oreDrops() + " gross=" + sample.creditGross());
            helper.assertTrue(sample.dwellTicks() == 2L,
                    "finishing two server ticks later must report dwellTicks=2, got " + sample.dwellTicks());
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = FREE_BATCH,
            timeoutTicks = ASYNC_TIMEOUT_TICKS)
    public static void freeEntrySucceedsWithoutTouchingTheBalance(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EmbeddedChannel channel = channelOf(player);
        drainSystemMessages(channel);
        setMaximumMinerLevel(player);
        EconomyService economy = newTestEconomy();
        economy.grant(player, Currency.CREDIT, 777L);

        boolean defaultsAreZero = MiningServerConfig.ENTRY_FEE_EASY.getDefault() == 0L
                && MiningServerConfig.ENTRY_FEE_MEDIUM.getDefault() == 0L
                && MiningServerConfig.ENTRY_FEE_HARD.getDefault() == 0L;
        long balanceBefore = economy.creditBalance(player);
        InstanceState target = requireFixedInstance(helper, Difficulty.EASY);
        int refsBefore = target.refCount();
        PlayerOrigin origin = PlayerOrigin.capture(player);
        EntryGateway gateway = new EntryGateway(helper.getLevel().getServer());
        Runnable cleanup = () -> withEconomyService(economy, () -> cleanupPlayer(player, gateway, origin));
        MiningYieldProbe.clear();

        long previousFee = MiningServerConfig.ENTRY_FEE_EASY.get();
        try {
            MiningServerConfig.ENTRY_FEE_EASY.set(0L);
            withEconomyService(economy, () -> gateway.requestEnter(player, Difficulty.EASY, false));
        } finally {
            MiningServerConfig.ENTRY_FEE_EASY.set(previousFee);
        }

        pumpUntilMessage(helper, channel, ENTERED_MESSAGE_KEY, MAX_PUMP_ATTEMPTS,
                () -> withEconomyService(economy, gateway::tick), (message, teleportResults) -> {
            long balanceDuring = economy.creditBalance(player);
            long instanceDuring = dataOf(player).currentInstanceId();
            ResourceKey<Level> dimensionDuring = player.level().dimension();
            int refsDuring = target.refCount();
            cleanup.run();
            int refsAfterCleanup = target.refCount();

            helper.assertTrue(defaultsAreZero,
                    "all three entry-fee config defaults must remain exactly zero until yield data is priced");
            helper.assertTrue(balanceDuring == balanceBefore,
                    "a quoted fee of zero must leave the balance exactly " + balanceBefore
                            + ", got " + balanceDuring);
            helper.assertTrue(instanceDuring == target.instanceId()
                            && MiningConstants.MINING_LEVEL.equals(dimensionDuring),
                    "free entry must complete through the real gateway into the target resident instance");
            helper.assertTrue(refsDuring == refsBefore + 1 && refsAfterCleanup == refsBefore,
                    "successful free entry must add exactly one ref and cleanup must restore it; before="
                            + refsBefore + " during=" + refsDuring + " after=" + refsAfterCleanup);
            helper.succeed();
        }, cleanup);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = PAID_BATCH,
            timeoutTicks = ASYNC_TIMEOUT_TICKS)
    public static void paidEntryChargesTheCapturedQuoteExactlyOnce(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EmbeddedChannel channel = channelOf(player);
        drainSystemMessages(channel);
        setMaximumMinerLevel(player);
        EconomyService economy = newTestEconomy();
        economy.grant(player, Currency.CREDIT, 250L);

        long fee = 75L;
        long balanceBefore = economy.creditBalance(player);
        InstanceState target = requireFixedInstance(helper, Difficulty.EASY);
        int refsBefore = target.refCount();
        PlayerOrigin origin = PlayerOrigin.capture(player);
        EntryGateway gateway = new EntryGateway(helper.getLevel().getServer());
        Runnable cleanup = () -> withEconomyService(economy, () -> cleanupPlayer(player, gateway, origin));
        MiningYieldProbe.clear();

        long previousFee = MiningServerConfig.ENTRY_FEE_EASY.get();
        try {
            MiningServerConfig.ENTRY_FEE_EASY.set(fee);
            withEconomyService(economy, () -> gateway.requestEnter(player, Difficulty.EASY, false));
        } finally {
            MiningServerConfig.ENTRY_FEE_EASY.set(previousFee);
        }

        pumpUntilMessage(helper, channel, ENTERED_MESSAGE_KEY, MAX_PUMP_ATTEMPTS,
                () -> withEconomyService(economy, gateway::tick), (message, teleportResults) -> {
            long balanceDuring = economy.creditBalance(player);
            long instanceDuring = dataOf(player).currentInstanceId();
            ResourceKey<Level> dimensionDuring = player.level().dimension();
            int refsDuring = target.refCount();
            boolean duplicateProbeRejected;
            try {
                MiningYieldProbe.start(player, Difficulty.HARD);
                duplicateProbeRejected = false;
            } catch (IllegalStateException expected) {
                duplicateProbeRejected = true;
            }
            MiningYieldProbe.record(player, 2, 50L);
            cleanup.run();
            int refsAfterCleanup = target.refCount();
            MiningYieldProbe.YieldSample lingeringSample = MiningYieldProbe.finish(player).orElse(null);
            MiningYieldProbe.clear();

            helper.assertTrue(balanceDuring == balanceBefore - fee,
                    "successful paid entry must charge the captured quote exactly once: expected "
                            + (balanceBefore - fee) + ", got " + balanceDuring);
            helper.assertTrue(instanceDuring == target.instanceId()
                            && MiningConstants.MINING_LEVEL.equals(dimensionDuring),
                    "paid entry must complete into the target resident instance");
            helper.assertTrue(refsDuring == refsBefore + 1 && refsAfterCleanup == refsBefore,
                    "successful paid entry must add exactly one ref and cleanup must restore it; before="
                            + refsBefore + " during=" + refsDuring + " after=" + refsAfterCleanup);
            helper.assertTrue(duplicateProbeRejected,
                    "EntryGateway must start exactly one yield probe session on successful entry");
            helper.assertTrue(lingeringSample == null,
                    "EntrySystem.leaveToFallback must finish and remove the active yield probe session");
            helper.succeed();
        }, cleanup);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = RESETTING_BATCH,
            timeoutTicks = ASYNC_TIMEOUT_TICKS)
    public static void resettingAfterTheGateDoesNotChargeOrEnter(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EmbeddedChannel channel = channelOf(player);
        drainSystemMessages(channel);
        setMaximumMinerLevel(player);
        EconomyService economy = newTestEconomy();
        economy.grant(player, Currency.CREDIT, 100L);

        long fee = 60L;
        long balanceBefore = economy.creditBalance(player);
        InstanceState target = requireFixedInstance(helper, Difficulty.EASY);
        int refsBefore = target.refCount();
        PlayerOrigin origin = PlayerOrigin.capture(player);
        EntryGateway gateway = new EntryGateway(helper.getLevel().getServer());
        Runnable cleanup = () -> withEconomyService(economy, () -> cleanupPlayer(player, gateway, origin));
        MiningYieldProbe.clear();

        long previousFee = MiningServerConfig.ENTRY_FEE_EASY.get();
        try {
            MiningServerConfig.ENTRY_FEE_EASY.set(fee);
            withEconomyService(economy, () -> gateway.requestEnter(player, Difficulty.EASY, false));
        } finally {
            MiningServerConfig.ENTRY_FEE_EASY.set(previousFee);
        }

        Runnable advanceWhileResetting = () -> {
            GenState originalState = target.genState();
            target.setGenState(GenState.RESETTING);
            try {
                withEconomyService(economy, gateway::tick);
            } finally {
                target.setGenState(originalState);
            }
        };
        pumpUntilMessage(helper, channel, RESETTING_MESSAGE_KEY, MAX_PUMP_ATTEMPTS,
                advanceWhileResetting, (message, teleportResults) -> {
                    long balanceAfter = economy.creditBalance(player);
                    IMiningPlayerData data = dataOf(player);
                    long instanceAfter = data.currentInstanceId();
                    boolean fallbackWritten = data.hasFallback();
                    ResourceKey<Level> dimensionAfter = player.level().dimension();
                    int refsAfter = target.refCount();
                    cleanup.run();
                    MiningYieldProbe.YieldSample unexpectedProbe = MiningYieldProbe.finish(player).orElse(null);
                    MiningYieldProbe.clear();

                    helper.assertTrue(balanceAfter == balanceBefore,
                            "an entry aborted by RESETTING must not charge any credit; expected "
                                    + balanceBefore + ", got " + balanceAfter);
                    helper.assertTrue(instanceAfter == IMiningPlayerData.NO_INSTANCE
                                    && origin.dimension().equals(dimensionAfter),
                            "RESETTING must abort before capability state or dimension changes");
                    helper.assertTrue(fallbackWritten,
                            "the RESETTING scenario must pass the synchronous gate and write its fallback first");
                    helper.assertTrue(refsAfter == refsBefore && target.refCount() == refsBefore,
                            "RESETTING must not change target refCount; before=" + refsBefore
                                    + " after=" + refsAfter + " cleanup=" + target.refCount());
                    helper.assertTrue(unexpectedProbe == null,
                            "an entry aborted by RESETTING must not start a yield probe session");
                    helper.succeed();
                }, cleanup);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BALANCE_DRIFT_BATCH,
            timeoutTicks = ASYNC_TIMEOUT_TICKS)
    public static void balanceSpentAfterTheGateAbortsBeforeTeleport(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EmbeddedChannel channel = channelOf(player);
        drainSystemMessages(channel);
        setMaximumMinerLevel(player);
        EconomyService economy = newTestEconomy();
        economy.grant(player, Currency.CREDIT, 90L);

        long fee = 90L;
        long balanceBefore = economy.creditBalance(player);
        InstanceState target = requireFixedInstance(helper, Difficulty.EASY);
        int refsBefore = target.refCount();
        PlayerOrigin origin = PlayerOrigin.capture(player);
        EntryGateway gateway = new EntryGateway(helper.getLevel().getServer());
        Runnable cleanup = () -> withEconomyService(economy, () -> cleanupPlayer(player, gateway, origin));
        MiningYieldProbe.clear();

        long previousFee = MiningServerConfig.ENTRY_FEE_EASY.get();
        try {
            MiningServerConfig.ENTRY_FEE_EASY.set(fee);
            withEconomyService(economy, () -> gateway.requestEnter(player, Difficulty.EASY, false));
        } finally {
            MiningServerConfig.ENTRY_FEE_EASY.set(previousFee);
        }
        boolean balanceExhausted = economy.tryCharge(player, Currency.CREDIT, balanceBefore);

        pumpUntilMessage(helper, channel, GateResult.INSUFFICIENT_FUNDS.reasonKey(),
                MAX_PUMP_ATTEMPTS, () -> withEconomyService(economy, gateway::tick),
                (message, teleportResults) -> {
                    long balanceAfter = economy.creditBalance(player);
                    IMiningPlayerData data = dataOf(player);
                    long instanceAfter = data.currentInstanceId();
                    boolean fallbackWritten = data.hasFallback();
                    ResourceKey<Level> dimensionAfter = player.level().dimension();
                    int refsAfter = target.refCount();
                    Object[] messageArgs = message.args();
                    boolean ticketsRemain = ChunkServices.ticketService().hasTickets(target.instanceId());
                    TeleportResultS2C insufficientResult = findTeleportResult(
                            teleportResults, INSUFFICIENT_ACTIONBAR_KEY);
                    cleanup.run();
                    MiningYieldProbe.YieldSample unexpectedProbe = MiningYieldProbe.finish(player).orElse(null);
                    MiningYieldProbe.clear();

                    helper.assertTrue(balanceExhausted && balanceAfter == 0L,
                            "the test must spend the full post-gate balance and the failed completion must leave it at 0");
                    helper.assertTrue(instanceAfter == IMiningPlayerData.NO_INSTANCE
                                    && origin.dimension().equals(dimensionAfter),
                            "a completion-time charge failure must abort before capability state or teleport");
                    helper.assertTrue(fallbackWritten,
                            "the balance-drift scenario must pass the initial gate before spending the balance");
                    helper.assertTrue(refsAfter == refsBefore && target.refCount() == refsBefore,
                            "a completion-time charge failure must not change target refCount; before="
                                    + refsBefore + " after=" + refsAfter + " cleanup=" + target.refCount());
                    helper.assertTrue(!ticketsRemain,
                            "a completion-time charge failure must release every force ticket for instance "
                                    + target.instanceId());
                    helper.assertTrue(messageArgs.length == 1
                                    && messageArgs[0] instanceof Long shortfall
                                    && shortfall == fee,
                            "completion-time insufficient feedback must carry exact shortfall " + fee
                                    + ", got " + java.util.Arrays.toString(messageArgs));
                    helper.assertTrue(unexpectedProbe == null,
                            "a completion-time charge failure must not start a yield probe session");
                    helper.assertTrue(insufficientResult != null
                                    && insufficientResult.resultEnum()
                                    == IMiningNetwork.TeleportResult.REJECTED_FULL
                                    && insufficientResult.instanceId() == target.instanceId()
                                    && insufficientResult.queuePos() == -1,
                            "completion-time insufficient funds must send REJECTED_FULL with the target instance, "
                                    + "queuePos=-1, and the dedicated no-argument actionbar key; observed="
                                    + teleportResults);
                    helper.succeed();
                }, cleanup);
    }

    private static void pumpUntilMessage(
            GameTestHelper helper,
            EmbeddedChannel channel,
            String expectedKey,
            int attemptsRemaining,
            Runnable advance,
            BiConsumer<SystemMessage, List<TeleportResultS2C>> onMessage,
            Runnable cleanup) {
        pumpUntilMessage(helper, channel, expectedKey, attemptsRemaining, advance,
                onMessage, cleanup, new ArrayList<>(), new ArrayList<>());
    }

    private static void pumpUntilMessage(
            GameTestHelper helper,
            EmbeddedChannel channel,
            String expectedKey,
            int attemptsRemaining,
            Runnable advance,
            BiConsumer<SystemMessage, List<TeleportResultS2C>> onMessage,
            Runnable cleanup,
            List<String> observedKeys,
            List<TeleportResultS2C> observedTeleportResults) {
        helper.runAfterDelay(1, () -> {
            FeedbackPackets feedback;
            try {
                advance.run();
                feedback = drainFeedbackPackets(channel);
            } catch (RuntimeException | Error failure) {
                cleanup.run();
                throw failure;
            }
            List<SystemMessage> messages = feedback.systemMessages();
            observedTeleportResults.addAll(feedback.teleportResults());
            for (SystemMessage message : messages) {
                observedKeys.add(message.key());
            }
            SystemMessage terminal = findMessage(messages, expectedKey);
            if (terminal != null) {
                onMessage.accept(terminal, List.copyOf(observedTeleportResults));
                return;
            }
            if (attemptsRemaining <= 1) {
                cleanup.run();
                helper.fail("timed out waiting for system message " + expectedKey
                        + "; observed keys=" + observedKeys);
                return;
            }
            pumpUntilMessage(helper, channel, expectedKey, attemptsRemaining - 1,
                    advance, onMessage, cleanup, observedKeys, observedTeleportResults);
        });
    }

    private static EconomyService newTestEconomy() {
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        return new EconomyService(
                SqliteEconomyLedger.openInMemory(),
                new AbuseGuard(),
                id -> states.computeIfAbsent(id, ignored -> new PlayerAbuseState()));
    }

    private static void withEconomyService(EconomyService economy, Runnable action) {
        var previous = EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
        EconomyServices.registerEconomyService(economy);
        try {
            action.run();
        } finally {
            if (previous == null) {
                EconomyServices.reset();
            } else {
                EconomyServices.registerEconomyService(previous);
            }
        }
    }

    private static void cleanupPlayer(ServerPlayer player, EntryGateway gateway, PlayerOrigin origin) {
        IMiningPlayerData data = dataOf(player);
        long instanceId = data.currentInstanceId();
        if (instanceId != IMiningPlayerData.NO_INSTANCE) {
            if (!new EntrySystem().leaveToFallback(player)) {
                throw new IllegalStateException("failed to leave active mining instance " + instanceId);
            }
        } else {
            player.teleportTo(origin.level(), origin.x(), origin.y(), origin.z(), origin.yRot(), origin.xRot());
        }
        removePlayer(player);
        // 若测试因等待目标消息超时而仍有 PendingEnter, 摘出 PlayerList 后再推进一次会走断线回滚并释放票。
        gateway.tick();
    }

    private static InstanceState requireFixedInstance(GameTestHelper helper, Difficulty difficulty) {
        InstanceState target = MiningWebUiActions.fixedInstanceFor(difficulty);
        if (target == null) {
            helper.fail("missing resident instance for difficulty " + difficulty);
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return target;
    }

    private static IMiningPlayerData dataOf(ServerPlayer player) {
        return MiningCapabilities.get(player).orElseThrow(
                () -> new IllegalStateException("mock player has no mining capability"));
    }

    private static void setMaximumMinerLevel(ServerPlayer player) {
        dataOf(player).jobProgress(JobId.MINER).setLevel(MinerConstants.MAX_LEVEL);
    }

    private static EmbeddedChannel channelOf(ServerPlayer player) {
        return (EmbeddedChannel) player.connection.connection.channel();
    }

    private static List<SystemMessage> drainSystemMessages(EmbeddedChannel channel) {
        return drainFeedbackPackets(channel).systemMessages();
    }

    private static FeedbackPackets drainFeedbackPackets(EmbeddedChannel channel) {
        List<SystemMessage> systemMessages = new ArrayList<>();
        List<TeleportResultS2C> teleportResults = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            try {
                if (outbound instanceof ClientboundSystemChatPacket packet) {
                    Component content = packet.content();
                    if (content.getContents() instanceof TranslatableContents translatable) {
                        systemMessages.add(new SystemMessage(translatable.getKey(), translatable.getArgs()));
                    }
                } else if (outbound instanceof ClientboundCustomPayloadPacket payload
                        && MAIN_CHANNEL.equals(payload.getIdentifier())) {
                    FriendlyByteBuf copy = new FriendlyByteBuf(payload.getData().copy());
                    try {
                        int discriminator = copy.readVarInt();
                        if (discriminator == TELEPORT_RESULT_DISCRIMINATOR) {
                            teleportResults.add(TeleportResultS2C.decode(copy));
                        }
                    } finally {
                        copy.release();
                    }
                }
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        }
        return new FeedbackPackets(List.copyOf(systemMessages), List.copyOf(teleportResults));
    }

    private static SystemMessage findMessage(List<SystemMessage> messages, String expectedKey) {
        return messages.stream()
                .filter(message -> expectedKey.equals(message.key()))
                .findFirst()
                .orElse(null);
    }

    private static TeleportResultS2C findTeleportResult(
            List<TeleportResultS2C> results, String expectedReasonKey) {
        return results.stream()
                .filter(result -> expectedReasonKey.equals(result.reasonKey()))
                .findFirst()
                .orElse(null);
    }

    private static void removePlayer(ServerPlayer player) {
        player.server.getPlayerList().remove(player);
    }

    private record PlayerOrigin(ServerLevel level, ResourceKey<Level> dimension,
                                double x, double y, double z, float yRot, float xRot) {

        private static PlayerOrigin capture(ServerPlayer player) {
            return new PlayerOrigin(player.serverLevel(), player.level().dimension(),
                    player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        }
    }

    private record SystemMessage(String key, Object[] args) {
    }

    private record FeedbackPackets(
            List<SystemMessage> systemMessages,
            List<TeleportResultS2C> teleportResults) {
    }
}
