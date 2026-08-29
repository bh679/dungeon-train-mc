package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

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

    /** How far above the camera a rising drop may find the ceiling it breaks against. */
    private static final int CONTACT_SCAN_HEIGHT = 10;

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

    /**
     * The band's replacement for {@code LevelRenderer#tickRain} — the splash particles and rain
     * ambience that go with the sheets above.
     *
     * <p>Vanilla drops its splashes on the <b>top</b> face of the block under the camera, found through
     * the {@code MOTION_BLOCKING} heightmap. In the band the rain rises, so the surface it breaks
     * against is the <b>underside</b> of the terrain hanging overhead: the contact block is found by
     * scanning up from the camera, and the particle is placed on that block's bottom face. The drops
     * themselves are turned around by {@code WaterDropParticleUpsideDownMixin}.</p>
     *
     * <p>The particle budget, the {@link ParticleStatus} tiers and the lava/magma/campfire steam
     * substitution are vanilla's. The sound is always {@link SoundEvents#WEATHER_RAIN}: vanilla's
     * {@code WEATHER_RAIN_ABOVE} variant is for rain landing on a roof over your head, which is exactly
     * where all of this band's rain lands, so picking it would muffle every storm.</p>
     *
     * @return the updated {@code rainSoundTime} counter, to be written back to the renderer
     */
    public static int tickRain(Minecraft minecraft, ClientLevel level, Camera camera,
                               int ticks, int rainSoundTime) {
        float intensity = level.getRainLevel(1.0F) / (Minecraft.useFancyGraphics() ? 1.0F : 2.0F);
        if (intensity <= 0.0F) return rainSoundTime;

        RandomSource random = RandomSource.create((long) ticks * 312987231L);
        BlockPos camPos = BlockPos.containing(camera.getPosition());
        ParticleStatus particles = minecraft.options.particles().get();
        int attempts = (int) (100.0F * intensity * intensity) / (particles == ParticleStatus.DECREASED ? 2 : 1);
        BlockPos lastContact = null;

        for (int attempt = 0; attempt < attempts; attempt++) {
            BlockPos contact = findCeiling(level,
                    camPos.getX() + random.nextInt(21) - 10,
                    camPos.getY(),
                    camPos.getZ() + random.nextInt(21) - 10);
            if (contact == null) continue;

            Biome biome = level.getBiome(contact).value();
            if (biome.getPrecipitationAt(contact) != Biome.Precipitation.RAIN) continue;

            lastContact = contact;
            if (particles == ParticleStatus.MINIMAL) break;

            double offsetX = random.nextDouble();
            double offsetZ = random.nextDouble();
            BlockState state = level.getBlockState(contact);
            FluidState fluid = level.getFluidState(contact);
            VoxelShape shape = state.getCollisionShape(level, contact);
            double underside = shape.min(Direction.Axis.Y, offsetX, offsetZ);
            if (!Double.isFinite(underside)) underside = 0.0;
            ParticleOptions particle = !fluid.is(FluidTags.LAVA)
                    && !state.is(Blocks.MAGMA_BLOCK)
                    && !CampfireBlock.isLitCampfire(state)
                    ? ParticleTypes.RAIN
                    : ParticleTypes.SMOKE;
            level.addParticle(particle,
                    (double) contact.getX() + offsetX,
                    (double) contact.getY() + underside,
                    (double) contact.getZ() + offsetZ,
                    0.0, 0.0, 0.0);
        }

        if (lastContact != null && random.nextInt(3) < rainSoundTime++) {
            rainSoundTime = 0;
            level.playLocalSound(lastContact, SoundEvents.WEATHER_RAIN, SoundSource.WEATHER, 0.2F, 1.0F, false);
        }
        return rainSoundTime;
    }

    /**
     * The block whose underside the rain breaks against in this column: the first one with a collision
     * shape at or above the camera, within {@link #CONTACT_SCAN_HEIGHT}. {@code null} if the column is
     * open that far up — the flipped counterpart of vanilla giving up when the heightmap is out of
     * range. This scan replaces the heightmap lookup, which in-band only ever finds the bedrock lid far
     * overhead and so rejects every column.
     */
    private static BlockPos findCeiling(ClientLevel level, int x, int camBlockY, int z) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int top = Math.min(camBlockY + CONTACT_SCAN_HEIGHT, level.getMaxBuildHeight() - 1);
        for (int y = camBlockY; y <= top; y++) {
            cursor.set(x, y, z);
            if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty()) {
                return cursor.immutable();
            }
        }
        return null;
    }
}
