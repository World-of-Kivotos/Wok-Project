package com.miningdim.job.fisher;

import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 渔业数据包文件 (图鉴目录、体型档案) 共用的可选 MOD 门控。
 *
 * 文件根节点可写 {@code required_mod} 与可选的 {@code required_version}: 目标 MOD 未安装则整份跳过;
 * 装了但版本字符串不完全相等, 记一条 WARN 后整份跳过。只写版本不写 MOD 属于数据错误, 直接抛出。
 */
public final class FishingDataGate {
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/fishing");

    private FishingDataGate() {
    }

    public static boolean isAvailable(JsonObject root) {
        if (!root.has("required_mod")) {
            if (root.has("required_version")) {
                throw new IllegalArgumentException("Fishing data required_version needs required_mod");
            }
            return true;
        }
        String modId = GsonHelper.getAsString(root, "required_mod");
        var container = ModList.get().getModContainerById(modId);
        if (container.isEmpty()) {
            return false;
        }
        if (root.has("required_version")) {
            String required = GsonHelper.getAsString(root, "required_version");
            String installed = container.get().getModInfo().getVersion().toString();
            if (!required.equals(installed)) {
                LOGGER.warn("Skipping fishing compatibility data for {} {}: installed {}", modId, required, installed);
                return false;
            }
        }
        return true;
    }
}
