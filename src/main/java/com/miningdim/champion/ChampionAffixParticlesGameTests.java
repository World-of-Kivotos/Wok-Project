package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 精英怪词条粒子映射 GameTest ({@link ChampionAffixParticles}): 35 词条全有非空签名粒子 + 关键映射主题正确
 * (燃烧->火 / 寒霜->雪 / 天雷->电火花 / 再生->心)。纯函数直驱, 与 ChampionGameTests 同 batch="champion"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionAffixParticlesGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyAffixHasNonNullParticle(GameTestHelper helper) {
        for (AffixDef def : AffixDef.values()) {
            helper.assertTrue(ChampionAffixParticles.ambientParticle(def) != null,
                    "affix " + def.name() + " must map to a non-null signature particle");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void keyAffixParticlesAreThematic(GameTestHelper helper) {
        helper.assertTrue(ChampionAffixParticles.ambientParticle(AffixDef.BURNING) == ParticleTypes.FLAME,
                "burning -> flame");
        helper.assertTrue(ChampionAffixParticles.ambientParticle(AffixDef.FROST) == ParticleTypes.SNOWFLAKE,
                "frost -> snowflake");
        helper.assertTrue(ChampionAffixParticles.ambientParticle(AffixDef.THUNDER) == ParticleTypes.ELECTRIC_SPARK,
                "thunder -> electric spark");
        helper.assertTrue(ChampionAffixParticles.ambientParticle(AffixDef.REGEN_TISSUE) == ParticleTypes.HEART,
                "regen tissue -> heart");
        helper.succeed();
    }
}
