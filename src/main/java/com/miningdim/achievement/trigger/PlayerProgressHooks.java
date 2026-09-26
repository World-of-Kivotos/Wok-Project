package com.miningdim.achievement.trigger;

import com.miningdim.achievement.AchievementIds;
import com.miningdim.achievement.AchievementServices;
import com.miningdim.achievement.meta.AchievementCatalog;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 玩家层面的事件钩子: 成就数量 (6.5)、婚姻 (9.5 social/married)、共享背包 (social/shared_backpack), 以及登录时的补查。
 *
 * <ul>
 *   <li>成就数量: {@link AdvancementEvent.AdvancementEarnEvent} 里只处理成就数量候选 (查询快照里的 countableIds, 数据包
 *       重载时重建), 用快照重新数一遍再触发 achievement_count。配方解锁也发这个事件, 一次集合查找就放过去了。</li>
 *   <li>婚姻: P1 读玩家 Capability 里的婚姻指针 (核心模块的 {@link IMiningPlayerData}, 不引用婚姻模块), 登录时查一次,
 *       每次存档 (自动保存、/save-all) 时对在线玩家补查一次, 打开共享背包时也先补查一次; 结过婚的玩家拿到成就后原版就摘掉了
 *       监听, 之后的补查是空操作。P2 有了婚礼监听接口后改成当场触发。</li>
 *   <li>共享背包: 玩家打开注册名为 {@code miningdim:marriage_backpack} 的菜单。按注册名认, 不引用婚姻模块的菜单类。
 *       婚姻模块只在核实了有效婚姻之后才打开这个菜单, 所以先补 married 再触发: 刚办完婚礼、登录与存档的补查都还没轮到的
 *       玩家, 不会在父成就 social/married 之前拿到 social/shared_backpack。</li>
 *   <li>登录补查: 统计项阈值 (数据包新增了阈值成就时, 已经达标的玩家上线即得)、成就数量、婚姻。</li>
 * </ul>
 */
public final class PlayerProgressHooks {

    /** 婚姻模块共享背包菜单的注册名。 */
    static final ResourceLocation SHARED_BACKPACK_MENU = AchievementIds.id("marriage_backpack");

    @SubscribeEvent
    public void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        AchievementTriggers.STAT_AT_LEAST.trigger(player);
        AchievementTriggers.ACHIEVEMENT_COUNT.trigger(player,
                AchievementServices.catalog().countEarned(player.getAdvancements()));
        checkMarried(player);
    }

    /** 每次存档对在线玩家补查婚姻。每个维度各发一次存档事件, 只认主世界那一次。 */
    @SubscribeEvent
    public void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension().equals(Level.OVERWORLD)) {
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                checkMarried(player);
            }
        }
    }

    @SubscribeEvent
    public void onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        AchievementCatalog catalog = AchievementServices.catalog();
        if (catalog.countableIds().contains(event.getAdvancement().getId())) {
            AchievementTriggers.ACHIEVEMENT_COUNT.trigger(player, catalog.countEarned(player.getAdvancements()));
        }
    }

    @SubscribeEvent
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer player && isSharedBackpack(event.getContainer())) {
            // 父成就在先: 菜单能打开说明婚姻刚被核实过, 指针此刻一定表明已婚。
            checkMarried(player);
            AchievementTriggers.OPEN_SHARED_BACKPACK.trigger(player);
        }
    }

    /** 婚姻指针表明已婚 (关系 id 或配偶二者有其一) 时触发 married。 */
    static void checkMarried(ServerPlayer player) {
        boolean married = MiningCapabilities.get(player)
                .map(data -> data.marriageId() != IMiningPlayerData.NO_MARRIAGE || data.spouseUUID() != null)
                .orElse(false);
        if (married) {
            AchievementTriggers.MARRIED.trigger(player);
        }
    }

    /**
     * 菜单是否共享背包。有的菜单没有 MenuType (比如原版马的物品栏, 走 openHorseInventory 也发 Open 事件), 对它们
     * {@link AbstractContainerMenu#getType()} 直接抛 UnsupportedOperationException; 这类菜单一律不是共享背包。
     */
    static boolean isSharedBackpack(AbstractContainerMenu menu) {
        MenuType<?> type;
        try {
            type = menu.getType();
        } catch (UnsupportedOperationException noMenuType) {
            return false;
        }
        return SHARED_BACKPACK_MENU.equals(ForgeRegistries.MENU_TYPES.getKey(type));
    }
}
