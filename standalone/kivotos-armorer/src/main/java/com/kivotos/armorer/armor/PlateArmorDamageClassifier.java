package com.kivotos.armorer.armor;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;

/** 插板伤害白名单。TaCZ 四型先精确分类，再排除其他绕甲与非物理来源。 */
public final class PlateArmorDamageClassifier {

    public enum Kind {
        BALLISTIC_NORMAL,
        BALLISTIC_ARMOR_PIERCING,
        GENERAL_PHYSICAL,
        EXCLUDED
    }

    private static final String TACZ_NAMESPACE = "tacz";

    private PlateArmorDamageClassifier() {
    }

    public static Kind classify(DamageSource source) {
        ResourceLocation id = source.typeHolder().unwrapKey()
                .map(key -> key.location())
                .orElse(null);
        // TaCZ 命名空间必须在这里终止分类；未知新弹型保守排除，不能再因 projectile/explosion 标签漏进 G/T。
        if (id != null && TACZ_NAMESPACE.equals(id.getNamespace())) {
            return classifyTacz(id);
        }

        if (source.is(DamageTypeTags.BYPASSES_ARMOR)
                || source.is(DamageTypeTags.IS_FIRE)
                || source.is(DamageTypes.MAGIC)
                || source.is(DamageTypes.INDIRECT_MAGIC)
                || source.is(DamageTypes.WITHER)
                || source.is(DamageTypes.WITHER_SKULL)
                || source.is(DamageTypes.DRAGON_BREATH)
                || source.is(DamageTypes.THORNS)) {
            return Kind.EXCLUDED;
        }

        if (isLegacyChampionPhysical(id)
                || source.is(DamageTypes.MOB_ATTACK)
                || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)
                || source.is(DamageTypes.PLAYER_ATTACK)
                || source.is(DamageTypeTags.IS_PROJECTILE)
                || source.is(DamageTypeTags.IS_EXPLOSION)) {
            return Kind.GENERAL_PHYSICAL;
        }
        return Kind.EXCLUDED;
    }

    /** Optional compatibility with the parent project's physical champion AOE without linking its classes. */
    private static boolean isLegacyChampionPhysical(ResourceLocation id) {
        return id != null
                && "miningdim".equals(id.getNamespace())
                && "champion_skill_aoe".equals(id.getPath());
    }

    public static Kind classifyTacz(String namespace, String path) {
        if (namespace == null || path == null) {
            return Kind.EXCLUDED;
        }
        return classifyTacz(new ResourceLocation(namespace, path));
    }

    private static Kind classifyTacz(ResourceLocation id) {
        if (id == null || !TACZ_NAMESPACE.equals(id.getNamespace())) {
            return Kind.EXCLUDED;
        }
        return switch (id.getPath()) {
            case "bullet", "bullet_void" -> Kind.BALLISTIC_NORMAL;
            case "bullet_ignore_armor", "bullet_void_ignore_armor" -> Kind.BALLISTIC_ARMOR_PIERCING;
            default -> Kind.EXCLUDED;
        };
    }
}

