package com.miningdim.rules;

import com.miningdim.core.MiningServices;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * R7 放置白名单判定 (无状态助手)。把 IMiningConfig.placeWhitelist() 的字符串 id 列表解析为
 * Block 集合, 判定某次放置的方块是否被允许。
 *
 * 不缓存解析结果: 每次判定实时读 config (与 IMiningConfig 契约一致, /reload 改白名单即时生效)。
 * 白名单条目逐项经 ForgeRegistries.BLOCKS 解析; 无效 id (拼写错/未注册方块) 跳过并记一次警告,
 * 不静默掩盖配置笔误 (异常必须痛: 这里是配置层而非业务热路径, 用日志暴露而非抛断服更合理)。
 */
final class PlacementRules {

    private PlacementRules() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/rules");

    /**
     * 该方块是否允许在矿山维度内放置 (在白名单内为 true)。比较以方块注册 id 为键。
     * 白名单为空时一律拒绝 (= 维度内禁放任何方块, 配置者的显式选择)。
     */
    static boolean isPlacementAllowed(Block placed) {
        ResourceLocation placedId = ForgeRegistries.BLOCKS.getKey(placed);
        if (placedId == null) {
            // 未注册方块理论上不会进放置事件; 出现即异常情形, 拒绝并暴露。
            LOGGER.warn("[miningdim] placement of unregistered block {} blocked in mining dimension", placed);
            return false;
        }
        return resolveWhitelist().contains(placedId);
    }

    /**
     * 解析白名单为方块注册 id 集合。逐项 new ResourceLocation 校验, 再确认对应方块已注册;
     * 直接比较 ResourceLocation 而非 Block 实例, 避免无效 id 解析到 minecraft:air 的误判
     * (getValue 对未知 id 返回 AIR 的默认值会把任何错拼条目悄悄等价为 air, 故改比对 key)。
     */
    private static Set<ResourceLocation> resolveWhitelist() {
        List<String> raw = MiningServices.config().placeWhitelist();
        Set<ResourceLocation> ids = new HashSet<>(raw.size() * 2);
        for (String entry : raw) {
            ResourceLocation id;
            try {
                id = new ResourceLocation(entry);
            } catch (net.minecraft.ResourceLocationException badId) {
                LOGGER.warn("[miningdim] invalid block id '{}' in rules.placeWhitelist; skipped", entry);
                continue;
            }
            if (!ForgeRegistries.BLOCKS.containsKey(id)) {
                LOGGER.warn("[miningdim] rules.placeWhitelist entry '{}' is not a registered block; skipped", entry);
                continue;
            }
            ids.add(id);
        }
        return ids;
    }
}
