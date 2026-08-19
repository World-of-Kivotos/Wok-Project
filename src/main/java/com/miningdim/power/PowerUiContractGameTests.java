package com.miningdim.power;

import com.miningdim.core.MiningConstants;
import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.power.endgame.LowTemperatureControllerMenu;
import com.miningdim.power.generator.GeneratorMenu;
import com.miningdim.power.generator.PreheatGeneratorMenu;
import com.miningdim.power.machine.AirSeparationMenu;
import com.miningdim.power.machine.MetallurgicPurifierMenu;
import com.miningdim.power.storage.PowerCellMenu;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Power 容器的 GUI atlas 与 Menu 槽位坐标契约。测试只读取服务端安全的 Menu 和打包资源，避免在
 * 专用服务器 GameTest 注册阶段加载任何客户端 Screen 类。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PowerUiContractGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "power_ui_contract";
    private static final String GUI_ROOT = "/assets/miningdim/textures/gui/power/";
    private static final int ATLAS_SIZE = 256;
    private static final int STANDARD_WIDTH = 218;
    private static final int STANDARD_HEIGHT = 222;
    private static final int CONTROLLER_HEIGHT = 176;
    private static final int STANDARD_PLAYER_X = 28;
    private static final int STANDARD_PLAYER_Y = 142;
    private static final int STANDARD_HOTBAR_Y = 200;
    private static final int CONTROLLER_PLAYER_Y = 94;
    private static final int CONTROLLER_HOTBAR_Y = 152;
    private static final int NEAR_WHITE_CHANNEL_MIN = 224;
    private static final int SLOT_FRAME_COLOR = 0xFF4D606F;
    private static final BlockPos MENU_BLOCK_POS = new BlockPos(3, 1, 3);
    private static final List<AtlasSpec> ATLASES = List.of(
            new AtlasSpec("generator.png", STANDARD_WIDTH, STANDARD_HEIGHT, List.of(
                    new SlotPoint(69, 37), new SlotPoint(133, 37),
                    new SlotPoint(28, 142), new SlotPoint(172, 178),
                    new SlotPoint(28, 200), new SlotPoint(172, 200))),
            new AtlasSpec("preheat_generator.png", STANDARD_WIDTH, STANDARD_HEIGHT, List.of(
                    new SlotPoint(101, 36), new SlotPoint(28, 142), new SlotPoint(172, 178),
                    new SlotPoint(28, 200), new SlotPoint(172, 200))),
            new AtlasSpec("power_cell.png", STANDARD_WIDTH, STANDARD_HEIGHT, List.of(
                    new SlotPoint(28, 142), new SlotPoint(172, 178),
                    new SlotPoint(28, 200), new SlotPoint(172, 200))),
            new AtlasSpec("metallurgic_purifier.png", STANDARD_WIDTH, STANDARD_HEIGHT, List.of(
                    new SlotPoint(51, 36), new SlotPoint(101, 36), new SlotPoint(151, 36),
                    new SlotPoint(28, 142), new SlotPoint(172, 178),
                    new SlotPoint(28, 200), new SlotPoint(172, 200))),
            new AtlasSpec("air_separation.png", STANDARD_WIDTH, STANDARD_HEIGHT, List.of(
                    new SlotPoint(101, 70), new SlotPoint(28, 142), new SlotPoint(172, 178),
                    new SlotPoint(28, 200), new SlotPoint(172, 200))),
            new AtlasSpec("low_temperature_controller.png", STANDARD_WIDTH, CONTROLLER_HEIGHT, List.of(
                    new SlotPoint(101, 35), new SlotPoint(28, 94), new SlotPoint(172, 130),
                    new SlotPoint(28, 152), new SlotPoint(172, 152))));

    private PowerUiContractGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void atlasesKeepExactPngAndLogicalBounds(GameTestHelper helper) {
        for (AtlasSpec spec : ATLASES) {
            String path = GUI_ROOT + spec.fileName();
            byte[] encoded = loadResource(path);
            assertRgbaPngHeader(helper, path, encoded);
            assertNoTextureMetadata(helper, path);
            BufferedImage image = decodeImage(path, encoded);
            helper.assertTrue(image.getWidth() == ATLAS_SIZE && image.getHeight() == ATLAS_SIZE,
                    path + " 物理 atlas 必须为 256x256，实得 "
                            + image.getWidth() + "x" + image.getHeight());
            helper.assertTrue(image.getColorModel().hasAlpha()
                            && image.getColorModel().getNumColorComponents() == 3
                            && image.getColorModel().getNumComponents() == 4,
                    path + " 必须解码为含独立 alpha 的 RGBA 图像");
            assertLogicalBounds(helper, path, image, spec.logicalWidth(), spec.logicalHeight());
            assertSlotFramePixels(helper, path, image, spec.slotFrames());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void menusKeepAtlasAlignedSlotCoordinates(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        Inventory inventory = player.getInventory();
        BlockPos absolute = helper.absolutePos(MENU_BLOCK_POS);

        helper.setBlock(MENU_BLOCK_POS, PowerRegistry.INDUSTRIAL_GENERATOR.get());
        GeneratorMenu generator = new GeneratorMenu(0, inventory, absolute);
        assertMachineSlots(helper, "generator", generator,
                new SlotPoint(69, 37), new SlotPoint(133, 37));
        assertPlayerInventory(helper, "generator", generator, inventory, 2,
                STANDARD_PLAYER_X, STANDARD_PLAYER_Y, STANDARD_HOTBAR_Y);
        generator.removed(player);

        helper.setBlock(MENU_BLOCK_POS, PowerRegistry.COAL_GENERATOR.get());
        PreheatGeneratorMenu preheatGenerator = new PreheatGeneratorMenu(1, inventory, absolute);
        assertMachineSlots(helper, "preheat_generator", preheatGenerator, new SlotPoint(101, 36));
        assertPlayerInventory(helper, "preheat_generator", preheatGenerator, inventory, 1,
                STANDARD_PLAYER_X, STANDARD_PLAYER_Y, STANDARD_HOTBAR_Y);
        preheatGenerator.removed(player);

        helper.setBlock(MENU_BLOCK_POS, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        PowerCellMenu powerCell = new PowerCellMenu(2, inventory, absolute);
        assertMachineSlots(helper, "power_cell", powerCell);
        assertPlayerInventory(helper, "power_cell", powerCell, inventory, 0,
                STANDARD_PLAYER_X, STANDARD_PLAYER_Y, STANDARD_HOTBAR_Y);
        powerCell.removed(player);

        helper.setBlock(MENU_BLOCK_POS, PowerMachineRegistry.PURIFIER_BLOCK.get());
        MetallurgicPurifierMenu purifier = new MetallurgicPurifierMenu(3, inventory, absolute);
        assertMachineSlots(helper, "metallurgic_purifier", purifier,
                new SlotPoint(51, 36), new SlotPoint(101, 36), new SlotPoint(151, 36));
        assertPlayerInventory(helper, "metallurgic_purifier", purifier, inventory, 3,
                STANDARD_PLAYER_X, STANDARD_PLAYER_Y, STANDARD_HOTBAR_Y);
        purifier.removed(player);

        helper.setBlock(MENU_BLOCK_POS, PowerMachineRegistry.AIR_SEPARATOR_BLOCK.get());
        AirSeparationMenu airSeparator = new AirSeparationMenu(4, inventory, absolute);
        assertMachineSlots(helper, "air_separation", airSeparator, new SlotPoint(101, 70));
        assertPlayerInventory(helper, "air_separation", airSeparator, inventory, 1,
                STANDARD_PLAYER_X, STANDARD_PLAYER_Y, STANDARD_HOTBAR_Y);
        airSeparator.removed(player);

        helper.setBlock(MENU_BLOCK_POS, PowerRegistry.LOW_TEMPERATURE_CONTROLLER.get());
        LowTemperatureControllerMenu controller = new LowTemperatureControllerMenu(5, inventory, absolute);
        assertMachineSlots(helper, "low_temperature_controller", controller, new SlotPoint(101, 35));
        assertPlayerInventory(helper, "low_temperature_controller", controller, inventory, 1,
                STANDARD_PLAYER_X, CONTROLLER_PLAYER_Y, CONTROLLER_HOTBAR_Y);
        controller.removed(player);

        helper.setBlock(MENU_BLOCK_POS, Blocks.AIR);
        helper.succeed();
    }

    private static void assertRgbaPngHeader(GameTestHelper helper, String path, byte[] encoded) {
        byte[] signature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        helper.assertTrue(encoded.length >= 33
                        && Arrays.equals(signature, Arrays.copyOfRange(encoded, 0, signature.length)),
                path + " 必须具有有效 PNG 签名与完整 IHDR");
        helper.assertTrue(readInt(encoded, 8) == 13
                        && encoded[12] == 'I' && encoded[13] == 'H'
                        && encoded[14] == 'D' && encoded[15] == 'R',
                path + " 的首个 PNG chunk 必须是长度 13 的 IHDR");
        helper.assertTrue(readInt(encoded, 16) == ATLAS_SIZE && readInt(encoded, 20) == ATLAS_SIZE,
                path + " 的 IHDR 必须声明 256x256");
        helper.assertTrue(Byte.toUnsignedInt(encoded[24]) == 8 && Byte.toUnsignedInt(encoded[25]) == 6,
                path + " 必须使用 8 bit RGBA PNG 编码，IHDR bitDepth/colorType 实得 "
                        + Byte.toUnsignedInt(encoded[24]) + "/" + Byte.toUnsignedInt(encoded[25]));
        helper.assertTrue(encoded[26] == 0 && encoded[27] == 0,
                path + " 必须使用 PNG 标准压缩与过滤方法");
    }

    private static void assertLogicalBounds(GameTestHelper helper, String path, BufferedImage image,
                                            int logicalWidth, int logicalHeight) {
        int outsideVisible = 0;
        int semiTransparent = 0;
        int transparentWithRgb = 0;
        int nearWhitePerimeter = 0;
        boolean reachesRightEdge = false;
        boolean reachesBottomEdge = false;
        for (int y = 0; y < ATLAS_SIZE; y++) {
            for (int x = 0; x < ATLAS_SIZE; x++) {
                int argb = image.getRGB(x, y);
                int alpha = argb >>> 24;
                if (alpha != 0 && alpha != 255) {
                    semiTransparent++;
                }
                if (alpha == 0 && (argb & 0x00FFFFFF) != 0) {
                    transparentWithRgb++;
                }
                boolean logicalPerimeter = x < logicalWidth && y < logicalHeight
                        && (x == 0 || x == logicalWidth - 1 || y == 0 || y == logicalHeight - 1);
                if (logicalPerimeter && alpha != 0
                        && ((argb >>> 16) & 0xFF) >= NEAR_WHITE_CHANNEL_MIN
                        && ((argb >>> 8) & 0xFF) >= NEAR_WHITE_CHANNEL_MIN
                        && (argb & 0xFF) >= NEAR_WHITE_CHANNEL_MIN) {
                    nearWhitePerimeter++;
                }
                if ((x >= logicalWidth || y >= logicalHeight) && alpha != 0) {
                    outsideVisible++;
                }
                if (x == logicalWidth - 1 && y < logicalHeight && alpha != 0) {
                    reachesRightEdge = true;
                }
                if (y == logicalHeight - 1 && x < logicalWidth && alpha != 0) {
                    reachesBottomEdge = true;
                }
            }
        }
        helper.assertTrue(outsideVisible == 0,
                path + " 在逻辑尺寸 " + logicalWidth + "x" + logicalHeight
                        + " 之外必须全透明，发现 " + outsideVisible + " 个可见像素");
        helper.assertTrue(semiTransparent == 0,
                path + " 像素画界面的 alpha 只允许 0/255，发现 "
                        + semiTransparent + " 个半透明像素");
        helper.assertTrue(transparentWithRgb == 0,
                path + " 全透明像素的 RGB 必须为 0，发现 "
                        + transparentWithRgb + " 个隐藏颜色像素");
        helper.assertTrue(nearWhitePerimeter == 0,
                path + " 逻辑区域最外周长禁止近白可见像素，发现 "
                        + nearWhitePerimeter + " 个 RGB 三通道均不低于 224 的像素");
        helper.assertTrue(reachesRightEdge && reachesBottomEdge,
                path + " 的可见内容必须精确延伸到逻辑右边界与下边界 "
                        + logicalWidth + "x" + logicalHeight);
    }

    private static void assertNoTextureMetadata(GameTestHelper helper, String path) {
        helper.assertTrue(PowerUiContractGameTests.class.getResource(path + ".mcmeta") == null,
                path + ".mcmeta 禁止存在，避免 blur 线性过滤破坏像素画边缘");
    }

    private static void assertMachineSlots(GameTestHelper helper, String menuName,
                                           AbstractMiningMenu menu, SlotPoint... expected) {
        helper.assertTrue(menu.containerSlotCount() == expected.length,
                menuName + " 机器槽数必须为 " + expected.length
                        + "，实得 " + menu.containerSlotCount());
        for (int index = 0; index < expected.length; index++) {
            SlotPoint point = expected[index];
            Slot slot = menu.getSlot(index);
            helper.assertTrue(slot.getContainerSlot() == index && slot.x == point.x() && slot.y == point.y(),
                    menuName + " 机器槽 " + index + " 必须位于 (" + point.x() + "," + point.y()
                            + ")，实得容器索引/坐标 " + slot.getContainerSlot()
                            + "/(" + slot.x + "," + slot.y + ")");
        }
    }

    private static void assertSlotFramePixels(GameTestHelper helper, String path, BufferedImage image,
                                              List<SlotPoint> slotFrames) {
        for (SlotPoint point : slotFrames) {
            int actual = image.getRGB(point.x(), point.y());
            helper.assertTrue(actual == SLOT_FRAME_COLOR,
                    path + " 槽框左上像素 (" + point.x() + "," + point.y()
                            + ") 必须为钢框色 #FF4D606F，实得 #"
                            + String.format("%08X", actual));
        }
    }

    private static void assertPlayerInventory(GameTestHelper helper, String menuName, AbstractMiningMenu menu,
                                              Inventory inventory, int containerSlots,
                                              int originX, int originY, int hotbarY) {
        helper.assertTrue(menu.containerSlotCount() == containerSlots
                        && menu.slots.size() == containerSlots + 36,
                menuName + " 必须保持 " + containerSlots + " 个机器槽加 36 个玩家槽");
        int menuIndex = containerSlots;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int inventoryIndex = 9 + col + row * 9;
                assertPlayerSlot(helper, menuName, menu.getSlot(menuIndex++), inventory,
                        inventoryIndex, originX + col * 18, originY + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            assertPlayerSlot(helper, menuName, menu.getSlot(menuIndex++), inventory,
                    col, originX + col * 18, hotbarY);
        }
    }

    private static void assertPlayerSlot(GameTestHelper helper, String menuName, Slot slot,
                                         Inventory inventory, int inventoryIndex, int expectedX, int expectedY) {
        helper.assertTrue(slot.container == inventory && slot.getContainerSlot() == inventoryIndex
                        && slot.x == expectedX && slot.y == expectedY,
                menuName + " 玩家槽 " + inventoryIndex + " 必须绑定原玩家物品栏并位于 ("
                        + expectedX + "," + expectedY + ")，实得容器/索引/坐标 "
                        + slot.container.getClass().getName() + "/" + slot.getContainerSlot()
                        + "/(" + slot.x + "," + slot.y + ")");
    }

    private static byte[] loadResource(String path) {
        try (InputStream input = PowerUiContractGameTests.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("缺少 power UI atlas: " + path);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("读取 power UI atlas 失败: " + path, exception);
        }
    }

    private static BufferedImage decodeImage(String path, byte[] encoded) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(encoded));
            if (image == null) {
                throw new IllegalStateException("无法解码 power UI atlas: " + path);
            }
            return image;
        } catch (IOException exception) {
            throw new IllegalStateException("解码 power UI atlas 失败: " + path, exception);
        }
    }

    private static int readInt(byte[] bytes, int offset) {
        return (Byte.toUnsignedInt(bytes[offset]) << 24)
                | (Byte.toUnsignedInt(bytes[offset + 1]) << 16)
                | (Byte.toUnsignedInt(bytes[offset + 2]) << 8)
                | Byte.toUnsignedInt(bytes[offset + 3]);
    }

    private record AtlasSpec(String fileName, int logicalWidth, int logicalHeight,
                             List<SlotPoint> slotFrames) {
    }

    private record SlotPoint(int x, int y) {
    }
}
