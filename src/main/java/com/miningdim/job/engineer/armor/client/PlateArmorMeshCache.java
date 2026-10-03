package com.miningdim.job.engineer.armor.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.engineer.armor.PlateArmorVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * 按外观缓存插板护甲的烘焙网格：第一次穿戴渲染时才从客户端资源管理器读取，资源重载（F3+T、切换资源包）时整表清空。
 *
 * <p>读取失败（文件缺失、JSON 损坏、违反数据契约）也会被缓存为"无网格"，只在该资源周期内打一条警告，
 * 之后该外观安静地退回原版胸甲模型，不会每帧重复读盘刷日志；下次资源重载后会重新尝试。
 * 穿戴贴图是逐面打包的图集，退回的原版模型按箱式 uv 取色，会显示成贴图错乱的原版胸甲，警告里写明了这一点。</p>
 *
 * <p>读写都只发生在客户端主线程：getHumanoidArmorModel 在渲染线程调用，
 * ResourceManagerReloadListener 的应用阶段也由主线程执行，因此用普通 EnumMap 即可。</p>
 */
@Mod.EventBusSubscriber(modid = MiningConstants.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PlateArmorMeshCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/engineer/armor");
    private static final Map<PlateArmorVariant, Optional<PlateArmorMesh>> MESHES =
            new EnumMap<>(PlateArmorVariant.class);

    private PlateArmorMeshCache() {
    }

    @SubscribeEvent
    public static void registerReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> MESHES.clear());
    }

    /** 网格在资源包里的位置：assets/miningdim/armor_meshes/plate_armor_&lt;id&gt;.json。 */
    public static ResourceLocation location(PlateArmorVariant variant) {
        return new ResourceLocation(MiningConstants.MODID, "armor_meshes/" + variant.itemId() + ".json");
    }

    /** 取该外观的网格；缺失或损坏时返回 null，调用方应退回原版模型。 */
    @Nullable
    public static PlateArmorMesh get(PlateArmorVariant variant) {
        Optional<PlateArmorMesh> cached = MESHES.get(variant);
        if (cached == null) {
            cached = load(Minecraft.getInstance().getResourceManager(), variant);
            MESHES.put(variant, cached);
        }
        return cached.orElse(null);
    }

    private static Optional<PlateArmorMesh> load(ResourceManager manager, PlateArmorVariant variant) {
        ResourceLocation location = location(variant);
        Optional<Resource> resource = manager.getResource(location);
        if (resource.isEmpty()) {
            LOGGER.warn("插板护甲网格 {} 不存在，{} 在本资源周期内退回原版胸甲模型（贴图是逐面图集，会显示为贴图错乱的原版胸甲）",
                    location, variant.itemId());
            return Optional.empty();
        }
        try (Reader reader = resource.get().openAsReader()) {
            return Optional.of(PlateArmorMesh.parse(reader, variant.itemId()));
        } catch (Exception exception) {
            // 资源包可被玩家替换，任何解析异常都只能降级显示，不能让一件护甲拖垮整个实体渲染。
            LOGGER.warn("插板护甲网格 {} 读取或校验失败，{} 在本资源周期内退回原版胸甲模型（贴图是逐面图集，会显示为贴图错乱的原版胸甲）",
                    location, variant.itemId(), exception);
            return Optional.empty();
        }
    }
}
