/*
 * Terrain adapted from Moderner Beta (https://github.com/Nostalgica-Reverie/moderner-beta),
 * world/chunk/provider/ChunkProviderIndev.java + api/world/chunk/ChunkProviderFinite.java.
 * Copyright (c) 2021 B3spectacled. MIT License — see THIRD_PARTY_NOTICES.md.
 * Trimmed to Indev's Floating level type on the Normal theme, with the level stored nibble-packed.
 */
package games.brennan.dungeontrain.worldgen.legacy.indev;

import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBlocks;
import games.brennan.dungeontrain.worldgen.legacy.noise.LegacyMath;
import games.brennan.dungeontrain.worldgen.legacy.noise.PerlinOctaveNoise;
import games.brennan.dungeontrain.worldgen.legacy.noise.PerlinOctaveNoiseCombined;

import java.util.Random;

/**
 * One finite Indev <b>Floating</b> level: {@link #WIDTH}×{@link #HEIGHT}×{@link #LENGTH} blocks of stacked
 * island layers over void, generated whole in the constructor exactly as Indev did — one shared
 * {@link Random} walked through every layer's raise / erode / soil / grow passes, then level-wide caves,
 * then grass planted on exposed dirt. Floating levels have no water, lava, bedrock or border.
 *
 * <p>Generation is inherently whole-level (caves wander across it on the one {@code Random}), so the level
 * cannot be produced chunk by chunk; {@link IndevLevels} builds each one once and chunks read slices of it.
 * Immutable after construction and safe to share between worldgen workers.</p>
 *
 * <p>Blocks are stored as {@link BetaBlocks} ids (all below 16) packed two per byte.</p>
 */
public final class IndevFloatingLevel {

    public static final int WIDTH = 256;
    public static final int LENGTH = 256;
    public static final int HEIGHT = 256;
    /** Island layers: Indev stacks one every 48 blocks down from {@code HEIGHT - 32}. */
    public static final int LAYERS = (HEIGHT - 64) / 48 + 1;

    // Moderner Beta's Indev defaults.
    private static final float MIN_HEIGHT_DAMP = 6.0f;
    private static final float MIN_HEIGHT_BOOST = -4.0f;
    private static final float MAX_HEIGHT_DAMP = 5.0f;
    private static final float MAX_HEIGHT_BOOST = 6.0f;
    private static final float HEIGHT_UNDER_DAMP = 1.25f;
    private static final float SELECTOR_SCALE = 1.0f;
    private static final int CAVE_RARITY = 8192;
    private static final float CAVE_RADIUS = 1.0f;
    private static final double SAND_BEACH_THRESHOLD = 8.0;
    private static final double GRAVEL_BEACH_THRESHOLD = 12.0;

    private final byte[] packed;

    /** Generate the level for {@code seed} (the tile's own seed — see {@link IndevLevels#tileSeed}). */
    public IndevFloatingLevel(long seed) {
        Builder b = new Builder(new Random(seed));
        b.generate();
        this.packed = pack(b.blocks);
    }

    /** The {@link BetaBlocks} id at level coordinates; air outside the level. */
    public byte block(int x, int y, int z) {
        if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT || z < 0 || z >= LENGTH) return BetaBlocks.AIR;
        int i = index(x, y, z);
        int b = packed[i >> 1];
        return (byte) ((i & 1) == 0 ? b & 0x0F : (b >> 4) & 0x0F);
    }

    static int index(int x, int y, int z) {
        return (x * LENGTH + z) * HEIGHT + y;
    }

    static byte[] pack(byte[] blocks) {
        byte[] out = new byte[(blocks.length + 1) >> 1];
        for (int i = 0; i < blocks.length; i++) {
            int v = blocks[i] & 0x0F;
            out[i >> 1] |= (byte) ((i & 1) == 0 ? v : v << 4);
        }
        return out;
    }

    /** The mutable generation pass — the original's provider fields, discarded once packed. */
    private static final class Builder {
        private final Random random;
        private final byte[] blocks = new byte[WIDTH * LENGTH * HEIGHT];
        private final int[] heightmap = new int[WIDTH * LENGTH];
        private int waterLevel;

        private PerlinOctaveNoiseCombined minHeightNoise;
        private PerlinOctaveNoiseCombined maxHeightNoise;
        private PerlinOctaveNoise mainHeightNoise;
        private PerlinOctaveNoise dirtNoise;
        private PerlinOctaveNoise floatingNoise;
        private PerlinOctaveNoiseCombined erodeNoise0;
        private PerlinOctaveNoiseCombined erodeNoise1;
        private PerlinOctaveNoise sandNoise;
        private PerlinOctaveNoise gravelNoise;

        Builder(Random random) {
            this.random = random;
        }

        void generate() {
            for (int layer = 0; layer < LAYERS; layer++) {
                // Each layer lowers the water level and re-rolls every noise stack off the shared Random,
                // in the original's construction order (the island noise is built but unused off-island).
                waterLevel = HEIGHT - 32 - layer * 48;
                minHeightNoise = combined();
                maxHeightNoise = combined();
                mainHeightNoise = octaves(6);
                octaves(2); // islandOctaveNoise — Island type only, but it still consumes the Random
                dirtNoise = octaves(8);
                floatingNoise = octaves(8);
                erodeNoise0 = combined();
                erodeNoise1 = combined();
                sandNoise = octaves(8);
                gravelNoise = octaves(8);
                raise();
                erode();
                soil();
                grow();
            }
            carve();
            plant();
        }

        private PerlinOctaveNoise octaves(int n) {
            return new PerlinOctaveNoise(random, n, false);
        }

        private PerlinOctaveNoiseCombined combined() {
            return new PerlinOctaveNoiseCombined(octaves(8), octaves(8));
        }

        private byte get(int x, int y, int z) {
            x = clamp(x, WIDTH);
            y = clamp(y, HEIGHT);
            z = clamp(z, LENGTH);
            return blocks[index(x, y, z)];
        }

        private void set(int x, int y, int z, byte block) {
            x = clamp(x, WIDTH);
            y = clamp(y, HEIGHT);
            z = clamp(z, LENGTH);
            blocks[index(x, y, z)] = block;
        }

        private static int clamp(int v, int size) {
            return v < 0 ? 0 : Math.min(v, size - 1);
        }

        /** "Raising": the base heightmap. */
        private void raise() {
            for (int x = 0; x < WIDTH; x++) {
                for (int z = 0; z < LENGTH; z++) {
                    double low = minHeightNoise.sample(x * 1.3f, z * 1.3f) / MIN_HEIGHT_DAMP + MIN_HEIGHT_BOOST;
                    double selector = mainHeightNoise.sampleXY(x * SELECTOR_SCALE, z * SELECTOR_SCALE) / 8.0;
                    // The original samples "high" and then discards it when the selector picks "low";
                    // sampling draws nothing from the Random, so skipping it changes nothing.
                    double high = selector > 0.0
                            ? low
                            : maxHeightNoise.sample(x * 1.3f, z * 1.3f) / MAX_HEIGHT_DAMP + MAX_HEIGHT_BOOST;
                    double h = Math.max(low, high) / 2.0;
                    if (h < 0.0) h /= HEIGHT_UNDER_DAMP;
                    heightmap[x + z * WIDTH] = (int) h;
                }
            }
        }

        /** "Eroding": terraces the heightmap to even steps where the selector is high. */
        private void erode() {
            for (int x = 0; x < WIDTH; x++) {
                for (int z = 0; z < LENGTH; z++) {
                    double selector = erodeNoise0.sample(x << 1, z << 1) / 8.0;
                    if (selector > 2.0) {
                        // Only terraced columns read the second stack (the original samples it for all).
                        int bit = erodeNoise1.sample(x << 1, z << 1) > 0.0 ? 1 : 0;
                        int h = heightmap[x + z * WIDTH];
                        heightmap[x + z * WIDTH] = ((h - bit) / 2 << 1) + bit;
                    }
                }
            }
        }

        /** "Soiling": stone and dirt up to the heightmap, with the island bottoms rounded off into air. */
        private void soil() {
            for (int x = 0; x < WIDTH; x++) {
                double nx = Math.abs((x / (WIDTH - 1.0) - 0.5) * 2.0);
                for (int z = 0; z < LENGTH; z++) {
                    double edge = Math.max(nx, Math.abs(z / (LENGTH - 1.0) - 0.5) * 2.0);
                    edge = edge * edge * edge;
                    int dirtThickness = (int) (dirtNoise.sampleXY(x, z) / 24.0) - 4;
                    int dirtThreshold = heightmap[x + z * WIDTH] + waterLevel;
                    int stoneThreshold = dirtThickness + dirtThreshold;
                    int h = Math.max(dirtThreshold, stoneThreshold);
                    if (h > HEIGHT - 2) h = HEIGHT - 2;
                    if (h <= 0) h = 1;
                    heightmap[x + z * WIDTH] = h;

                    double floating = floatingNoise.sampleXY(x * 2.3, z * 2.3) / 24.0;
                    // Rounds out the bottom of the terrain; toward the level rim the cut rises to the top.
                    int rounded = (int) (Math.sqrt(Math.abs(floating)) * Math.signum(floating) * 20.0) + waterLevel;
                    rounded = (int) (rounded * (1.0 - edge) + edge * HEIGHT);
                    if (rounded > waterLevel) rounded = HEIGHT;

                    // The original walks the whole column, but only [rounded, top] can come out non-air, and
                    // an air result into an air cell is a no-op — so walk just that span (same result).
                    int top = Math.min(HEIGHT - 1, Math.max(dirtThreshold, stoneThreshold));
                    int base = (x * LENGTH + z) * HEIGHT;
                    for (int y = Math.max(0, rounded); y <= top; y++) {
                        byte block = y <= stoneThreshold ? BetaBlocks.STONE : BetaBlocks.DIRT;
                        if (blocks[base + y] == BetaBlocks.AIR) blocks[base + y] = block;
                    }
                }
            }
        }

        /** "Growing": sand and gravel beaches on the layer's low ground. */
        private void grow() {
            int surfaceLevel = waterLevel - 1;
            for (int x = 0; x < WIDTH; x++) {
                for (int z = 0; z < LENGTH; z++) {
                    int h = heightmap[x + z * WIDTH];
                    // Where the floating cut emptied the column there is no ground to turn into beach —
                    // without this the pass leaves lone sand/gravel blocks hanging in the void.
                    if (h > surfaceLevel || get(x, h, z) == BetaBlocks.AIR) continue;
                    // Floating's level fluid is air, so "under fluid" and "under air" coincide.
                    if (get(x, h + 1, z) != BetaBlocks.AIR) continue;
                    // The beach noises draw nothing from the Random, so sampling only here changes nothing.
                    boolean sand = sandNoise.sampleXY(x, z) > SAND_BEACH_THRESHOLD;
                    boolean gravel = gravelNoise.sampleXY(x, z) > GRAVEL_BEACH_THRESHOLD;
                    if (sand) {
                        set(x, h, z, BetaBlocks.SAND);
                    } else if (gravel) {
                        set(x, h, z, BetaBlocks.GRAVEL);
                    }
                }
            }
        }

        /** "Carving": Indev's worm caves through stone, level-wide. */
        private void carve() {
            int caveCount = WIDTH * LENGTH * HEIGHT / CAVE_RARITY;
            for (int i = 0; i < caveCount; i++) {
                float caveX = random.nextFloat() * WIDTH;
                float caveY = random.nextFloat() * HEIGHT;
                float caveZ = random.nextFloat() * LENGTH;
                int caveLen = (int) ((random.nextFloat() + random.nextFloat()) * 200.0f);
                float theta = random.nextFloat() * (float) Math.PI * 2.0f;
                float deltaTheta = 0.0f;
                float phi = random.nextFloat() * (float) Math.PI * 2.0f;
                float deltaPhi = 0.0f;
                float caveRadius = random.nextFloat() * random.nextFloat() * CAVE_RADIUS;
                for (int len = 0; len < caveLen; len++) {
                    caveX += LegacyMath.sin(theta) * LegacyMath.cos(phi);
                    caveZ += LegacyMath.cos(theta) * LegacyMath.cos(phi);
                    caveY += LegacyMath.sin(phi);
                    theta += deltaTheta * 0.2f;
                    deltaTheta = deltaTheta * 0.9f + (random.nextFloat() - random.nextFloat());
                    phi = phi * 0.5f + deltaPhi * 0.25f;
                    deltaPhi = deltaPhi * 0.75f + (random.nextFloat() - random.nextFloat());
                    if (random.nextFloat() >= 0.25f) {
                        float cx = caveX + (random.nextFloat() * 4.0f - 2.0f) * 0.2f;
                        float cy = caveY + (random.nextFloat() * 4.0f - 2.0f) * 0.2f;
                        float cz = caveZ + (random.nextFloat() * 4.0f - 2.0f) * 0.2f;
                        float radius = (HEIGHT - cy) / HEIGHT;
                        radius = 1.2f + (radius * 3.5f + 1.0f) * caveRadius;
                        radius *= LegacyMath.sin(len * (float) Math.PI / caveLen);
                        hollow(cx, cy, cz, radius);
                    }
                }
            }
        }

        /** {@code fillOblateSpheroid} with air: clears stone only. */
        private void hollow(float cx, float cy, float cz, float radius) {
            for (int x = (int) (cx - radius); x < (int) (cx + radius); x++) {
                for (int y = (int) (cy - radius); y < (int) (cy + radius); y++) {
                    for (int z = (int) (cz - radius); z < (int) (cz + radius); z++) {
                        float dx = x - cx;
                        float dy = y - cy;
                        float dz = z - cz;
                        if (dx * dx + dy * dy * 2.0f + dz * dz < radius * radius
                                && x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT && z >= 0 && z < LENGTH
                                && blocks[index(x, y, z)] == BetaBlocks.STONE) {
                            blocks[index(x, y, z)] = BetaBlocks.AIR;
                        }
                    }
                }
            }
        }

        /** "Planting": grass on every dirt block open to the sky above it. */
        private void plant() {
            for (int x = 0; x < WIDTH; x++) {
                for (int z = 0; z < LENGTH; z++) {
                    int base = (x * LENGTH + z) * HEIGHT;
                    for (int y = 0; y < HEIGHT - 2; y++) {
                        if (blocks[base + y] == BetaBlocks.DIRT && blocks[base + y + 1] == BetaBlocks.AIR) {
                            blocks[base + y] = BetaBlocks.GRASS;
                        }
                    }
                }
            }
        }
    }
}
