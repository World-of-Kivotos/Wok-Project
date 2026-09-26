package com.miningdim.client.title;

import com.miningdim.core.MiningConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Team;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * 头顶名牌的称号行: 在原版名字 (及其下方计分项) 的上方单独渲染一行称号, 原版名字照常渲染、不做任何改动。
 *
 * <p>可见性与原版名字严格同步: 事件结果为 DENY 时不画; 为 ALLOW 时照画; 为 DEFAULT 时按
 * {@code LivingEntityRenderer#shouldShowName} 的同一套判据 (该方法是 protected, 这里逐条复刻: 距离 32/64 格、
 * 对本地玩家隐身、队伍名牌可见性、F1 隐藏界面、镜头实体自己、载具) —— 因此本地玩家自己 (第一人称或 F5)
 * 永远看不到自己的称号行, 与原版看不到自己的名字一致。潜行时与原版一样只画 NORMAL 一遍 (不透墙),
 * 否则先画一遍半透明透墙、再画一遍正常。
 *
 * <p>缓存清理: 实体离开客户端世界即摘除 (entityId 会被复用, 不清会张冠李戴); 客户端世界整体卸载 (断线、换维度)
 * 与登出整表清空 —— 断线走 Minecraft.clearLevel 时不逐实体触发离开事件。新世界里可见玩家的称号由服务端
 * StartTracking 补发重建。
 *
 * <p>仅客户端加载 (Dist.CLIENT 静态订阅), 不持任何服务端态。
 */
@Mod.EventBusSubscriber(modid = MiningConstants.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TitleNameTagRenderer {

    /** 名牌文字缩放 (与原版 EntityRenderer#renderNameTag 相同)。 */
    private static final float TEXT_SCALE = 0.025F;
    /** 一行名牌的世界高度 (原版 PlayerRenderer 在名字下方计分项之后上移的距离 9 * 1.15 * 0.025)。 */
    private static final float LINE_HEIGHT = 9.0F * 1.15F * TEXT_SCALE;
    /** 原版透墙那一遍的文字颜色 (0x20FFFFFF, 即 553648127)。 */
    private static final int SEE_THROUGH_TEXT_COLOR = 0x20FFFFFF;
    /** 原版只在 10 格内渲染名字下方的计分项。 */
    private static final double BELOW_NAME_DISTANCE_SQR = 100.0D;
    /** 计分板"名字下方"显示槽位 (Scoreboard.DISPLAY_SLOT_BELOW_NAME)。 */
    private static final int BELOW_NAME_SLOT = 2;
    /** 原版对名为 deadmau5 的玩家把名字上移 10 像素 (彩蛋), 称号行随之上移以免重叠。 */
    private static final String DEADMAU5 = "deadmau5";
    private static final float DEADMAU5_LIFT = 10.0F * TEXT_SCALE;

    private TitleNameTagRenderer() {
    }

    /**
     * LOW 优先级: 让其他模组先裁定事件结果 (DENY/ALLOW) 与名字内容, 本类据最终结果决定是否画称号行。
     * 事件对每个实体每帧都会触发, 非玩家或无称号时第一时间返回。
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRenderNameTag(RenderNameTagEvent event) {
        if (!(event.getEntity() instanceof AbstractClientPlayer player)) {
            return;
        }
        Component title = TitleClientCache.get(player.getId());
        if (title == null || event.getResult() == Event.Result.DENY) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        double distanceSqr = dispatcher.distanceToSqr(player);
        if (event.getResult() != Event.Result.ALLOW && !vanillaWouldShowName(minecraft, player, distanceSqr)) {
            return;
        }
        if (!ForgeHooksClient.isNameplateInRenderDistance(player, distanceSqr)) {
            return;
        }
        renderTitleLine(event, minecraft, dispatcher, player, title, distanceSqr);
    }

    private static void renderTitleLine(RenderNameTagEvent event, Minecraft minecraft,
                                        EntityRenderDispatcher dispatcher, AbstractClientPlayer player,
                                        Component title, double distanceSqr) {
        float lift = LINE_HEIGHT;
        if (distanceSqr < BELOW_NAME_DISTANCE_SQR
                && player.getScoreboard().getDisplayObjective(BELOW_NAME_SLOT) != null) {
            // PlayerRenderer 先在原位画计分项, 再把名字上移一行; 称号行须再高一行。
            lift += LINE_HEIGHT;
        }
        if (DEADMAU5.equals(event.getContent().getString())) {
            lift += DEADMAU5_LIFT;
        }

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(0.0F, player.getNameTagOffsetY() + lift, 0.0F);
        pose.mulPose(dispatcher.cameraOrientation());
        pose.scale(-TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE);
        Matrix4f matrix = pose.last().pose();
        int background = (int) (minecraft.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
        Font font = event.getEntityRenderer().getFont();
        float x = (float) (-font.width(title) / 2);
        boolean seeThrough = !player.isDiscrete();
        font.drawInBatch(title, x, 0.0F, SEE_THROUGH_TEXT_COLOR, false, matrix, event.getMultiBufferSource(),
                seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, background,
                event.getPackedLight());
        if (seeThrough) {
            font.drawInBatch(title, x, 0.0F, -1, false, matrix, event.getMultiBufferSource(),
                    Font.DisplayMode.NORMAL, 0, event.getPackedLight());
        }
        pose.popPose();
    }

    /** 逐条复刻 LivingEntityRenderer#shouldShowName (1.20.1), 见类注释。 */
    private static boolean vanillaWouldShowName(Minecraft minecraft, AbstractClientPlayer player, double distanceSqr) {
        float range = player.isDiscrete() ? 32.0F : 64.0F;
        if (distanceSqr >= (double) (range * range)) {
            return false;
        }
        LocalPlayer local = minecraft.player;
        if (local == null) {
            return false;
        }
        boolean visible = !player.isInvisibleTo(local);
        if (player != local) {
            Team team = player.getTeam();
            Team localTeam = local.getTeam();
            if (team != null) {
                return switch (team.getNameTagVisibility()) {
                    case ALWAYS -> visible;
                    case NEVER -> false;
                    case HIDE_FOR_OTHER_TEAMS -> localTeam == null
                            ? visible
                            : team.isAlliedTo(localTeam) && (team.canSeeFriendlyInvisibles() || visible);
                    case HIDE_FOR_OWN_TEAM -> localTeam == null
                            ? visible
                            : !team.isAlliedTo(localTeam) && visible;
                };
            }
        }
        return Minecraft.renderNames() && player != minecraft.getCameraEntity() && visible && !player.isVehicle();
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            TitleClientCache.remove(event.getEntity().getId());
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() != null && event.getLevel().isClientSide()) {
            TitleClientCache.clear();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TitleClientCache.clear();
    }
}
