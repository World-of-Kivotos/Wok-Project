package com.miningdim.rules;

import com.miningdim.core.Difficulty;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 矿洞三难度死亡物品规则。全局 keepInventory=true 时 EASY/MEDIUM 沿用原版保留行为, HARD 则把原版玩家
 * Inventory 中的物品强制加入死亡掉落集合并清空对应槽位。第三方 soulbound、墓碑或额外物品栏不属于原版
 * Inventory 路径, 仍需按实际安装的 mod 逐个真机验证。
 */
public final class MiningDeathRules {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/rules");

    /**
     * HIGH 必须早于 EconomySystem 的 NORMAL 掉落处理, 让新增物品继续服从统一的保留、快速消失或销毁模式。
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!player.level().dimension().equals(MiningConstants.MINING_LEVEL)) {
            return;
        }

        BlockPos pos = player.blockPosition();
        InstanceState instance = MiningServices.instanceManager().regionAt(pos.getX(), pos.getZ());
        if (instance == null || instance.difficulty() != Difficulty.HARD) {
            return;
        }

        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            ItemStack removed = inventory.removeItemNoUpdate(slot);
            if (EnchantmentHelper.hasVanishingCurse(removed)) {
                continue;
            }
            event.getDrops().add(new ItemEntity(
                    player.level(), player.getX(), player.getY(), player.getZ(), removed));
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (!event.getServer().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            LOGGER.warn("[miningdim] keepInventory=false: EASY/MEDIUM death item retention is not active");
        }
    }
}
