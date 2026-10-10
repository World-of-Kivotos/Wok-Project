package com.miningdim.donation;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 捐赠箱的永久记录: 每条流水、每次协管变更、每次转让都写一行服务端日志; 拆除与指令清空写一行汇总
 * 加逐物品明细。
 *
 * 独立 logger 名 {@code miningdim/donation}, 服主可以在 log4j 配置里单独把它分流到文件; 方块实体里的
 * 300 条环形缓冲只是方便在游戏里翻看, 淘汰掉的条目以这里为准。每行都带维度与坐标, 一个服务器上有多个
 * 自管区捐赠箱时能直接 grep 出某一个箱子的全部历史。
 */
final class DonationAudit {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/donation");

    /** 没有可归属玩家的移除/清空的执行者记法。 */
    static final String NON_PLAYER = "non-player";

    private static final ResourceLocation UNKNOWN_ITEM = new ResourceLocation("minecraft", "air");

    private DonationAudit() {
    }

    static void entry(@Nullable Level level, BlockPos pos, DonationLedger.Entry entry) {
        LOGGER.info("[donation] {} {} {} actor={} uuid={} item={} count={}",
                dimension(level), coords(pos), entry.action(),
                entry.automation() ? "AUTOMATION" : entry.actorName(),
                entry.actorId() == null ? "-" : entry.actorId(),
                entry.itemId(), entry.count());
    }

    static void admin(@Nullable Level level, BlockPos pos, String event, String actor, String detail) {
        LOGGER.info("[donation] {} {} {} by={} {}", dimension(level), coords(pos), event, actor, detail);
    }

    /** 拆除箱子的玩家。非玩家路径 (指令 destroy、其它模组直接 destroyBlock) 没有 Actor, 记为 non-player。 */
    record Actor(UUID id, String name) {
    }

    /**
     * 方块被移除 (仓库散落): 一行汇总 (执行者、箱主、散落组数与件数) + 每种物品一行 REMOVED_DROP 明细。
     * 拆箱是唯一一次倒出整仓的路径, 执行者与物品两个维度都必须可查。返回写出的全部行, 方块实体留档。
     */
    static List<String> removal(@Nullable Level level, BlockPos pos, @Nullable Actor actor, String owner,
                                List<ItemStack> dropped) {
        String by = actor == null ? NON_PLAYER : describe(actor.id(), actor.name());
        return itemized(level, pos, "REMOVED by=" + by + " owner=" + owner, "dropped", "REMOVED_DROP",
                actor == null ? NON_PLAYER : actor.name(), actor == null ? "-" : actor.id().toString(), dropped, "");
    }

    /**
     * 仓库被指令路径清空 ({@link net.minecraft.world.Clearable}: /clone ... move、/setblock 与 /fill 的 replace、
     * 结构放置): 一行汇总 + 每种物品一行。没有可归属的玩家, 统一记为 non-player。
     */
    static void cleared(@Nullable Level level, BlockPos pos, String owner, List<ItemStack> stacks, int pendingAutomation) {
        itemized(level, pos, "CLEARED by=" + NON_PLAYER + " owner=" + owner, "cleared", "CLEARED_ITEM",
                NON_PLAYER, "-", stacks, " pending_automation=" + pendingAutomation);
    }

    /**
     * 汇总行 + 按物品合并的明细行 (同种物品的多组合并成一行), 逐行写日志并返回写出的内容。
     * 汇总行形如 {@code <head> <noun>_stacks=N <noun>_items=M<tail>}, 明细行与流水同格式
     * {@code <itemEvent> actor=.. uuid=.. item=.. count=..}。
     */
    static List<String> itemized(@Nullable Level level, BlockPos pos, String head, String noun, String itemEvent,
                                 String actorName, String actorId, List<ItemStack> stacks, String tail) {
        Map<ResourceLocation, Integer> byItem = new LinkedHashMap<>();
        int total = 0;
        for (ItemStack stack : stacks) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            byItem.merge(id == null ? UNKNOWN_ITEM : id, stack.getCount(), Integer::sum);
            total += stack.getCount();
        }
        List<String> lines = new ArrayList<>(byItem.size() + 1);
        lines.add(line(level, pos, head + " " + noun + "_stacks=" + stacks.size() + " " + noun + "_items=" + total + tail));
        for (Map.Entry<ResourceLocation, Integer> e : byItem.entrySet()) {
            lines.add(line(level, pos, itemEvent + " actor=" + actorName + " uuid=" + actorId
                    + " item=" + e.getKey() + " count=" + e.getValue()));
        }
        for (String line : lines) {
            LOGGER.info("{}", line);
        }
        return lines;
    }

    static String describe(@Nullable UUID id, String name) {
        return (name == null || name.isEmpty() ? "?" : name) + "(" + (id == null ? "-" : id) + ")";
    }

    private static String line(@Nullable Level level, BlockPos pos, String body) {
        return "[donation] " + dimension(level) + " " + coords(pos) + " " + body;
    }

    private static String dimension(@Nullable Level level) {
        return level == null ? "dim=?" : "dim=" + level.dimension().location();
    }

    private static String coords(BlockPos pos) {
        return "pos=" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
