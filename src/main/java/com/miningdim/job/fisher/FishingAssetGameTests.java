package com.miningdim.job.fisher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.fisher.ore.OreFishType;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 渔业物品图标的资产契约测试。
 *
 * 缘由: runGameTestServer 是纯服务端进程, 从不拼图集, 所以图标尺寸再离谱也照样 "All N tests passed"。
 * 本模块首版提交的十张图标就是 1536x1024 与 1254x1254, 两条原版硬规则各踩一条 ——
 *
 * 1. 无 .mcmeta 时 {@code AnimationMetadataSection.calculateFrameSize} 取 {@code min(宽, 高)} 当帧尺寸,
 *    {@code SpriteLoader.loadSprite} 随后要求宽高都是帧尺寸的整数倍, 否则记
 *    "Image {} size {},{} is not multiple of frame size {},{}" 并返回 null —— 该物品在客户端直接是缺失贴图。
 *    1536x1024 推出的帧是 1024x1024, 1536 不是它的整数倍。
 * 2. 边长被 2 整除的次数决定整张方块/物品图集能开几级 mipmap
 *    ("Texture {} with size {}x{} limits mip level from {} to {}")。1254 只能整除一次, 会把全图集的 4 级
 *    mipmap 拉到 1 级 —— 受害的是全服所有远景贴图, 不只是这几条鱼。
 *
 * 判据取原版这两条规则本身, 不取 {@code build_ore_fish_icons.py} 里的 256 这个数, 这样换美术方案时
 * 测试仍然守得住底线。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FishingAssetGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "fishing_assets";

    /** 原版 {@code Minecraft} 默认 mipmap 等级, 也是本仓贴图必须保住的等级。 */
    private static final int REQUIRED_MIPMAP_LEVELS = 4;
    /** 本仓高精度物品图标的规格上限(塔罗牌与枪匠蓝图即为此值); 再大只是白占图集与 JAR。 */
    private static final int MAX_ICON_EDGE = 256;

    private FishingAssetGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyFishingIconSurvivesVanillaAtlasStitching(GameTestHelper helper) {
        for (String icon : iconNames()) {
            String texturePath = "/assets/miningdim/textures/item/fishing/" + icon + ".png";
            BufferedImage image = loadImage(texturePath);
            int width = image.getWidth();
            int height = image.getHeight();

            // 规则 1: 无 mcmeta 时帧尺寸 = min(宽, 高), 宽高必须都是它的整数倍。
            int frame = Math.min(width, height);
            helper.assertTrue(width % frame == 0 && height % frame == 0,
                    icon + " 尺寸 " + width + "x" + height + " 不是帧尺寸 " + frame + " 的整数倍; "
                            + "SpriteLoader 会拒绝拼图并让该物品退化成缺失贴图");

            // 规则 2: 边长被 2 整除的次数必须够开满 mipmap, 否则拖垮整张图集。
            helper.assertTrue(halvings(width) >= REQUIRED_MIPMAP_LEVELS
                            && halvings(height) >= REQUIRED_MIPMAP_LEVELS,
                    icon + " 尺寸 " + width + "x" + height + " 只能被 2 整除 "
                            + Math.min(halvings(width), halvings(height)) + " 次, 不足 "
                            + REQUIRED_MIPMAP_LEVELS + " 级; 会把整张物品图集的 mipmap 拉低, 全服远景贴图跟着糊");

            helper.assertTrue(width <= MAX_ICON_EDGE && height <= MAX_ICON_EDGE,
                    icon + " 尺寸 " + width + "x" + height + " 超过本仓物品图标上限 " + MAX_ICON_EDGE);

            helper.assertTrue(image.getColorModel().hasAlpha(),
                    icon + " 必须带 Alpha 通道, 物品图标不能有不透明底板");
            // 图标主体按长边铺满 256 是刻意的(物品栏里才占得满格), 所以不查四边留白, 只查背景确实被抠掉了:
            // image_gen 出的原画带过不透明底板时, 物品栏里会是一块方形色板而不是一条鱼。
            helper.assertTrue(transparentPixels(image) > 0,
                    icon + " 整张没有一个全透明像素, 背景没抠干净");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyFishingItemModelBindsItsOwnIcon(GameTestHelper helper) {
        for (String icon : iconNames()) {
            JsonObject model = loadJson("/assets/miningdim/models/item/" + icon + ".json");
            helper.assertTrue("minecraft:item/generated".equals(model.get("parent").getAsString()),
                    icon + " 物品模型必须继承 item/generated");
            String layer0 = model.getAsJsonObject("textures").get("layer0").getAsString();
            helper.assertTrue(("miningdim:item/fishing/" + icon).equals(layer0),
                    icon + " 物品模型绑定的贴图是 " + layer0 + ", 与同名图标对不上");
        }
        helper.succeed();
    }

    /** 十张图标 = 五种矿石鱼 + 对应五种鱼羹, 名字由 {@link OreFishType} 推导, 加鱼种时测试自动覆盖。 */
    private static List<String> iconNames() {
        List<String> names = new ArrayList<>();
        for (OreFishType type : OreFishType.values()) {
            names.add(type.fishId());
            names.add(type.soupId());
        }
        return names;
    }

    /** 该边长能被 2 整除多少次。 */
    private static int halvings(int edge) {
        int count = 0;
        while (edge > 0 && edge % 2 == 0) {
            edge /= 2;
            count++;
        }
        return count;
    }

    private static int transparentPixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) == 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static BufferedImage loadImage(String path) {
        try (InputStream input = FishingAssetGameTests.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("找不到渔业图片资源: " + path);
            }
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IllegalStateException("无法解码渔业图片资源: " + path);
            }
            return image;
        } catch (IOException exception) {
            throw new IllegalStateException("读取渔业图片资源失败: " + path, exception);
        }
    }

    private static JsonObject loadJson(String path) {
        try (InputStream input = FishingAssetGameTests.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("找不到渔业资产: " + path);
            }
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException exception) {
            throw new IllegalStateException("读取渔业资产失败: " + path, exception);
        }
    }
}
