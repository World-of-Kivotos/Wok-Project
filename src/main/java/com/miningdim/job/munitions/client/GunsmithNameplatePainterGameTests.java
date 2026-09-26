package com.miningdim.job.munitions.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.client.GunsmithNameplatePainter.Effect;
import com.miningdim.job.munitions.gunsmith.GunsmithPartRarity;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.EnumSet;
import java.util.Set;

/**
 * 稀有度名牌像素画的阶梯与可读性不变量。{@link GunsmithNameplatePainter} 不引用客户端类，
 * 因此可以在 GameTest 服务端直接逐帧绘制并检查像素。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GunsmithNameplatePainterGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "gunsmith_nameplate";
    /** 粗体“原型级”与粗体“Prototype”的文字宽度，覆盖中英文两种标签长度。 */
    private static final int[] LABEL_WIDTHS = {29, 59};
    /** 各档特效数量：制式级 1、改装级 3、特种级 5、尖端级 8、原型级 12。 */
    private static final int[] EFFECTS_PER_RANK = {1, 3, 5, 8, 12};

    private GunsmithNameplatePainterGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyRarityTierAddsItsOwnNameplateEffects(GameTestHelper helper) {
        Set<Effect> lower = EnumSet.noneOf(Effect.class);
        for (int rank = 1; rank <= 5; rank++) {
            GunsmithPartRarity rarity = rarityOfRank(rank);
            Set<Effect> effects = GunsmithNameplatePainter.effects(rarity);
            helper.assertTrue(effects.size() == EFFECTS_PER_RANK[rank - 1],
                    rarity.id() + " nameplate must carry " + EFFECTS_PER_RANK[rank - 1]
                            + " effects, found " + effects.size());
            helper.assertTrue(effects.containsAll(lower) && effects.size() > lower.size(),
                    rarity.id() + " nameplate must keep every lower-tier effect and add its own");
            lower = effects;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nameplateEffectsNeverBlurTheRankPips(GameTestHelper helper) {
        int height = GunsmithNameplatePainter.imageHeight();
        for (GunsmithPartRarity rarity : GunsmithPartRarity.values()) {
            int rank = GunsmithNameplatePainter.rank(rarity);
            for (int labelWidth : LABEL_WIDTHS) {
                int width = GunsmithNameplatePainter.imageWidth(labelWidth);
                int[] pixels = new int[width * height];
                for (long t = -1L; t <= 7000L; t += t < 0L ? 1L : 20L) {
                    GunsmithNameplatePainter.paint(rarity, labelWidth, t, pixels);
                    double minLit = 1.0D;
                    double maxUnlit = 0.0D;
                    for (int pip = 0; pip < GunsmithNameplatePainter.PIP_COUNT; pip++) {
                        for (int column = 0; column < GunsmithNameplatePainter.PIP_WIDTH; column++) {
                            for (int y = GunsmithNameplatePainter.PIP_TOP;
                                 y <= GunsmithNameplatePainter.PIP_BOTTOM; y++) {
                                int x = GunsmithNameplatePainter.pipPixelX(pip, column, y);
                                double l = luminance(pixels[(y + GunsmithNameplatePainter.MARGIN) * width + x]);
                                if (pip < rank) {
                                    minLit = Math.min(minLit, l);
                                } else {
                                    maxUnlit = Math.max(maxUnlit, l);
                                }
                            }
                        }
                    }
                    String where = rarity.id() + " label=" + labelWidth + " t=" + t;
                    helper.assertTrue(maxUnlit <= 0.30D,
                            "unlit rank pips must stay dark under every effect: " + where + " max=" + maxUnlit);
                    // 原型级的错位帧会整行横移，等级格短暂离位属于设计内的故障效果
                    if (t < 0L || !GunsmithNameplatePainter.isGlitchFrame(t)
                            || !GunsmithNameplatePainter.has(rarity, Effect.GLITCH)) {
                        helper.assertTrue(minLit >= 0.60D,
                                "lit rank pips must stay bright under every effect: " + where + " min=" + minLit);
                    }
                }
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nameplateBackdropKeepsLabelReadable(GameTestHelper helper) {
        int height = GunsmithNameplatePainter.imageHeight();
        int margin = GunsmithNameplatePainter.MARGIN;
        for (GunsmithPartRarity rarity : GunsmithPartRarity.values()) {
            for (int labelWidth : LABEL_WIDTHS) {
                int width = GunsmithNameplatePainter.imageWidth(labelWidth);
                int[] pixels = new int[width * height];
                GunsmithNameplatePainter.paint(rarity, labelWidth, -1L, pixels);
                double brightest = 0.0D;
                // 文字 8 行加 1 行阴影
                for (int y = GunsmithNameplatePainter.LABEL_Y; y <= GunsmithNameplatePainter.LABEL_Y + 8; y++) {
                    for (int x = GunsmithNameplatePainter.LABEL_X;
                         x < GunsmithNameplatePainter.LABEL_X + labelWidth; x++) {
                        int argb = pixels[(y + margin) * width + x + margin];
                        helper.assertTrue((argb >>> 24) == 0xFF,
                                rarity.id() + " label backdrop must be opaque at " + x + "," + y);
                        brightest = Math.max(brightest, luminance(argb));
                    }
                }
                helper.assertTrue(brightest <= 0.30D, rarity.id() + " label backdrop must stay dark enough for "
                        + "the light label text, brightest=" + brightest);
                double text = luminance(0xFF000000 | GunsmithNameplatePainter.labelColor(rarity));
                helper.assertTrue(text >= 0.80D, rarity.id() + " label color must stay light, was " + text);
            }
        }
        helper.succeed();
    }

    private static GunsmithPartRarity rarityOfRank(int rank) {
        for (GunsmithPartRarity rarity : GunsmithPartRarity.values()) {
            if (GunsmithNameplatePainter.rank(rarity) == rank) {
                return rarity;
            }
        }
        throw new IllegalArgumentException("no rarity with rank " + rank);
    }

    private static double luminance(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (0.2126D * r + 0.7152D * g + 0.0722D * b) / 255.0D;
    }
}
