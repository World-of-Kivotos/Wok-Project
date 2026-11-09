package com.miningdim.freshness;

import com.mojang.brigadier.Command;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * {@code /freshness ...} (Spoilage_Granary_DesignSpec「界面、提示与命令」)。
 *
 * <ul>
 *   <li>{@code stats} —— 档位、时钟、最近 24 小时与 7 天的三列计数 (盖章 / 本该腐烂 / 真换掉);</li>
 *   <li>{@code clock} —— 挂钟小时序号、累计偏移、心跳与「现在」的分钟序号;</li>
 *   <li>{@code dump} —— 把「物品 → 类别」清单写到存档目录, 供人工核对标签 (规格点名的手工核对入口);</li>
 *   <li>{@code stamp} / {@code strip} —— 给手上的东西盖章 / 去章 (奖励模板与商店货架配置前必须先</li>
 * </ul>
 *
 * 去章, 见反漏洞 #41)。四个子命令都要 OP 权限 ({@value #OP_LEVEL})。
 */
public final class FreshnessCommands {

    private static final int OP_LEVEL = 2;

    /** {@code /freshness audit} 的默认半径与上限 (格)。 */
    private static final int DEFAULT_AUDIT_RADIUS = 16;
    private static final int MAX_AUDIT_RADIUS = 64;
    /** 一次最多列几个容器 (剩下的提示换个位置再跑)。 */
    private static final int MAX_AUDIT_FINDINGS = 8;

    private FreshnessCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("freshness")
                .requires(source -> source.hasPermission(OP_LEVEL))
                .executes(context -> summary(context.getSource()))
                .then(Commands.literal("stats").executes(context -> stats(context.getSource())))
                .then(Commands.literal("clock").executes(context -> clock(context.getSource())))
                .then(Commands.literal("dump").executes(context -> dump(context.getSource())))
                .then(Commands.literal("audit")
                        .executes(context -> audit(context.getSource(), DEFAULT_AUDIT_RADIUS))
                        .then(Commands.argument("radius",
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, MAX_AUDIT_RADIUS))
                                .executes(context -> audit(context.getSource(),
                                        context.getArgument("radius", Integer.class)))))
                .then(Commands.literal("stamp").executes(context -> stamp(context.getSource())))
                .then(Commands.literal("strip").executes(context -> strip(context.getSource()))));
    }

    /**
     * {@code /freshness audit [<半径>]}: 巡查半径内**已加载**容器里还有多少无章 / 已腐烂的易腐品。
     *
     * 给观察点覆盖不到的地方用 (压缩抽屉、第三方银行与邮箱一类): 只读、不加载区块, 正式服上随便跑。
     * 返回值 = 需要处理的件数 (无章 + 已腐烂), 0 就是干净的。
     */
    private static int audit(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        BlockPos center = BlockPos.containing(source.getPosition());
        FreshnessAudit.Report report = FreshnessAudit.scan(level, center, radius);
        source.sendSuccess(() -> Component.literal("[新鲜度] 半径 " + radius + " 格内: 有东西的容器 "
                + report.containers() + " 个, 易腐品 " + report.perishables() + " 件, 其中无章 "
                + report.unstamped() + " 件、已腐烂 " + report.rotten() + " 件"), false);
        if (report.clean()) {
            source.sendSuccess(() -> Component.literal(
                    "[新鲜度] 没发现无章或已腐烂的易腐品 (观察点覆盖不到的地方才需要这条命令)"), false);
            return 0;
        }
        report.findings().stream().limit(MAX_AUDIT_FINDINGS).forEach(finding -> source.sendSuccess(
                () -> Component.literal("[新鲜度]  (" + finding.pos().getX() + ", " + finding.pos().getY()
                        + ", " + finding.pos().getZ() + ") " + finding.block() + ": 易腐 "
                        + finding.perishables() + " / 无章 " + finding.unstamped() + " / 已腐烂 "
                        + finding.rotten()), false));
        if (report.findings().size() > MAX_AUDIT_FINDINGS) {
            source.sendSuccess(() -> Component.literal("[新鲜度] 还有 "
                    + (report.findings().size() - MAX_AUDIT_FINDINGS) + " 个容器没列出来 (换个位置再跑一次)"),
                    false);
        }
        return report.problems();
    }

    private static int summary(CommandSourceStack source) {
        FreshnessClockData data = clockData(source);
        long now = FreshnessClock.currentWallHour();
        source.sendSuccess(() -> Component.literal("[新鲜度] 档位 " + FreshnessConfig.MODE.get()
                + ", 现在 = 第 " + now + " 小时, 累计偏移 " + data.clock().offsetHours() + " 小时, "
                + "统计环已记 " + data.stats().filledHours() + " 小时"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int stats(CommandSourceStack source) {
        FreshnessClockData data = clockData(source);
        long now = FreshnessClock.currentWallHour();
        FreshnessStats.Snapshot day = data.stats().totals(now - 24L);
        FreshnessStats.Snapshot week = data.stats().totals(now - 24L * 7L);
        FreshnessStats.Snapshot month = data.stats().totals(now - FreshnessStatsRing.HOURS_RETAINED);
        FreshnessStats.Snapshot pending = FreshnessStats.peek();

        source.sendSuccess(() -> Component.literal("[新鲜度] 档位 " + FreshnessConfig.MODE.get()
                + " (关闭=不记账, 试运行=只盖章并统计「本该腐烂」, 开启=真换腐烂物)"), false);
        source.sendSuccess(() -> Component.literal("[新鲜度] 最近 24 小时: 盖章 " + day.stamped()
                + ", 本该腐烂 " + day.wouldRot() + ", 真换掉 " + day.rotted()), false);
        source.sendSuccess(() -> Component.literal("[新鲜度] 最近 7 天: 盖章 " + week.stamped()
                + ", 本该腐烂 " + week.wouldRot() + ", 真换掉 " + week.rotted()
                + "; 最近 30 天同上口径: " + month.stamped() + " / " + month.wouldRot() + " / " + month.rotted()), false);
        source.sendSuccess(() -> Component.literal("[新鲜度] 本小时未入环: 盖章 " + pending.stamped()
                + ", 本该腐烂 " + pending.wouldRot() + ", 真换掉 " + pending.rotted()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int clock(CommandSourceStack source) {
        FreshnessClockData data = clockData(source);
        FreshnessClock clock = data.clock();
        long wallHour = FreshnessClock.currentWallHour();
        source.sendSuccess(() -> Component.literal("[新鲜度] 挂钟第 " + wallHour + " 小时; 上次观察 "
                + clock.lastSeenHour() + "; 心跳 " + data.heartbeatHour() + "; 累计偏移 " + clock.offsetHours()
                + " 小时; 现在(分钟序号) " + data.nowMinute()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int dump(CommandSourceStack source) {
        FreshnessClockData data = clockData(source);
        Map<FreshnessCategory, Integer> counts = new EnumMap<>(FreshnessCategory.class);
        StringBuilder text = new StringBuilder();
        text.append("# 物品 -> 新鲜度类别 (腐烂与粮仓 /freshness dump)\n");
        text.append("# 现在分钟序号 ").append(data.nowMinute()).append('\n');
        int spoiling = 0;
        int exempt = 0;
        for (var entry : ForgeRegistries.ITEMS.getEntries()) {
            ItemStack stack = new ItemStack(entry.getValue());
            var category = FreshnessCategories.categoryOf(stack);
            String id = entry.getKey().location().toString();
            if (category.isPresent()) {
                counts.merge(category.get(), 1, Integer::sum);
                spoiling++;
                text.append(id).append('\t').append(category.get().name().toLowerCase()).append('\n');
            } else if (FreshnessCategories.isExempt(stack)) {
                exempt++;
            }
        }
        text.append("# 会腐烂的物品 ").append(spoiling).append(" 种; 豁免(含不腐烂) ")
                .append(exempt).append(" 种\n");

        Path target = source.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("miningdim/freshness-dump.txt");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, text.toString(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            source.sendFailure(Component.literal("[新鲜度] 清单写入失败: " + failure.getMessage()));
            return 0;
        }
        StringBuilder summary = new StringBuilder("[新鲜度] 已写入 ").append(target).append(" : 会腐烂 ")
                .append(spoiling).append(" 种");
        counts.forEach((category, count) -> summary.append(", ").append(category.translationKey())
                .append(' ').append(count));
        String message = summary.toString();
        source.sendSuccess(() -> Component.literal(message), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int stamp(CommandSourceStack source) {
        ItemStack held = held(source);
        if (held.isEmpty()) {
            source.sendFailure(Component.literal("[新鲜度] 手上没有东西"));
            return 0;
        }
        if (!FreshnessCategories.spoils(held)) {
            source.sendFailure(Component.literal("[新鲜度] 这样东西不腐烂, 不需要盖章"));
            return 0;
        }
        long now = FreshnessClockData.nowMinute(source.getLevel());
        int shelfLifeHours = FreshnessCategories.shelfLifeHours(held);
        FreshnessData.stamp(held, now);
        source.sendSuccess(() -> Component.literal("[新鲜度] 已盖章: 剩 "
                + (FreshnessData.remainingMinutes(held, shelfLifeHours, now) / 60L) + " 小时"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int strip(CommandSourceStack source) {
        ItemStack held = held(source);
        if (held.isEmpty()) {
            source.sendFailure(Component.literal("[新鲜度] 手上没有东西"));
            return 0;
        }
        CompoundTag tag = held.getTag();
        if (tag == null || !tag.contains(FreshnessConstants.ROOT)) {
            source.sendFailure(Component.literal("[新鲜度] 这样东西本来就没有保鲜章"));
            return 0;
        }
        // 奖励模板、商店货架、新手礼包在配置前必须先去掉章, 否则每次发出去的都是老批次 (反漏洞 #41)。
        tag.remove(FreshnessConstants.ROOT);
        if (tag.isEmpty()) {
            held.setTag(null);
        }
        source.sendSuccess(() -> Component.literal("[新鲜度] 已去章: 下次被发现时会重新按「到手」盖章"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static ItemStack held(CommandSourceStack source) {
        var player = source.getPlayer();
        return player == null ? ItemStack.EMPTY : player.getMainHandItem();
    }

    private static FreshnessClockData clockData(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ServerLevel overworld = server.overworld();
        return FreshnessClockData.get(overworld);
    }
}
