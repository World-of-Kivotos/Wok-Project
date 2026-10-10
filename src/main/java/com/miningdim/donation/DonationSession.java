package com.miningdim.donation;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次界面会话内的存取聚合: 同一玩家打开一次界面、关闭一次界面, 期间对同一物品的所有放入合成一个数,
 * 所有取出合成另一个数。会话结束时整体交给 {@link DonationBoxBlockEntity#commitSession} 落账。
 *
 * 不跨会话合并: 关界面再开就是新会话, 查账时能看出"这个人来过几次"。每个菜单实例持有自己的一份, 只在
 * 服务端主线程读写。
 */
final class DonationSession {

    private final Map<ResourceLocation, Integer> deposits = new LinkedHashMap<>();
    private final Map<ResourceLocation, Integer> withdrawals = new LinkedHashMap<>();

    void deposit(Item item, int count) {
        add(deposits, item, count);
    }

    void withdraw(Item item, int count) {
        add(withdrawals, item, count);
    }

    private static void add(Map<ResourceLocation, Integer> target, Item item, int count) {
        if (count <= 0) {
            return;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (id != null) {
            target.merge(id, count, Integer::sum);
        }
    }

    boolean isEmpty() {
        return deposits.isEmpty() && withdrawals.isEmpty();
    }

    Map<ResourceLocation, Integer> deposits() {
        return Collections.unmodifiableMap(deposits);
    }

    Map<ResourceLocation, Integer> withdrawals() {
        return Collections.unmodifiableMap(withdrawals);
    }

    /** 落账后清空, 防止同一会话被重复落账 (removed 在极端路径上可能被调两次)。 */
    void clear() {
        deposits.clear();
        withdrawals.clear();
    }
}
