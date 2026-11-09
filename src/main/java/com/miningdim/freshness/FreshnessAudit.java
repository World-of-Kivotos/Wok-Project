package com.miningdim.freshness;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code /freshness audit}: 巡查**观察点覆盖不到的容器**里还剩多少没盖章或已经腐烂的易腐品
 * (Spoilage_Granary_DesignSpec「开服迁移」末尾与「安全阀」: CNPC 银行、邮件、MTS 货箱与压缩抽屉
 * 这类不经过本模块的打开/关闭钩子, 开服前靠这条命令人工核对)。
 *
 * 只读: 不盖章、不换腐烂物、**不加载区块** (只走已经加载的区块里的方块实体), 所以可以在正式服上
 * 安全地跑。容器内容取 Forge 的物品接口 (抽屉一类只开接口), 没有接口的退回原版 {@link Container}。
 */
public final class FreshnessAudit {

    /** 一条发现 (某个容器里有几件无章/已腐烂的易腐品)。 */
    public record Finding(BlockPos pos, ResourceLocation block, int perishables, int unstamped, int rotten) {

        /** 值得报出来的条数 (无章或已腐烂)。 */
        public int problems() {
            return unstamped + rotten;
        }
    }

    /** 一次巡查的结果。 */
    public record Report(int containers, int perishables, int unstamped, int rotten, List<Finding> findings,
                         List<Finding> entries) {

        /** 有没有要继续查的地方。 */
        public boolean clean() {
            return unstamped == 0 && rotten == 0;
        }

        /** 命令的返回值: 需要处理的东西件数。 */
        public int problems() {
            return unstamped + rotten;
        }
    }

    private FreshnessAudit() {
    }

    /**
     * 巡查以 {@code center} 为中心、{@code radius} 格内的容器。
     *
     * @param radius 半径 (格); 只统计**已加载**区块里的方块实体
     */
    public static Report scan(ServerLevel level, BlockPos center, int radius) {
        long nowMinute = FreshnessClockData.nowMinute(level);
        int containers = 0;
        int perishables = 0;
        int unstamped = 0;
        int rotten = 0;
        List<Finding> findings = new ArrayList<>();
        List<Finding> entries = new ArrayList<>();

        int minChunkX = (center.getX() - radius) >> 4;
        int maxChunkX = (center.getX() + radius) >> 4;
        int minChunkZ = (center.getZ() - radius) >> 4;
        int maxChunkZ = (center.getZ() + radius) >> 4;
        long radiusSqr = (long) radius * radius;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                // 绝不为巡查去读区块: 没加载就跳过 (正式服上这条命令要能随便跑)。
                if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                net.minecraft.world.level.chunk.ChunkAccess loaded = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                LevelChunk chunk = loaded instanceof LevelChunk full ? full : null;
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    BlockPos pos = blockEntity.getBlockPos();
                    if (pos.distSqr(center) > radiusSqr) {
                        continue;
                    }
                    List<ItemStack> contents = contentsOf(blockEntity);
                    if (contents == null) {
                        continue;
                    }
                    int foundPerishables = 0;
                    int foundUnstamped = 0;
                    int foundRotten = 0;
                    boolean anything = false;
                    for (ItemStack stack : contents) {
                        if (stack.isEmpty()) {
                            continue;
                        }
                        anything = true;
                        if (!FreshnessCategories.spoils(stack)) {
                            continue;
                        }
                        // 件数按**物品个数**算 (3 个面包就是 3 件), 不是按格子数 —— 运营看的是「还剩多少件没章」。
                        foundPerishables += stack.getCount();
                        if (!FreshnessData.isStamped(stack)) {
                            foundUnstamped += stack.getCount();
                        }
                        if (FreshnessData.isRotten(stack, FreshnessCategories.shelfLifeHours(stack), nowMinute)) {
                            foundRotten += stack.getCount();
                        }
                    }
                    if (!anything) {
                        continue;
                    }
                    containers++;
                    perishables += foundPerishables;
                    unstamped += foundUnstamped;
                    rotten += foundRotten;
                    ResourceLocation block = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(blockEntity.getType());
                    Finding finding = new Finding(pos.immutable(), block == null
                            ? new ResourceLocation("unknown") : block,
                            foundPerishables, foundUnstamped, foundRotten);
                    entries.add(finding);
                    if (foundUnstamped > 0 || foundRotten > 0) {
                        findings.add(finding);
                    }
                }
            }
        }
        findings.sort(Comparator.comparingInt(Finding::problems).reversed()
                .thenComparing(Finding::pos));
        entries.sort(Comparator.comparing(Finding::pos));
        return new Report(containers, perishables, unstamped, rotten, List.copyOf(findings),
                List.copyOf(entries));
    }

    /**
     * 一个方块实体的内容; 不是容器就返回 {@code null}。
     *
     * Forge 物品接口优先 (压缩抽屉这类只开接口), 没有再退回原版 {@link Container}。
     */
    private static List<ItemStack> contentsOf(BlockEntity blockEntity) {
        IItemHandler handler = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null)
                .resolve().orElse(null);
        if (handler != null) {
            List<ItemStack> stacks = new ArrayList<>(handler.getSlots());
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                stacks.add(handler.getStackInSlot(slot));
            }
            return stacks;
        }
        if (blockEntity instanceof Container container) {
            List<ItemStack> stacks = new ArrayList<>(container.getContainerSize());
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                stacks.add(container.getItem(slot));
            }
            return stacks;
        }
        return null;
    }
}
