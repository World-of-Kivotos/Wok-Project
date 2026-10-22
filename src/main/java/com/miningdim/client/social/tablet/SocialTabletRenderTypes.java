package com.miningdim.client.social.tablet;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * 社交平板 3D 层的渲染类型。
 *
 * <ul>
 *   <li>{@link #front()}：银色正面。position-colour-texture，无光照贴图、无雾，丢弃 alpha &lt; 0.1
 *   的像素（贴图四角是透明的，圆角靠它切出来），线性采样，不剔除。</li>
 *   <li>{@link #browser(int)}：浏览器画面。CEF 交出来的是<b>预乘 alpha</b> 的 BGRA，所以混合用
 *   {@code ONE, ONE_MINUS_SRC_ALPHA}（与 {@code WebBrowser#render} 同一套公式），否则圆角/文字边缘
 *   会被压出一道暗边。原生 GL 贴图 id 通过自定义 state shard 绑定。</li>
 *   <li>{@link #body(ResourceLocation)}：侧壁与背板，走原版实体光照，顶点色给银色。</li>
 * </ul>
 */
final class SocialTabletRenderTypes extends RenderType {
    private static final Map<Long, RenderType> FRONTS = new HashMap<>();
    private static final Map<Integer, RenderType> BROWSERS = new HashMap<>();

    private SocialTabletRenderTypes() {
        super("miningdim_social_tablet_unused", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS,
                0, false, false, () -> {
                }, () -> {
                });
    }

    /** 正面：全亮、alpha 裁剪、线性采样。 */
    static RenderType front() {
        return FRONTS.computeIfAbsent(1L, key -> create("miningdim_social_tablet_front",
                DefaultVertexFormat.POSITION_COLOR_TEX, VertexFormat.Mode.QUADS, 256, false, true,
                CompositeState.builder()
                        .setShaderState(POSITION_COLOR_TEX_SHADER)
                        .setTextureState(new TextureStateShard(SocialDeviceArt.FRONT, true, false))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setLightmapState(NO_LIGHTMAP)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(COLOR_DEPTH_WRITE)
                        .createCompositeState(false)));
    }

    /** 浏览器画面：预乘 alpha、全亮、alpha 裁剪。 */
    static RenderType browser(int textureId) {
        return BROWSERS.computeIfAbsent(textureId, id -> create("miningdim_social_tablet_browser",
                DefaultVertexFormat.POSITION_COLOR_TEX, VertexFormat.Mode.QUADS, 256, false, true,
                CompositeState.builder()
                        .setShaderState(POSITION_COLOR_TEX_SHADER)
                        .setTextureState(new RawTextureState(id))
                        .setTransparencyState(PREMULTIPLIED_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setLightmapState(NO_LIGHTMAP)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(COLOR_DEPTH_WRITE)
                        .createCompositeState(false)));
    }

    /** 机身侧壁 / 背板 / 手套：原版实体光照，顶点色着色。 */
    static RenderType body(ResourceLocation texture) {
        return RenderType.entityCutoutNoCull(texture);
    }

    /** 绑定一个原生 GL 贴图 id 的纹理状态（1.20.1 的 shard 以 Runnable 形式收 setup/clear）。 */
    private static final class RawTextureState extends EmptyTextureStateShard {
        private RawTextureState(int id) {
            super(() -> RenderSystem.setShaderTexture(0, id), () -> {
            });
        }
    }

    /** CEF 预乘 alpha。 */
    private static final TransparencyStateShard PREMULTIPLIED_TRANSPARENCY =
            new TransparencyStateShard("miningdim_social_tablet_premultiplied", () -> {
                RenderSystem.enableBlend();
                RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                        com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            }, () -> {
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableBlend();
            });

    /** 浏览器贴图换掉后清缓存（关闭浏览器时调用）。 */
    static void forgetBrowsers() {
        BROWSERS.clear();
    }
}
