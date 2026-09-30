package com.miningdim.district.guard.create;

import com.miningdim.district.guard.GuardSettings;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 机械动力方块的分类 (设计文档 22.4): 什么方块算"机器"。按顺序:
 * <ol>
 *   <li>注册名的命名空间不在 namespaces 里 → 不是机器;</li>
 *   <li>在 allowBlocks 里 → 不是机器;</li>
 *   <li>在 denyBlocks 里 → 是机器;</li>
 *   <li>{@code block instanceof EntityBlock} → 是机器 (机械动力所有带方块实体的方块都经 IBE 实现了它, 轨道也是);</li>
 *   <li>是机械动力 {@code IRotate} 的实例 → 是机器 (按类名找, 没装机械动力时跳过);</li>
 *   <li>其余 → 装饰, 放行。</li>
 * </ol>
 * 结果按 Block 身份缓存 (Block 不覆写 equals); 配置只在开服读, 缓存不用失效, 换一份设置就换一个实例。
 */
public final class CreateBlockPolicy {

    /** 机械动力"会转的方块"接口的类名。 */
    public static final String KINETIC_INTERFACE = "com.simibubi.create.content.kinetics.base.IRotate";

    private final GuardSettings settings;
    @Nullable
    private final Class<?> kineticInterface;
    private final Function<Block, ResourceLocation> keys;
    private final Map<Block, Boolean> cache = new ConcurrentHashMap<>();

    public CreateBlockPolicy(GuardSettings settings, @Nullable Class<?> kineticInterface) {
        this(settings, kineticInterface, CreateBlockPolicy::registryKey);
    }

    /**
     * 注册名另给 (GameTest: 开发运行时没有某些命名空间的方块, 例如 ignored_void, 用合成的注册名核对分类与放置禁令)。
     * 生产路径一律走上面的构造器, 按方块注册表取名。
     */
    public CreateBlockPolicy(GuardSettings settings, @Nullable Class<?> kineticInterface,
                             Function<Block, ResourceLocation> keys) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.kineticInterface = kineticInterface;
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    @Nullable
    private static ResourceLocation registryKey(Block block) {
        return ForgeRegistries.BLOCKS.getKey(block);
    }

    /** 按设置建一份, IRotate 按类名找 (没装机械动力时为 null)。 */
    public static CreateBlockPolicy of(GuardSettings settings) {
        return new CreateBlockPolicy(settings, findKineticInterface());
    }

    /** 按类名找 IRotate, 只加载不初始化; 找不到 (没装机械动力) 返回 null, 不抛异常。 */
    @Nullable
    public static Class<?> findKineticInterface() {
        try {
            return Class.forName(KINETIC_INTERFACE, false, CreateBlockPolicy.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    public GuardSettings settings() {
        return settings;
    }

    /** 找到了机械动力的"会转"接口。 */
    public boolean kineticInterfacePresent() {
        return kineticInterface != null;
    }

    /** 这个方块算不算机械动力的"机器" (22.4)。 */
    public boolean isMachine(Block block) {
        Boolean cached = cache.get(block);
        if (cached != null) {
            return cached;
        }
        boolean machine = classify(block);
        cache.put(block, machine);
        return machine;
    }

    /** 这个方块的命名空间在 namespaces 里 (用于 /district machines 与提示)。 */
    public boolean inNamespaces(Block block) {
        ResourceLocation id = keys.apply(block);
        return id != null && settings.namespaces().contains(id.getNamespace());
    }

    private boolean classify(Block block) {
        ResourceLocation id = keys.apply(block);
        if (id == null || !settings.namespaces().contains(id.getNamespace())) {
            return false;
        }
        if (settings.allowBlocks().contains(id)) {
            return false;
        }
        if (settings.denyBlocks().contains(id)) {
            return true;
        }
        if (block instanceof EntityBlock) {
            return true;
        }
        return kineticInterface != null && kineticInterface.isInstance(block);
    }
}
