package com.miningdim.freshness;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 观察点 (规格「观察点」表里不需要 mixin 的那一批): 掉落物进世界、玩家背包定时扫描、白名单容器开关、
 * 右键方块 / 右键物品 / 与实体交互之前、登录。
 *
 * 每个观察点只做一件事: 把看到的东西交给 {@link FreshnessSettle} 结算 —— 未盖章的盖章, 已腐烂的按开关换成腐烂物。
 * 需要注入原版方法的观察点 (漏斗、合成格、熔炉、营火、FD 厨具) 走本模块自己的 mixin 配置, 不在本类里。
 */
public final class FreshnessObservation {

    /** 玩家背包扫描间隔 (规格: 每 2 秒错峰一次)。 */
    private static final int PLAYER_SCAN_INTERVAL_TICKS = 40;

    private FreshnessObservation() {
    }

    // ============================================================
    // 掉落物进世界
    // ============================================================

    /** 掉落物生成 (含从存档读出的) 时结算: 没章的盖现在, 已腐烂的换成腐烂物。 */
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!FreshnessConfig.stampingEnabled()
                || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof ItemEntity itemEntity)) {
            return;
        }
        settleStackInEntity(itemEntity, FreshnessClockData.nowMinute(level));
    }

    private static void settleStackInEntity(ItemEntity itemEntity, long nowMinute) {
        ItemStack stack = itemEntity.getItem();
        FreshnessSourceContext.inheritInto(stack, nowMinute);
        List<ItemStack> returns = new ArrayList<>();
        ItemStack replacement = FreshnessSettle.settleSlot(stack, nowMinute,
                FreshnessConfig.replacementEnabled(), returns);
        if (replacement.isEmpty()) {
            return;
        }
        itemEntity.setItem(replacement);
        for (ItemStack returned : returns) {
            itemEntity.level().addFreshEntity(new ItemEntity(itemEntity.level(),
                    itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(), returned));
        }
    }

    // ============================================================
    // 玩家: 定时扫描 + 登录 + 打开容器 + 右键之前
    // ============================================================

    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        // 错峰: 每个玩家落在自己 id 的那一 tick, 100 人在线时每 tick 约 2.5 人 (规格「性能」)。
        if (Math.floorMod(player.tickCount + player.getId(), PLAYER_SCAN_INTERVAL_TICKS) != 0) {
            return;
        }
        settlePlayer(player);
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            settlePlayer(player);
        }
    }

    /** 打开或关闭任何容器之前/之后都先结算背包 (反漏洞 #22: 抢在 2 秒扫描之前把东西塞进不检查的容器)。 */
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        settlePlayer(player);
        settleWhitelistedContainer(player, event.getContainer());
    }

    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        settlePlayer(player);
        settleWhitelistedContainer(player, event.getContainer());
    }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        settleFor(player(event));
    }

    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        // 开吃那一刻的检查: 结算背包会把过期食物换成腐烂物, 于是原版看到的不再是食物, 吃不下 (反漏洞 #14)。
        settleFor(player(event));
    }

    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        settleFor(player(event));
    }

    private static ServerPlayer player(PlayerInteractEvent event) {
        return event.getEntity() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
    }

    private static void settleFor(ServerPlayer player) {
        if (player != null) {
            settlePlayer(player);
        }
    }

    // ============================================================
    // 结算实现
    // ============================================================

    /** 结算玩家背包 (主背包 + 快捷栏 + 副手 + 盔甲都在这一个 Container 里)。 */
    public static int settlePlayer(ServerPlayer player) {
        if (!FreshnessConfig.stampingEnabled()) {
            return 0;
        }
        long nowMinute = FreshnessClockData.nowMinute(player.serverLevel());
        List<ItemStack> returns = new ArrayList<>();
        int rotted = FreshnessSettle.settleContainer(player.getInventory(), nowMinute,
                FreshnessConfig.replacementEnabled(), returns);
        giveOrDrop(player, returns);
        return rotted;
    }

    /**
     * 结算白名单容器: 只处理真正属于方块、末影箱或本 MOD 容器的格子 (安全阀: 「别的模组用箱子界面做商店菜单,
     * 给展示物品盖章会弄坏它」)。玩家自己那一半由 {@link #settlePlayer} 单独结算, 这里按 Slot 的 container 跳过。
     */
    private static int settleWhitelistedContainer(ServerPlayer player, AbstractContainerMenu menu) {
        if (!FreshnessConfig.stampingEnabled() || !whitelisted(menu)) {
            return 0;
        }
        long nowMinute = FreshnessClockData.nowMinute(player.serverLevel());
        List<ItemStack> returns = new ArrayList<>();
        int rotted = 0;
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) {
                continue;
            }
            ItemStack replacement = FreshnessSettle.settleSlot(slot.getItem(), nowMinute,
                    FreshnessConfig.replacementEnabled(), returns);
            if (!replacement.isEmpty()) {
                slot.set(replacement);
                rotted++;
            }
        }
        giveOrDrop(player, returns);
        return rotted;
    }

    /** 白名单: 原版箱子 (含末影箱、9×1 到 9×6)、3×3 发射器、潜影盒、漏斗。 */
    /** 白名单容器 (箱子 / 潜影盒 / 漏斗 / 发射器): 与 Shift 快移合并共用 {@link FreshnessContainers} 那一份判据。 */
    private static boolean whitelisted(AbstractContainerMenu menu) {
        return FreshnessContainers.isWhitelistedMenu(menu);
    }

    private static void giveOrDrop(ServerPlayer player, List<ItemStack> returns) {
        for (ItemStack stack : returns) {
            if (!stack.isEmpty() && !player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
    }
}
