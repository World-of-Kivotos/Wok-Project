package com.miningdim.title;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 当前生效的称号定义集合。{@link TitleDefinitionLoader} 在每次数据包 (重新) 加载时整表替换;
 * 读取方只拿到不可变快照。
 *
 * 用实例而不是全局静态表: GameTest 可以各建一份互不干扰的定义集合, 不必改写服务端真正生效的那一份。
 * {@link #generation()} 每次替换自增, {@link TitleRenderer} 据此判定前缀缓存是否过期。
 */
public final class TitleDefinitions {

    /** 列表排序: sort 大者在前 (档位越高越靠前), 同分按 id 字典序, 保证每次输出稳定。 */
    private static final Comparator<TitleDefinition> DISPLAY_ORDER = Comparator
            .comparingInt(TitleDefinition::sort).reversed()
            .thenComparing(definition -> definition.id().toString());

    private volatile Map<ResourceLocation, TitleDefinition> byId = Map.of();
    private volatile int generation;

    /** 整表替换 (数据包加载线程即服务端主线程调用)。 */
    public void install(Map<ResourceLocation, TitleDefinition> loaded) {
        this.byId = Collections.unmodifiableMap(new LinkedHashMap<>(loaded));
        this.generation++;
    }

    /** 服务端停止时清空, 防跨存档沿用上一个世界的定义。 */
    public void clear() {
        install(Map.of());
    }

    public Optional<TitleDefinition> get(ResourceLocation id) {
        return Optional.ofNullable(byId.get(id));
    }

    public boolean contains(ResourceLocation id) {
        return byId.containsKey(id);
    }

    /** 全部定义, 按展示顺序排好。 */
    public List<TitleDefinition> all() {
        List<TitleDefinition> sorted = new ArrayList<>(byId.values());
        sorted.sort(DISPLAY_ORDER);
        return Collections.unmodifiableList(sorted);
    }

    public int size() {
        return byId.size();
    }

    /** 替换次数; 只用于缓存失效判定。 */
    public int generation() {
        return generation;
    }
}
