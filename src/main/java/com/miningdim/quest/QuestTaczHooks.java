package com.miningdim.quest;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.event.common.EntityKillByGunEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;

/**
 * TaCZ 边界层: 枪械击杀事实的翻译 + 隐藏任务线的枪械解锁判定。
 *
 * <b>只在 Forge 报告 TaCZ 已加载时才注册</b> ({@link QuestSystem} 里判), 与仓库既有的
 * {@code CaseTaczEventHooks} 同范式。核心任务层不含任何 {@code com.tacz.*} 引用, 因此没装 TaCZ 的服务器
 * 完全不会 classload 到本类。
 *
 * <b>部位判据的边界 (已实测)</b>: TaCZ 1.1.8 的 {@code EntityKillByGunEvent} 只暴露 {@code isHeadShot()}
 * 一个部位布尔; {@code GunDamageSourcePart} 是穿甲/非穿甲而非身体部位; 内部类 {@code EntityResult} 虽然存了
 * 命中点, 但没有经事件暴露。因此本类只产出爆头/非爆头, 不产出四肢部位 —— 那需要自研射线与包围盒分段,
 * 精度低于 TaCZ 自身判定且只对人形怪有意义。
 */
public final class QuestTaczHooks {

    private QuestTaczHooks() {
    }

    public static void register(IEventBus forgeBus) {
        forgeBus.register(new QuestTaczHooks());
    }

    /**
     * 枪械击杀。
     *
     * 距离取<b>击杀瞬间</b>射手与目标的直线距离, 不是开火瞬间的距离 —— TaCZ 没有暴露子弹起点 (
     * {@code EntityKineticBullet.startPos} 是私有字段且无 getter)。子弹飞行期间双方移动会带来偏差, 在狙击
     * 这种远距离低机动的场景下可忽略; 若将来 TaCZ 暴露起点, 这里应改用起点。
     */
    @SubscribeEvent
    public void onKillByGun(EntityKillByGunEvent event) {
        // 事件在两个逻辑端都发, 只在服务端记账, 否则单机会双倍计数。
        if (event.getLogicalSide() != LogicalSide.SERVER) {
            return;
        }
        if (!(event.getAttacker() instanceof ServerPlayer player)) {
            return;
        }
        LivingEntity victim = event.getKilledEntity();
        if (victim == null) {
            return;
        }
        ResourceLocation gunId = event.getGunId();
        double distance = player.position().distanceTo(victim.position());
        QuestEventHooks.post(new QuestFacts.GunKill(player, victim, gunId, gunTypeOf(gunId),
                event.isHeadShot(), distance, event.getBaseDamage()));
    }

    /**
     * 击发一次枪械 (塔科夫"倾泻火力")。
     *
     * 挂 {@code GunShootEvent} 而非 {@code GunFireEvent}: 后者是扣扳机的意图, 会被弹匣空/冷却等情形取消,
     * 而"倾泻火力"该数的是真的打出去的弹。霰弹枪一次击发只产生一条事件 (按扣扳机发, 不按弹丸发)。
     */
    @SubscribeEvent
    public void onGunShoot(GunShootEvent event) {
        if (event.getLogicalSide() != LogicalSide.SERVER) {
            return;
        }
        if (!(event.getShooter() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack gunStack = event.getGunItemStack();
        IGun gun = IGun.getIGunOrNull(gunStack);
        if (gun == null) {
            return;
        }
        ResourceLocation gunId = gun.getGunId(gunStack);
        QuestEventHooks.post(new QuestFacts.GunShot(player, gunId, gunTypeOf(gunId)));
    }

    /**
     * 降频扫描背包, 检出狙击枪即解锁神射手任务线。
     *
     * 为什么是扫描而不是挂"获得物品"事件: 原版/Forge 没有一个覆盖全部获取路径的事件 —— 开箱系统是直接往背包
     * 里塞 ItemStack, 既不触发 {@code EntityItemPickupEvent} 也不触发合成事件。扫描虽然笨, 但对"玩家现在
     * 手里有没有狙击枪"这个判据是完备的。
     *
     * 解锁本身是幂等的 ({@link QuestService#unlockChain} 已解锁则返回 false), 因此重复扫描不会重置进度。
     */
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (!QuestServices.active()) {
            return;
        }
        if (player.tickCount % QuestConfig.CHAIN_UNLOCK_SCAN_INTERVAL_TICKS.get() != 0) {
            return;
        }
        if (QuestServices.service().pool().chain(QuestPool.CHAIN_MARKSMAN) == null) {
            return;
        }
        if (!carriesGunOfType(player, QuestPool.GUN_TYPE_SNIPER)) {
            return;
        }
        if (QuestServices.service().unlockChain(player, QuestPool.CHAIN_MARKSMAN)) {
            QuestChainState state = QuestServices.service().boardOf(player).chain(QuestPool.CHAIN_MARKSMAN);
            player.sendSystemMessage(Component.literal("解锁任务线: " + state.chain().title()
                            + " —— 首阶段: " + state.current().definition().objective().describe())
                    .withStyle(ChatFormatting.GOLD));
        }
    }

    /** 背包 (含副手与盔甲槽) 里是否带着指定分类的 TaCZ 枪械。 */
    private static boolean carriesGunOfType(ServerPlayer player, String gunType) {
        for (ItemStack stack : player.getInventory().items) {
            if (isGunOfType(stack, gunType)) {
                return true;
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (isGunOfType(stack, gunType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGunOfType(ItemStack stack, String gunType) {
        if (stack.isEmpty()) {
            return false;
        }
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) {
            return false;
        }
        return gunType.equalsIgnoreCase(gunTypeOf(gun.getGunId(stack)));
    }

    /**
     * 解析枪械分类字符串 (pistol / sniper / rifle / ...)。
     *
     * 返回 null 表示 TaCZ 的服务端资源索引里没有这把枪 (整合包资源包缺失或 id 拼写不符)。不猜、不兜底成
     * 某个默认分类: 猜错会让"用狙击枪击杀"这类任务在错误的武器上计数。
     */
    private static String gunTypeOf(ResourceLocation gunId) {
        if (gunId == null) {
            return null;
        }
        return TimelessAPI.getCommonGunIndex(gunId).map(CommonGunIndex::getType).orElse(null);
    }
}
