package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;

/**
 * Weather sheets for the <b>upside-down band</b>, where rain and snow travel <em>upward</em>.
 *
 * <p>The band mirrors the world around {@code trainY + upsideDownMirrorPlaneOffset}: terrain hangs
 * from a ceiling and the sky sits below the train. Vanilla's {@code LevelRenderer#renderSnowAndRain}
 * is a copy of this loop with two differences, and both of them are wrong in a flipped world:</p>
 * <ul>
 *   <li>Its animated texture scroll runs one way, so the streaks read as falling. Here the sign is
 *       flipped, so they read as rising.</li>
 *   <li>It clamps each column's Y window to sit <em>above</em> the {@code MOTION_BLOCKING} heightmap,
 *       so nothing draws underground. In-band the topmost motion-blocking block is the mirrored
 *       ceiling / bedrock lid, well above the train, which collapses the window to zero height and
 *       draws no weather at all. Here the window is the plain {@code [camY ± radius]} band around the
 *       camera and the depth test — enabled below, as in vanilla — hides whatever the hanging terrain
 *       covers, which is the flipped equivalent of that rule.</li>
 * </ul>
 *
 * <p>Everything else (biome precipitation gate, per-column jitter, distance alpha falloff, batching by
 * texture, render state) is vanilla's, so the sheets look the same — only inverted. Driven from
 * {@code LevelRendererUpsideDownRainMixin}, which delegates here for band columns and lets vanilla
 * render everywhere else.</p>
 */
public final class UpsideDownRainRenderer {

    private static final ResourceLocation RAIN_LOCATION =
            ResourceLocation.withDefaultNamespace("textures/environment/rain.png");
    private static final ResourceLocation SNOW_LOCATION =
            ResourceLocation.withDefaultNamespace("textures/environment/snow.png");

    /** Column radius around the camera, in blocks — vanilla's 5, or 10 on fancy graphics. */
    private static final int FAST_RADIUS = 5;
    private static final int FANCY_RADIUS = 10;

    /** Batch marker for "nothing buffered yet" / the two sheet textures. */
    private static final int BATCH_NONE = -1;
    private static final int BATCH_RAIN = 0;
    private static final int BATCH_SNOW = 1;

    private UpsideDownRainRenderer() {}

    /**
     * Draw the upward weather sheets around the camera.
     *
     * @param rainSizeX  vanilla's per-offset quad half-width table (shadowed off {@code LevelRenderer})
     * @param rainSizeZ  the matching half-depth table
     * @param ticks      the renderer's tick counter, which drives the scroll animation
     */
    public static void render(ClientLevel level, LightTexture lightTexture, float partialTick,
                              double camX, double camY, double camZ,
                              float[] rainSizeX, float[] rainSizeZ, int ticks) {
        float rainLevel = level.getRainLevel(partialTick);
        if (rainLevel <= 0.0F) return;

        lightTexture.turnOnLightLayer();
        int camBlockX = Mth.floor(camX);
        int camBlockY = Mth.floor(camY);
        int camBlockZ = Mth.floor(camZ);
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = null;
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        int radius = Minecraft.useFancyGraphics() ? FANCY_RADIUS : FAST_RADIUS;
        RenderSystem.depthMask(Minecraft.useShaderTransparency());
        int batch = BATCH_NONE;
        float age = (float) ticks + partialTick;
        RenderSystem.setShader(GameRenderer::getParticleShader);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int bz = camBlockZ - radius; bz <= camBlockZ + radius; bz++) {
            for (int bx = camBlockX - radius; bx <= camBlockX + radius; bx++) {
                int sizeIndex = (bz - camBlockZ + 16) * 32 + bx - camBlockX + 16;
                double halfWidth = (double) rainSizeX[sizeIndex] * 0.5;
                double halfDepth = (double) rainSizeZ[sizeIndex] * 0.5;
                cursor.set((double) bx, camY, (double) bz);
                Biome biome = level.getBiome(cursor).value();
                if (!biome.hasPrecipitation()) continue;

                // No heightmap clamp: in-band the "ground" hangs overhead, so the sheets fill the open
                // gap around the camera and the depth test occludes them against the mirrored ceiling.
                int bottomY = camBlockY - radius;
                int topY = camBlockY + radius;
                int lightY = camBlockY;

                RandomSource random = RandomSource.create(
                        (long) (bx * bx * 3121 + bx * 45238971 ^ bz * bz * 418711 + bz * 13761));
                cursor.set(bx, bottomY, bz);
                Biome.Precipitation precipitation = biome.getPrecipitationAt(cursor);
                if (precipitation == Biome.Precipitation.RAIN) {
                    if (batch != BATCH_RAIN) {
                        if (batch != BATCH_NONE) {
                            BufferUploader.drawWithShader(buffer.buildOrThrow());
                        }
                        batch = BATCH_RAIN;
                        RenderSystem.setShaderTexture(0, RAIN_LOCATION);
                        buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
                    }

                    int wrappedTicks = ticks & 131071;
                    int columnHash = bx * bx * 3121 + bx * 45238971 + bz * bz * 418711 + bz * 13761 & 0xFF;
                    float speed = 3.0F + random.nextFloat();
                    // Sign flipped from vanilla's `-(...)`: the streaks scroll upward.
                    float scroll = ((float) (wrappedTicks + columnHash) + partialTick) / 32.0F * speed;
                    float scrollV = scroll % 32.0F;
                    double dx = (double) bx + 0.5 - camX;
                    double dz = (double) bz + 0.5 - camZ;
                    float distance = (float) Math.sqrt(dx * dx + dz * dz) / (float) radius;
                    float alpha = ((1.0F - distance * distance) * 0.5F + 0.5F) * rainLevel;
                    cursor.set(bx, lightY, bz);
                    int light = LevelRenderer.getLightColor(level, cursor);
                    buffer.addVertex(
                                    (float) ((double) bx - camX - halfWidth + 0.5),
                                    (float) ((double) topY - camY),
                                    (float) ((double) bz - camZ - halfDepth + 0.5))
                            .setUv(0.0F, (float) bottomY * 0.25F + scrollV)
                            .setColor(1.0F, 1.0F, 1.0F, alpha)
                            .setLight(light);
                    buffer.addVertex(
                                    (float) ((double) bx - camX + halfWidth + 0.5),
                                    (float) ((double) topY - camY),
                                    (float) ((double) bz - camZ + halfDepth + 0.5))
                            .setUv(1.0F, (float) bottomY * 0.25F + scrollV)
                            .setColor(1.0F, 1.0F, 1.0F, alpha)
                            .setLight(light);
                    buffer.addVertex(
                                    (float) ((double) bx - camX + halfWidth + 0.5),
                                    (float) ((double) bottomY - camY),
                                    (float) ((double) bz - camZ + halfDepth + 0.5))
                            .setUv(1.0F, (float) topY * 0.25F + scrollV)
                            .setColor(1.0F, 1.0F, 1.0F, alpha)
                            .setLight(light);
                    buffer.addVertex(
                                    (float) ((double) bx - camX - halfWidth + 0.5),
                                    (float) ((double) bottomY - camY),
                                    (float) ((double) bz - camZ - halfDepth + 0.5))
                            .setUv(0.0F, (float) topY * 0.25F + scrollV)
                            .setColor(1.0F, 1.0F, 1.0F, alpha)
                            .setLight(light);
                } else if (precipitation == Biome.Precipitation.SNOW) {
                    if (batch != BATCH_SNOW) {
                        if (batch != BATCH_NONE) {
                            BufferUploader.drawWithShader(buffer.buildOrThrow());
                        }
                        batch = BATCH_SNOW;
                        RenderSystem.setShaderTexture(0, SNOW_LOCATION);
                        buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
                    }

                    // Sign flipped from vanilla's `-(...)`: the flakes drift upward.
                    float scrollV = ((float) (ticks & 511) + partialTick) / 512.0F;
                    float driftU = (float) (random.nextDouble()
                            + (double) age * 0.01 * (double) ((float) random.nextGaussian()));
                    float driftV = (float) (random.nextDouble()
                            + (double) (age * (float) random.nextGaussian()) * 0.001);
                    double dx = (double) bx + 0.5 - camX;
                    double dz = (double) bz + 0.5 - camZ;
                    float distance = (float) Math.sqrt(dx * dx + dz * dz) / (float) radius;
                    float alpha = ((1.0F - distance * distance) * 0.3F + 0.5F) * rainLevel;
                    cursor.set(bx, lightY, bz);
                    int light = LevelRenderer.getLightColor(level, cursor);
                    int blockLight = (((light >> 16 & 65535) * 3 + 240) / 4);
                    int skyLight = (((light & 65535) * 3 + 240) / 4);
                    buffer.addVertex(
                                    (float) ((double) bx - camX - halfWidth + 0.5),
                                    (float) ((double) topY - camY),
                                    (float) ((double) bz - camZ - halfDepth + 0.5))
                            .setUv(0.0F + driftU, (float) bottomY * 0.25F + scrollV + driftV)
                            .setColor(1.0F, 1.0F, 1.0F, alpha)
                            .setUv2(skyLight, blockLight);
                    buffer.addVertex(
                                    (float) ((double) bx - camX + halfWidth + 0.5),
                                    (float) ((double) topY - camY),
                                    (float) ((double) bz - camZ + halfDepth + 0.5))
                            .setUv(1.0F + driftU, (float) bottomY * 0.25F + scrollV + driftV)
                            .setColor(1.0F, 1.0F, 1.0F, alpha)
                            .setUv2(skyLight, blockLight);
                    buffer.addVertex(
                                    (float) ((double) bx - camX + halfWidth + 0.5),
                                    (float) ((double) bottomY - camY),
                                    (float) ((double) bz - camZ + halfDepth + 0.5))
                            .setUv(1.0F + driftU, (float) topY * 0.25F + scrollV + driftV)
                            .setColor(1.0F, 1.0F, 1.0F, alpha)
                            .setUv2(skyLight, blockLight);
                    buffer.addVertex(
                                    (float) ((double) bx - camX - halfWidth + 0.5),
                                    (float) ((double) bottomY - camY),
                                    (float) ((double) bz - camZ - halfDepth + 0.5))
                            .setUv(0.0F + driftU, (float) topY * 0.25F + scrollV + driftV)
                            .setColor(1.0F, 1.0F, 1.0F, alpha)
                            .setUv2(skyLight, blockLight);
                }
            }
        }

        if (batch != BATCH_NONE) {
            BufferUploader.drawWithShader(buffer.buildOrThrow());
        }

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        lightTexture.turnOffLightLayer();
    }
}
