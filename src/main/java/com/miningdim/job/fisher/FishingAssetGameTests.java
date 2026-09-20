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
 * 判据取原版这两条规则本身, 不取 {@code build_ore_fish_icons.py} 里的 64 这个数, 这样换美术方案时
 * 测试仍然守得住底线。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FishingAssetGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "fishing_assets";

    /** 原版 {@code Minecraft} 默认 mipmap 等级, 也是本仓贴图必须保住的等级。 */
    private static final int REQUIRED_MIPMAP_LEVELS = 4;
    /**
     * 绝不许越过的硬上限(与塔罗牌、枪匠蓝图同档), 不是本模块的目标规格 —— 本模块实际派生 64x64,
     * 由 {@code tools/fishing/build_ore_fish_icons.py} 的 ICON_SIZE 决定, 真正卡住尺寸回退的是下面那条
     * element 红线。
     */
    private static final int MAX_ICON_EDGE = 256;
    /**
     * 单张图标允许 {@code ItemModelGenerator} 烘出的 BlockElement 上限。
     *
     * {@code item/generated} 不是贴一张平面图: 原版沿贴图的 Alpha 轮廓扫四个方向, 每个
     * (方向, 锚) 组合生成一个 element 的侧面几何 —— 上下方向按像素行取锚、左右方向按像素列取锚,
     * 同一行/列上再碎的轮廓也只合成一个 span ({@code createOrExpandSpan} 只看 facing 与 anchor,
     * 不要求连续)。正反两面合起来只算一个 element(同一个 BlockElement 上的 SOUTH/NORTH 两个 face),
     * 所以单张上限是 {@code 1 + 2 x 宽 + 2 x 高}, 代价同时随"图多大"和"轮廓占了多少行列"涨。
     *
     * 实测同一条暗金鱼: 本模块最初提交的 1254x1254 原画是 3473 个 element, 256x256 是 372,
     * 64x64 是 134; 若不清掉缩放留下的 Alpha 碎屑, 64x64 会涨到 220。十张最初合计 17729 个 element,
     * 现在 949。物品栏里摆满一箱这种图标时多画的面是实打实的掉帧, 而服务端 GameTest 永远看不到。
     *
     * 200 这条红线的挑法: 当前十张最大 134, 64x64 的理论上限是 257, 取 200 既留得下美术微调的余量,
     * 又刚好卡住两种最可能的回退 —— 去掉 Alpha 清理(220)或把尺寸退回 256x256(372)。
     * 本仓既有的塔罗牌/枪匠蓝图虽然也是 256x256, 但轮廓是规整矩形, 只有 38-44 个 element,
     * 不能拿来给不规则轮廓的图标背书。
     */
    private static final int MAX_GENERATED_ELEMENTS = 200;

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
            // 图标主体按长边铺满 64 是刻意的(物品栏里才占得满格), 所以不查四边留白, 只查背景确实被抠掉了:
            // image_gen 出的原画带过不透明底板时, 物品栏里会是一块方形色板而不是一条鱼。
            helper.assertTrue(transparentPixels(image) > 0,
                    icon + " 整张没有一个全透明像素, 背景没抠干净");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyFishingIconStaysCheapForItemModelGeneration(GameTestHelper helper) {
        for (String icon : iconNames()) {
            BufferedImage image = loadImage("/assets/miningdim/textures/item/fishing/" + icon + ".png");
            int elements = generatedElementCount(image);
            helper.assertTrue(elements <= MAX_GENERATED_ELEMENTS,
                    icon + " 的 Alpha 轮廓会让 ItemModelGenerator 烘出 " + elements + " 个 element, 超过红线 "
                            + MAX_GENERATED_ELEMENTS + "; 降分辨率或清掉缩放留下的 Alpha 碎屑");
        }
        helper.succeed();
    }

    /**
     * 按原版 {@code ItemModelGenerator} 的规则数 element: 正反两面合成的那一个记 1(processFrames 的字节码里
     * BlockElement 只 new 了一次, SOUTH/NORTH 是它的两个 BlockElementFace), 再加每一个真正产生了轮廓的
     * (方向, 锚) 组合一个(createSideElements 对每个 span 各 new 一个 BlockElement)。四个方向的邻居偏移取自原版 {@code SpanFacing}: UP(0,-1)、DOWN(0,+1)、
     * LEFT(-1,0)、RIGHT(+1,0); 上下按像素行取锚、左右按像素列取锚, 与 {@code createOrExpandSpan}
     * "同 facing 同 anchor 就合并, 不要求连续"的写法一致。判据取原版算法本身, 不取生成脚本里的任何参数。
     *
     * 透明的判据是 {@code alpha == 0} —— 原版 {@code SpriteContents.isTransparent} 就是这么判的,
     * 所以缩放留下的 alpha 只有个位数的碎屑会被当成实体轮廓, 这正是要守的东西。
     */
    private static int generatedElementCount(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        boolean[] upRows = new boolean[height];
        boolean[] downRows = new boolean[height];
        boolean[] leftColumns = new boolean[width];
        boolean[] rightColumns = new boolean[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!opaque(image, x, y)) {
                    continue;
                }
                if (!opaque(image, x, y - 1)) {
                    upRows[y] = true;
                }
                if (!opaque(image, x, y + 1)) {
                    downRows[y] = true;
                }
                if (!opaque(image, x - 1, y)) {
                    leftColumns[x] = true;
                }
                if (!opaque(image, x + 1, y)) {
                    rightColumns[x] = true;
                }
            }
        }
        int spans = count(upRows) + count(downRows) + count(leftColumns) + count(rightColumns);
        return spans + 1;
    }

    private static int count(boolean[] flags) {
        int total = 0;
        for (boolean flag : flags) {
            if (flag) {
                total++;
            }
        }
        return total;
    }

    private static boolean opaque(BufferedImage image, int x, int y) {
        if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) {
            return false;
        }
        return ((image.getRGB(x, y) >>> 24) & 0xFF) != 0;
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
