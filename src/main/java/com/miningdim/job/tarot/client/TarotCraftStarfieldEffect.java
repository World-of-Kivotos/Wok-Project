package com.miningdim.job.tarot.client;

import com.miningdim.job.tarot.craft.TarotCraftService;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * Code-only result animation for the tarot synthesis screen.
 *
 * <p>The effect is deliberately stateless: the screen supplies a local age and seed, so reopening
 * or pausing the screen cannot leave particles behind. {@link #render(GuiGraphics, int, int, float,
 * TarotCraftService.Result, long)} uses the tarot screen's standard 218 x 132 effect area. The
 * bounds overload is available for previews and future screen layouts.</p>
 */
public final class TarotCraftStarfieldEffect {

    public static final float DURATION_TICKS = 56.0F;
    public static final int DEFAULT_WIDTH = 218;
    public static final int DEFAULT_HEIGHT = 132;

    private static final int SUCCESS_PARTICLES = 38;
    private static final int GREAT_SUCCESS_PARTICLES = 68;
    private static final int REVERSE_PARTICLES = 46;
    private static final int SHATTER_PARTICLES = 38;
    private static final int BIG_SHATTER_PARTICLES = 54;

    /** Client-only presentation variants. GREAT_SUCCESS is a Shiny synthesis reveal. */
    public enum Presentation {
        SUCCESS,
        GREAT_SUCCESS,
        REVERSE,
        SHATTER,
        BIG_SHATTER
    }

    private TarotCraftStarfieldEffect() {
    }

    /** Draws an effect over the standard tarot synthesis panel. */
    public static void render(GuiGraphics graphics, int left, int top, float ageTicks,
                              TarotCraftService.Result result, long seed) {
        render(graphics, left, top, DEFAULT_WIDTH, DEFAULT_HEIGHT, ageTicks, result, seed);
    }

    /** Draws a result effect, promoting a successful Shiny synthesis to GREAT_SUCCESS. */
    public static void render(GuiGraphics graphics, int left, int top, float ageTicks,
                              TarotCraftService.Result result, boolean greatSuccess, long seed) {
        render(graphics, left, top, DEFAULT_WIDTH, DEFAULT_HEIGHT,
                ageTicks, presentation(result, greatSuccess), seed);
    }

    /** Convenience overload for callers which only distinguish success from failure. */
    public static void render(GuiGraphics graphics, int left, int top, float ageTicks,
                              boolean success, long seed) {
        render(graphics, left, top, DEFAULT_WIDTH, DEFAULT_HEIGHT, ageTicks,
                success ? TarotCraftService.Result.SUCCESS : TarotCraftService.Result.SHATTER, seed);
    }

    /**
     * Draws one deterministic frame, clipped to the supplied panel bounds.
     * Every presentation has its own visual language: blue-white synthesis, gold Shiny
     * coronation, violet inversion, light fracture, or catastrophic double fracture.
     */
    public static void render(GuiGraphics graphics, int left, int top, int width, int height,
                              float ageTicks, TarotCraftService.Result result, long seed) {
        render(graphics, left, top, width, height, ageTicks,
                presentation(result, false), seed);
    }

    /** Resolves the five visually distinct synthesis presentations without changing game rules. */
    public static Presentation presentation(TarotCraftService.Result result, boolean greatSuccess) {
        if (result == null) {
            return null;
        }
        return switch (result) {
            case SUCCESS -> greatSuccess ? Presentation.GREAT_SUCCESS : Presentation.SUCCESS;
            case REVERSE -> Presentation.REVERSE;
            case SHATTER -> Presentation.SHATTER;
            case BIG_SHATTER -> Presentation.BIG_SHATTER;
        };
    }

    /** Draws one deterministic frame for a fully resolved presentation variant. */
    public static void render(GuiGraphics graphics, int left, int top, int width, int height,
                              float ageTicks, Presentation presentation, long seed) {
        if (graphics == null || presentation == null
                || width <= 0 || height <= 0 || !isActive(ageTicks)) {
            return;
        }

        float age = Mth.clamp(ageTicks, 0.0F, DURATION_TICKS);
        int centerX = left + width / 2;
        int centerY = top + Math.min(59, height / 2);

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 280.0F);
        graphics.enableScissor(left, top, left + width, top + height);
        switch (presentation) {
            case SUCCESS -> renderSuccess(
                    graphics, left, top, width, height, centerX, centerY, age, seed);
            case GREAT_SUCCESS -> renderGreatSuccess(
                    graphics, left, top, width, height, centerX, centerY, age, seed);
            case REVERSE -> renderReverse(
                    graphics, left, top, width, height, centerX, centerY, age, seed);
            case SHATTER -> renderFailure(
                    graphics, left, top, width, height, centerX, centerY, age, seed, false);
            case BIG_SHATTER -> renderFailure(
                    graphics, left, top, width, height, centerX, centerY, age, seed, true);
        }
        graphics.disableScissor();
        graphics.pose().popPose();
    }

    public static boolean isActive(float ageTicks) {
        return ageTicks >= 0.0F && ageTicks < DURATION_TICKS;
    }

    private static void renderSuccess(GuiGraphics graphics, int left, int top, int width, int height,
                                      int centerX, int centerY, float age, long seed) {
        float gather = smoothstep(0.0F, 25.0F, age);
        float endFade = 1.0F - smoothstep(43.0F, DURATION_TICKS, age);

        // Distant blue-white stars spiral into the synthesis core.
        for (int i = 0; i < SUCCESS_PARTICLES; i++) {
            float delay = unit(seed, i, 0) * 7.0F;
            float local = smoothstep(delay, 27.0F + delay * 0.20F, age);
            float angle = unit(seed, i, 1) * Mth.TWO_PI + local * 2.1F;
            float startRadiusX = 34.0F + unit(seed, i, 2) * Math.max(36.0F, width * 0.42F);
            float startRadiusY = 22.0F + unit(seed, i, 3) * Math.max(18.0F, height * 0.34F);
            float radiusFactor = 1.0F - easeOutCubic(local);
            int x = Mth.floor(centerX + Mth.cos(angle) * startRadiusX * radiusFactor);
            int y = Mth.floor(centerY + Mth.sin(angle) * startRadiusY * radiusFactor);
            float twinkle = 0.58F + 0.42F * Mth.sin(age * 0.72F + i * 2.17F);
            float alpha = Mth.clamp((0.22F + local * 0.78F) * twinkle * endFade, 0.0F, 1.0F);
            int color = switch (i % 5) {
                case 0 -> 0xFFE9C76D;
                case 1, 2 -> 0xFFF6FCFF;
                default -> 0xFF76DBFF;
            };
            drawSpark(graphics, x, y, i % 9 == 0 ? 3 : 2, withAlpha(color, alpha));
        }

        // Two counter-rotating astrolabe rings blossom from the point of convergence.
        float firstRing = window(age, 14.0F, 42.0F);
        float secondRing = window(age, 20.0F, 49.0F);
        drawStarWheel(graphics, centerX, centerY, 8.0F + 60.0F * easeOutCubic(
                        smoothstep(14.0F, 42.0F, age)), age * 0.085F, 24,
                withAlpha(0xFF83E9FF, firstRing * 0.88F), false);
        drawStarWheel(graphics, centerX, centerY, 5.0F + 45.0F * easeOutCubic(
                        smoothstep(20.0F, 49.0F, age)), -age * 0.12F, 16,
                withAlpha(0xFFFFD879, secondRing * 0.95F), true);

        // A short, decisive white-blue flash marks the resolved result.
        float flash = triangle(age, 23.5F, 7.5F);
        if (flash > 0.0F) {
            int veilAlpha = Mth.floor(105.0F * flash * flash);
            graphics.fill(left, top, left + width, top + height, (veilAlpha << 24) | 0xD9F7FF);
            drawCrossBurst(graphics, centerX, centerY, 8.0F + 65.0F * flash,
                    withAlpha(0xFFF9FEFF, flash), 12);
            drawCrossBurst(graphics, centerX, centerY, 5.0F + 42.0F * flash,
                    withAlpha(0xFFFFD97C, flash * 0.92F), 4);
            drawSpark(graphics, centerX, centerY, 5, withAlpha(0xFFFFFFFF, flash));
        }

        // A few calm ascending motes let the result breathe after the flash.
        float release = smoothstep(27.0F, 35.0F, age) * endFade;
        for (int i = 0; i < 14; i++) {
            float phase = Mth.clamp((age - 26.0F - unit(seed, i, 7) * 8.0F) / 22.0F, 0.0F, 1.0F);
            float angle = unit(seed, i, 8) * Mth.TWO_PI;
            int x = Mth.floor(centerX + Mth.cos(angle) * (9.0F + 58.0F * phase));
            int y = Mth.floor(centerY + Mth.sin(angle) * (5.0F + 25.0F * phase) - phase * 13.0F);
            drawSpark(graphics, x, y, i % 4 == 0 ? 2 : 1,
                    withAlpha(i % 3 == 0 ? 0xFFFFD879 : 0xFFB9F3FF,
                            release * (1.0F - phase)));
        }
    }

    /** Gold-white coronation reserved for a successful UR to Shiny synthesis. */
    private static void renderGreatSuccess(GuiGraphics graphics, int left, int top,
                                           int width, int height, int centerX, int centerY,
                                           float age, long seed) {
        float endFade = 1.0F - smoothstep(47.0F, DURATION_TICKS, age);
        float release = smoothstep(23.0F, 42.0F, age);

        // The whole panel takes on a warm celestial sheen before the coronation burst.
        float veil = window(age, 2.0F, 51.0F);
        graphics.fill(left, top, left + width, top + height,
                withAlpha(0xFF173954, veil * 0.24F));

        // A denser two-colour galaxy contracts, then blossoms back out as a radiant halo.
        for (int i = 0; i < GREAT_SUCCESS_PARTICLES; i++) {
            float delay = unit(seed, i, 30) * 6.0F;
            float local = smoothstep(delay, 23.0F + delay * 0.18F, age);
            float direction = (i & 1) == 0 ? 1.0F : -1.0F;
            float angle = unit(seed, i, 31) * Mth.TWO_PI
                    + direction * (local * 2.8F + release * 1.3F);
            float startRadiusX = 42.0F + unit(seed, i, 32) * Math.max(38.0F, width * 0.48F);
            float startRadiusY = 24.0F + unit(seed, i, 33) * Math.max(20.0F, height * 0.38F);
            float radiusFactor = (1.0F - easeOutCubic(local)) + release * (0.36F + unit(seed, i, 34));
            int x = Mth.floor(centerX + Mth.cos(angle) * startRadiusX * radiusFactor);
            int y = Mth.floor(centerY + Mth.sin(angle) * startRadiusY * radiusFactor
                    - release * (4.0F + unit(seed, i, 35) * 14.0F));
            float twinkle = 0.64F + 0.36F * Mth.sin(age * 0.86F + i * 1.71F);
            float alpha = Mth.clamp((0.32F + local * 0.68F)
                    * twinkle * endFade * (1.0F - release * 0.22F), 0.0F, 1.0F);
            int color = switch (i % 6) {
                case 0, 1 -> 0xFFFFE27A;
                case 2 -> 0xFFFFFFFF;
                case 3 -> 0xFF8DEBFF;
                default -> 0xFFFFB84F;
            };
            drawSpark(graphics, x, y, i % 10 == 0 ? 4 : (i % 3 == 0 ? 2 : 1),
                    withAlpha(color, alpha));
        }

        // Three independently rotating celestial seals make this read immediately above success.
        float seal = window(age, 10.0F, 51.0F);
        drawStarWheel(graphics, centerX, centerY,
                10.0F + 79.0F * easeOutCubic(smoothstep(12.0F, 45.0F, age)),
                age * 0.115F, 32, withAlpha(0xFFFFE27A, seal * 0.96F), true);
        drawStarWheel(graphics, centerX, centerY,
                7.0F + 61.0F * easeOutCubic(smoothstep(15.0F, 43.0F, age)),
                -age * 0.16F, 28, withAlpha(0xFF87EEFF, seal * 0.88F), false);
        drawStarWheel(graphics, centerX, centerY,
                5.0F + 39.0F * easeOutCubic(smoothstep(20.0F, 39.0F, age)),
                age * 0.22F, 20, withAlpha(0xFFFFFFFF, seal), true);

        float flash = triangle(age, 24.5F, 9.5F);
        if (flash > 0.0F) {
            graphics.fill(left, top, left + width, top + height,
                    withAlpha(0xFFFFF4C1, flash * flash * 0.58F));
            drawCrossBurst(graphics, centerX, centerY, 18.0F + 104.0F * flash,
                    withAlpha(0xFFFFF6CE, flash), 20);
            drawCrossBurst(graphics, centerX, centerY, 10.0F + 75.0F * flash,
                    withAlpha(0xFF8DEBFF, flash * 0.90F), 12);
            drawSpark(graphics, centerX, centerY, 7,
                    withAlpha(0xFFFFFFFF, flash));
        }

        // A crown of descending stars lingers while the Shiny card is about to be revealed.
        float rain = smoothstep(27.0F, 34.0F, age) * endFade;
        for (int i = 0; i < 18; i++) {
            float phase = Mth.clamp((age - 27.0F - unit(seed, i, 38) * 9.0F) / 19.0F,
                    0.0F, 1.0F);
            int x = Mth.floor(left + 12.0F + unit(seed, i, 39) * (width - 24.0F));
            int y = Mth.floor(top - 7.0F + phase * (height + 8.0F));
            drawSpark(graphics, x, y, i % 5 == 0 ? 3 : 2,
                    withAlpha(i % 3 == 0 ? 0xFF8DEBFF : 0xFFFFE27A,
                            rain * (1.0F - phase * 0.72F)));
        }
    }

    /** Violet mirror inversion used only by the orientation-flipping successful outcome. */
    private static void renderReverse(GuiGraphics graphics, int left, int top,
                                      int width, int height, int centerX, int centerY,
                                      float age, long seed) {
        float endFade = 1.0F - smoothstep(45.0F, DURATION_TICKS, age);
        float veil = window(age, 1.0F, 50.0F);
        graphics.fill(left, top, left + width, top + height,
                withAlpha(0xFF1A082F, veil * 0.38F));

        // Split the constellation into opposing clockwise/counter-clockwise streams.
        for (int i = 0; i < REVERSE_PARTICLES; i++) {
            float delay = unit(seed, i, 50) * 8.0F;
            float local = smoothstep(delay, 27.0F + delay * 0.15F, age);
            float direction = (i & 1) == 0 ? 1.0F : -1.0F;
            float angle = unit(seed, i, 51) * Mth.TWO_PI + direction * local * 3.4F;
            float startRadiusX = 37.0F + unit(seed, i, 52) * Math.max(35.0F, width * 0.43F);
            float startRadiusY = 23.0F + unit(seed, i, 53) * Math.max(18.0F, height * 0.35F);
            float radiusFactor = 1.0F - easeOutCubic(local) * 0.90F;
            int x = Mth.floor(centerX + Mth.cos(angle) * startRadiusX * radiusFactor);
            int y = Mth.floor(centerY + Mth.sin(angle) * startRadiusY * radiusFactor);
            float alpha = Mth.clamp((0.28F + local * 0.72F)
                    * (0.58F + 0.42F * Mth.sin(age * 0.78F + i * 1.93F)) * endFade,
                    0.0F, 1.0F);
            int color = switch (i % 4) {
                case 0 -> 0xFFF0B2FF;
                case 1 -> 0xFF8F7CFF;
                case 2 -> 0xFF8DEBFF;
                default -> 0xFFFFFFFF;
            };
            drawFragment(graphics, x, y, i % 8 == 0 ? 4 : 2,
                    direction * (age * 13.0F + i * 7.0F), withAlpha(color, alpha));
        }

        float mirror = window(age, 9.0F, 45.0F);
        drawStarWheel(graphics, centerX, centerY,
                9.0F + 68.0F * easeOutCubic(smoothstep(12.0F, 41.0F, age)),
                age * 0.14F, 24, withAlpha(0xFFD08AFF, mirror * 0.92F), true);
        drawStarWheel(graphics, centerX, centerY,
                6.0F + 52.0F * easeOutCubic(smoothstep(16.0F, 43.0F, age)),
                -age * 0.19F, 24, withAlpha(0xFF72E6FF, mirror * 0.84F), false);

        // A vertical mirror seam flips through the core, followed by two opposed waves.
        float seam = triangle(age, 22.0F, 10.0F);
        if (seam > 0.0F) {
            int seamHalfHeight = Mth.floor((height * 0.48F) * seam);
            graphics.fill(centerX - 1, centerY - seamHalfHeight,
                    centerX + 2, centerY + seamHalfHeight + 1,
                    withAlpha(0xFFF5E8FF, seam));
            graphics.fill(centerX - 3, centerY - seamHalfHeight,
                    centerX + 4, centerY + seamHalfHeight + 1,
                    withAlpha(0xFF9C75FF, seam * 0.22F));
        }

        float flip = triangle(age, 25.0F, 7.5F);
        if (flip > 0.0F) {
            graphics.fill(left, top, left + width, top + height,
                    withAlpha(0xFFD9B5FF, flip * flip * 0.34F));
            drawRay(graphics, centerX, centerY, 0.0F, 92.0F * flip, 2,
                    withAlpha(0xFF8DEBFF, flip));
            drawRay(graphics, centerX, centerY, Mth.PI, 92.0F * flip, 2,
                    withAlpha(0xFFD99CFF, flip));
            drawSpark(graphics, centerX, centerY, 5,
                    withAlpha(0xFFFFFFFF, flip));
        }

        float after = smoothstep(28.0F, 35.0F, age) * endFade;
        for (int i = 0; i < 12; i++) {
            float side = (i & 1) == 0 ? 1.0F : -1.0F;
            float phase = Mth.clamp((age - 27.0F - unit(seed, i, 58) * 6.0F) / 20.0F,
                    0.0F, 1.0F);
            int x = Mth.floor(centerX + side * (10.0F + 76.0F * phase));
            int y = Mth.floor(centerY + (unit(seed, i, 59) - 0.5F) * 43.0F);
            drawSpark(graphics, x, y, i % 4 == 0 ? 2 : 1,
                    withAlpha(side > 0.0F ? 0xFF8DEBFF : 0xFFD99CFF,
                            after * (1.0F - phase)));
        }
    }

    private static void renderFailure(GuiGraphics graphics, int left, int top, int width, int height,
                                      int centerX, int centerY, float age, long seed, boolean big) {
        float intensity = big ? 1.22F : 1.0F;
        float endFade = 1.0F - smoothstep(43.0F, DURATION_TICKS, age);

        // The panel loses light around the core before the star breaks.
        float dark = smoothstep(1.0F, 13.0F, age) * (1.0F - smoothstep(35.0F, 54.0F, age));
        graphics.fill(left, top, left + width, top + height,
                withAlpha(big ? 0xFF120611 : 0xFF100A1A, dark * (big ? 0.68F : 0.52F)));
        for (int ring = 5; ring >= 1; ring--) {
            float ringAlpha = dark * (0.045F + (6 - ring) * 0.025F);
            int radiusX = Mth.floor(width * ring / 12.0F);
            int radiusY = Mth.floor(height * ring / 12.0F);
            graphics.fill(centerX - radiusX, centerY - radiusY,
                    centerX + radiusX + 1, centerY + radiusY + 1,
                    withAlpha(0xFF07030C, ringAlpha));
        }

        float compression = smoothstep(0.0F, 13.0F, age) * (1.0F - smoothstep(13.0F, 18.0F, age));
        if (compression > 0.0F) {
            float pulse = 1.0F + 0.18F * Mth.sin(age * 1.65F);
            drawCrossBurst(graphics, centerX, centerY, (9.0F + 18.0F * compression) * pulse,
                    withAlpha(0xFFE58CFF, compression), 8);
            drawSpark(graphics, centerX, centerY, 4,
                    withAlpha(0xFFFFE5FA, compression));
        }

        // Broken star fragments fly out, turn, and fall under gravity.
        int count = big ? BIG_SHATTER_PARTICLES : SHATTER_PARTICLES;
        float flight = Math.max(0.0F, age - 13.0F);
        for (int i = 0; i < count; i++) {
            float delay = unit(seed, i, 12) * 4.0F;
            float t = Math.max(0.0F, flight - delay);
            if (t <= 0.0F) {
                continue;
            }
            float angle = unit(seed, i, 13) * Mth.TWO_PI;
            float speed = (1.20F + unit(seed, i, 14) * 2.55F) * intensity;
            float vx = Mth.cos(angle) * speed;
            float vy = Mth.sin(angle) * speed - (0.25F + unit(seed, i, 15) * 0.90F);
            float gravity = 0.055F + unit(seed, i, 16) * 0.035F;
            int x = Mth.floor(centerX + vx * t);
            int y = Mth.floor(centerY + vy * t + gravity * t * t);
            float life = Mth.clamp(1.0F - t / (big ? 43.0F : 37.0F), 0.0F, 1.0F) * endFade;
            int color = switch (i % 5) {
                case 0 -> 0xFFFF6C93;
                case 1 -> 0xFFE484FF;
                case 2 -> 0xFFFFB5D5;
                default -> 0xFF9E4ED6;
            };
            drawFragment(graphics, x, y, 2 + (i % 3), age * (11.0F + i % 7),
                    withAlpha(color, life));
        }

        // Jagged radial fracture lines remain briefly after impact.
        float crack = window(age, 12.0F, big ? 36.0F : 31.0F);
        if (crack > 0.0F) {
            int rays = big ? 18 : 12;
            for (int i = 0; i < rays; i++) {
                float angle = (Mth.TWO_PI * i / rays) + unit(seed, i, 20) * 0.17F;
                float length = (18.0F + unit(seed, i, 21) * (big ? 69.0F : 51.0F))
                        * smoothstep(12.0F, 22.0F, age);
                drawRay(graphics, centerX, centerY, angle, length, i % 3 == 0 ? 2 : 1,
                        withAlpha(i % 2 == 0 ? 0xFFFF5F8A : 0xFFB75DE7, crack * 0.78F));
            }
        }

        float shock = window(age, 13.0F, 31.0F);
        drawStarWheel(graphics, centerX, centerY,
                5.0F + 74.0F * easeOutCubic(smoothstep(13.0F, 31.0F, age)), age * 0.035F,
                big ? 24 : 18, withAlpha(0xFFFF527C, shock * 0.74F), true);
        if (big) {
            float secondShock = window(age, 18.0F, 41.0F);
            drawStarWheel(graphics, centerX, centerY,
                    8.0F + 91.0F * easeOutCubic(smoothstep(18.0F, 41.0F, age)), -age * 0.028F,
                    28, withAlpha(0xFF9E43D3, secondShock * 0.60F), false);
        }

        float impact = triangle(age, 14.0F, big ? 4.0F : 3.0F);
        if (impact > 0.0F) {
            graphics.fill(left, top, left + width, top + height,
                    withAlpha(big ? 0xFFD94169 : 0xFF933B7C, impact * (big ? 0.32F : 0.22F)));
            drawSpark(graphics, centerX, centerY, big ? 5 : 4,
                    withAlpha(0xFFFFD6EC, impact));
        }
    }

    private static void drawStarWheel(GuiGraphics graphics, int centerX, int centerY, float radius,
                                      float rotation, int points, int color, boolean diamonds) {
        if ((color >>> 24) == 0 || radius <= 0.0F) {
            return;
        }
        for (int i = 0; i < points; i++) {
            float angle = rotation + Mth.TWO_PI * i / points;
            int x = Mth.floor(centerX + Mth.cos(angle) * radius);
            int y = Mth.floor(centerY + Mth.sin(angle) * radius * 0.58F);
            if (diamonds && (i & 1) == 0) {
                drawFragment(graphics, x, y, 3, angle * Mth.RAD_TO_DEG, color);
            } else {
                drawSpark(graphics, x, y, i % 6 == 0 ? 2 : 1, color);
            }
        }
    }

    private static void drawCrossBurst(GuiGraphics graphics, int centerX, int centerY, float radius,
                                       int color, int rays) {
        if ((color >>> 24) == 0) {
            return;
        }
        for (int i = 0; i < rays; i++) {
            drawRay(graphics, centerX, centerY, Mth.TWO_PI * i / rays, radius,
                    i % 4 == 0 ? 2 : 1, color);
        }
    }

    private static void drawRay(GuiGraphics graphics, int centerX, int centerY, float angle,
                                float length, int thickness, int color) {
        if ((color >>> 24) == 0 || length <= 0.0F) {
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY, 0.0F);
        graphics.pose().mulPose(Axis.ZP.rotation(angle));
        graphics.fill(2, -thickness / 2, Mth.floor(length) + 2,
                (thickness + 1) / 2, color);
        graphics.pose().popPose();
    }

    private static void drawSpark(GuiGraphics graphics, int x, int y, int radius, int color) {
        if ((color >>> 24) == 0) {
            return;
        }
        int r = Math.max(1, radius);
        graphics.fill(x - r, y, x + r + 1, y + 1, color);
        graphics.fill(x, y - r, x + 1, y + r + 1, color);
        if (r >= 3) {
            int faint = withAlpha(color, ((color >>> 24) / 255.0F) * 0.42F);
            graphics.fill(x - r + 1, y - 1, x + r, y + 2, faint);
            graphics.fill(x - 1, y - r + 1, x + 2, y + r, faint);
        }
    }

    private static void drawFragment(GuiGraphics graphics, int x, int y, int size,
                                     float degrees, int color) {
        if ((color >>> 24) == 0) {
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(degrees));
        int half = Math.max(1, size / 2);
        graphics.fill(-half, -1, size - half + 1, 2, color);
        graphics.fill(-1, -half, 1, half + 1, color);
        graphics.pose().popPose();
    }

    private static int withAlpha(int color, float alpha) {
        return (Mth.floor(Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24) | (color & 0x00FFFFFF);
    }

    private static float smoothstep(float start, float end, float value) {
        if (end <= start) {
            return value >= end ? 1.0F : 0.0F;
        }
        float t = Mth.clamp((value - start) / (end - start), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    private static float easeOutCubic(float value) {
        float inverse = 1.0F - Mth.clamp(value, 0.0F, 1.0F);
        return 1.0F - inverse * inverse * inverse;
    }

    private static float window(float age, float start, float end) {
        float in = smoothstep(start, start + 5.0F, age);
        float out = 1.0F - smoothstep(end - 8.0F, end, age);
        return in * out;
    }

    private static float triangle(float value, float center, float halfWidth) {
        return Mth.clamp(1.0F - Math.abs(value - center) / halfWidth, 0.0F, 1.0F);
    }

    /** Deterministic [0, 1) pseudo-random value without allocating a RandomSource each frame. */
    private static float unit(long seed, int index, int channel) {
        long value = seed + index * 0x9E3779B97F4A7C15L + channel * 0xD1B54A32D192ED03L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (float) ((value >>> 40) & 0xFFFFFFL) / 16777216.0F;
    }
}
