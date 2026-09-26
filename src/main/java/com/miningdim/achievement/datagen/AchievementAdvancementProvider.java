package com.miningdim.achievement.datagen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miningdim.achievement.AchievementIds;
import com.miningdim.achievement.meta.AchievementMetaLoader;
import com.miningdim.achievement.tier.AchievementTier;
import com.miningdim.core.MiningConstants;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.advancements.FrameType;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.common.crafting.conditions.ModLoadedCondition;
import net.minecraftforge.common.data.ExistingFileHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 成就的数据生成器 (Achievement_System_DesignSpec 4.1): 从 {@link AchievementDeclarations} 这一份 Java 声明同时生成
 * {@code data/miningdim/advancements/**.json} 与 {@code data/miningdim/achievement_meta/**.json}, 进度的框体、公告开关
 * 与标题颜色都由档位推导, 不手写。输出按仓库惯例提交到 {@code src/generated/resources/}, 改声明后必须重跑 runData。
 *
 * <p>没有沿用 Forge 的 {@code ForgeAdvancementProvider}: 它的回调只收 {@link Advancement} 对象、由它自己序列化,
 * 没有地方挂加载条件, 而依赖 TaCZ 的成就必须带 {@code forge:mod_loaded} (6.2); 元数据也要与进度出自同一份声明。
 * 所以这里是一个普通的 {@link DataProvider}, 进度 JSON 仍由原版 {@code Advancement.Builder#serializeToJson} 生成,
 * 带条件的在顶层加一个 {@code conditions} 数组, 即 Forge 加载进度时 {@code ConditionalAdvancement.processConditional}
 * 读取的格式。
 *
 * <p>渐变档 (白金起) 的标题要在生成时逐字上色, 文字取自模组自带的 zh_cn 语言表 (中文文案的唯一真源, 这里不另抄一份);
 * 语言表里缺键时生成直接失败。
 */
public final class AchievementAdvancementProvider implements DataProvider {

    private static final ResourceLocation ZH_CN = new ResourceLocation(MiningConstants.MODID, "lang/zh_cn.json");

    private final PackOutput.PathProvider advancementPaths;
    private final PackOutput.PathProvider metaPaths;
    private final ExistingFileHelper existingFiles;

    public AchievementAdvancementProvider(PackOutput output, ExistingFileHelper existingFiles) {
        this.advancementPaths = output.createPathProvider(PackOutput.Target.DATA_PACK, "advancements");
        this.metaPaths = output.createPathProvider(PackOutput.Target.DATA_PACK, AchievementMetaLoader.DIRECTORY);
        this.existingFiles = existingFiles;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        JsonObject zhCn = readZhCn();
        List<AchievementDeclaration> declarations = AchievementDeclarations.p1();
        validate(declarations);
        List<CompletableFuture<?>> writes = new ArrayList<>();
        for (AchievementDeclaration declaration : declarations) {
            writes.add(DataProvider.saveStable(cache, advancementJson(declaration, zhCn),
                    advancementPaths.json(declaration.id())));
            writes.add(DataProvider.saveStable(cache, declaration.meta().toJson(), metaPaths.json(declaration.id())));
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    @Override
    public String getName() {
        return "Achievement advancements and meta";
    }

    /**
     * 一个进度的 JSON。{@code sends_telemetry_event} 取 false: 这是服务端模组的成就, 不该让客户端为它向 Mojang
     * 发遥测, 也与手写数据包进度缺省该字段时的取值一致 (原版 {@code Advancement.Builder.advancement()} 默认 true,
     * 是给原版自己的进度用的; 唯一取 false 的工厂方法是配方进度专用的, 这里不借用它)。
     */
    private static JsonElement advancementJson(AchievementDeclaration declaration, JsonObject zhCn) {
        Advancement.Builder builder = Advancement.Builder.advancement();
        if (declaration.parent() != null) {
            builder.parent(declaration.parent());
        }
        builder.display(display(declaration, zhCn));
        declaration.criteria().forEach(builder::addCriterion);
        JsonObject json = builder.serializeToJson();
        json.addProperty("sends_telemetry_event", false);
        if (declaration.requiredMod() != null) {
            JsonArray conditions = new JsonArray();
            conditions.add(CraftingHelper.serialize(new ModLoadedCondition(declaration.requiredMod())));
            json.add("conditions", conditions);
        }
        return json;
    }

    /**
     * 显示信息。页签根: 不公告、不发 Toast、带背景 (第五章)。成就: 框体与公告开关取自档位 (隐藏成就一律公告),
     * 标题带档位颜色, 说明保留 translate。
     */
    private static DisplayInfo display(AchievementDeclaration declaration, JsonObject zhCn) {
        ItemStack icon = new ItemStack(ForgeRegistries.ITEMS.getValue(declaration.icon()));
        Component description = Component.translatable(declaration.descriptionKey());
        AchievementTier tier = declaration.tier();
        if (tier == null) {
            return new DisplayInfo(icon, Component.translatable(declaration.titleKey()), description,
                    declaration.background(), FrameType.TASK, false, false, false);
        }
        String resolved = tier.isGradient() ? requireText(zhCn, declaration.titleKey()) : declaration.titleKey();
        return new DisplayInfo(icon, tier.title(declaration.titleKey(), resolved), description, null, tier.frame(),
                true, tier.announcesToChat(declaration.hidden()), declaration.hidden());
    }

    /**
     * 声明表的结构约束: 六个页签各有且只有一个根 (第五章)、每条成就都在某个页签下、id 唯一、父进度先于子进度声明、
     * 有条件、图标与背景各就各位、依赖可选模组的父链一致。
     */
    private static void validate(List<AchievementDeclaration> declarations) {
        Set<ResourceLocation> expectedRoots = new HashSet<>();
        AchievementIds.TABS.forEach(tab -> expectedRoots.add(AchievementIds.root(tab)));
        Set<ResourceLocation> declared = new HashSet<>();
        Set<ResourceLocation> roots = new HashSet<>();
        Set<ResourceLocation> modGated = new HashSet<>();
        for (AchievementDeclaration declaration : declarations) {
            ResourceLocation id = declaration.id();
            if (!declared.add(id)) {
                throw new IllegalStateException("achievement " + id + " is declared twice");
            }
            String tab = id.getPath().substring(0, Math.max(0, id.getPath().indexOf('/')));
            if (!AchievementIds.TABS.contains(tab) || !AchievementIds.isAchievement(id)) {
                throw new IllegalStateException("achievement " + id + " is not under one of the tabs "
                        + AchievementIds.TABS);
            }
            if (declaration.isRoot()) {
                roots.add(id);
            }
            if (declaration.criteria().isEmpty()) {
                throw new IllegalStateException("achievement " + id + " has no criteria");
            }
            if (declaration.isRoot() != (declaration.parent() == null)
                    || declaration.isRoot() != (declaration.background() != null)) {
                throw new IllegalStateException(id + ": exactly the tab roots have no parent and carry a background");
            }
            if (declaration.parent() != null && !declared.contains(declaration.parent())) {
                throw new IllegalStateException(id + ": parent " + declaration.parent() + " must be declared first");
            }
            if (!ForgeRegistries.ITEMS.containsKey(declaration.icon())) {
                throw new IllegalStateException(id + ": icon " + declaration.icon() + " is not a registered item");
            }
            if (declaration.requiredMod() != null) {
                modGated.add(id);
            } else if (declaration.parent() != null && modGated.contains(declaration.parent())) {
                // 父进度因加载条件缺席时, 原版会连带丢掉子进度; 子进度必须声明同一条件, 不能指望它照常出现。
                throw new IllegalStateException(id + ": parent " + declaration.parent()
                        + " depends on an optional mod, so the child must declare it too");
            }
        }
        if (!roots.equals(expectedRoots)) {
            throw new IllegalStateException("tab roots " + roots + " do not match the tabs " + AchievementIds.TABS);
        }
    }

    private JsonObject readZhCn() {
        try (Reader reader = new InputStreamReader(
                existingFiles.getResource(ZH_CN, PackType.CLIENT_RESOURCES).open(), StandardCharsets.UTF_8)) {
            return GsonHelper.parse(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("achievement datagen cannot read the bundled zh_cn language file "
                    + "(run data generation with --existing src/main/resources)", exception);
        }
    }

    private static String requireText(JsonObject language, String key) {
        if (!language.has(key) || !language.get(key).isJsonPrimitive() || language.get(key).getAsString().isBlank()) {
            throw new IllegalStateException("zh_cn language file has no text for " + key
                    + "; gradient-tier titles are rendered from it at data generation time");
        }
        return language.get(key).getAsString();
    }
}
