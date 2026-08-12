package com.miningdim.job.engineer.armor.integration;

import com.miningdim.job.engineer.armor.PlateArmorEquipmentHandler;
import com.miningdim.job.engineer.armor.item.PlateArmorItem;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * TaCZ 一弹一次磨损的短期账本。Pre 保存命中开始时的实际 ItemStack 引用，Post/Kill 再结算；
 * 即使致死流程已把装备移入掉落物，同一引用仍能收到耐久变化。
 */
public final class PlateArmorTaczWearLedger {

    private static final long EXPIRE_TICKS = 100L;
    private static final int MAX_PENDING = 4096;

    private final Map<ShotKey, PendingArmor> pending = new HashMap<>();

    public void capture(UUID bulletId, Player target) {
        long now = target.level().getGameTime();
        prune(now);
        ShotKey key = new ShotKey(bulletId, target.getUUID());
        ItemStack stack = target.getItemBySlot(EquipmentSlot.CHEST);
        if (stack.getItem() instanceof PlateArmorItem armor && armor.isFunctional(stack)) {
            pending.put(key, new PendingArmor(armor, stack, now + EXPIRE_TICKS));
        } else {
            pending.remove(key);
        }
    }

    public boolean settle(UUID bulletId, Player target, double incomingDamage) {
        PendingArmor entry = pending.remove(new ShotKey(bulletId, target.getUUID()));
        if (entry == null) {
            return false;
        }
        entry.armor().applyCombatWear(entry.stack(), incomingDamage, target);
        PlateArmorEquipmentHandler.synchronize(target);
        return true;
    }

    public int pendingCount() {
        return pending.size();
    }

    private void prune(long now) {
        Iterator<PendingArmor> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().expiresAt() < now) {
                iterator.remove();
            }
        }
        if (pending.size() >= MAX_PENDING) {
            pending.clear();
        }
    }

    private record ShotKey(UUID bulletId, UUID targetId) {
    }

    private record PendingArmor(PlateArmorItem armor, ItemStack stack, long expiresAt) {
    }
}
