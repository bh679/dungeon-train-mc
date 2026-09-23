/*
 * Terrain passes adapted from Moderner Beta (https://github.com/Nostalgica-Reverie/moderner-beta),
 * world/chunk/provider/ChunkProviderClassic030.java + api/world/chunk/ChunkProviderFinite.java.
 * Copyright (c) 2021 B3spectacled. MIT License — see THIRD_PARTY_NOTICES.md.
 * Restored to Classic 0.30's own 256×256×64 level; the planting and ore passes Moderner Beta leaves out
 * are in ClassicPlanting.
 */
package games.brennan.dungeontrain.worldgen.legacy.classic;

import games.brennan.dungeontrain.worldgen.legacy.noise.PerlinOctaveNoiseCombined;
import games.brennan.dungeontrain.worldgen.legacy.noise.LegacyMath;
import games.brennan.dungeontrain.worldgen.legacy.noise.PerlinOctaveNoise;

import java.util.Random;

/**
 * Classic 0.30's finite level generator, as pure Java. Classic built the whole level at once in a fixed
 * order of passes — Raising, Eroding, Soiling, Carving, Watering, Melting, Growing, Planting — and so does
 * {@link #generate}. Every pass draws from the one level {@link Random} in that order, so the same level
 * seed always rebuilds the same level. All state is local to the call: thread-safe.
 */
public final class ClassicTerrain {

    // Moderner Beta's Indev/Classic defaults with its Classic 0.30 preset applied.
    private static final double NOISE_SCALE = 1.3D;
    private static final double SELECTOR_SCALE = 1.0D;
    private static final double MIN_HEIGHT_DAMP = 6.0D;
    private static final double MIN_HEIGHT_BOOST = -4.0D;
    private static final double MAX_HEIGHT_DAMP = 5.0D;
    private static final double MAX_HEIGHT_BOOST = 6.0D;
    private static final double HEIGHT_UNDER_DAMP = 1.25D;
    private static final int CAVE_RARITY = 8192;
    private static final float CAVE_RADIUS = 1.0F;
    private static final double SAND_BEACH_THRESHOLD = 8.0D;
    private static final double GRAVEL_BEACH_THRESHOLD = 12.0D;
    private static final int WATER_RARITY = 8000;
    private static final int LAVA_RARITY = 20000;

    static final int W = ClassicLevel.WIDTH;
    static final int L = ClassicLevel.LENGTH;
    static final int H = ClassicLevel.HEIGHT;
    static final int WATER = ClassicLevel.WATER_LEVEL;

    private ClassicTerrain() {}

    /** Build the level for {@code levelSeed}. */
    public static ClassicLevel generate(long levelSeed) {
        Random random = new Random(levelSeed);
        byte[] blocks = new byte[W * L * H];
        int[] heightmap = new int[W * L];
        raise(random, heightmap);
        erode(random, heightmap);
        soil(random, heightmap, blocks);
        carve(random, blocks);
        ClassicPlanting.ores(random, blocks);
        water(random, blocks);
        melt(random, blocks);
        grow(random, heightmap, blocks);
        ClassicPlanting.plant(random, heightmap, blocks);
        return new ClassicLevel(blocks);
    }

    // ---- level array access (coordinates clamp to the level, as the original's did) ----------------

    static byte get(byte[] blocks, int x, int y, int z) {
        return blocks[ClassicLevel.index(clamp(x, W), clamp(y, H), clamp(z, L))];
    }

    static void set(byte[] blocks, int x, int y, int z, byte block) {
        blocks[ClassicLevel.index(clamp(x, W), clamp(y, H), clamp(z, L))] = block;
    }

    static boolean inLevel(int x, int y, int z) {
        return x >= 0 && x < W && y >= 0 && y < H && z >= 0 && z < L;
    }

    private static int clamp(int v, int size) {
        return v < 0 ? 0 : Math.min(v, size - 1);
    }

    // ---- Raising: the two-noise heightmap ------------------------------------------------------------

    private static void raise(Random random, int[] heightmap) {
        PerlinOctaveNoiseCombined low = new PerlinOctaveNoiseCombined(new PerlinOctaveNoise(random, 8, false), new PerlinOctaveNoise(random, 8, false));
        PerlinOctaveNoiseCombined high = new PerlinOctaveNoiseCombined(new PerlinOctaveNoise(random, 8, false), new PerlinOctaveNoise(random, 8, false));
        PerlinOctaveNoise selector = new PerlinOctaveNoise(random, 6, false);
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < L; z++) {
                double heightLow = low.sample(x * NOISE_SCALE, z * NOISE_SCALE) / MIN_HEIGHT_DAMP + MIN_HEIGHT_BOOST;
                double heightHigh = high.sample(x * NOISE_SCALE, z * NOISE_SCALE) / MAX_HEIGHT_DAMP + MAX_HEIGHT_BOOST;
                if (selector.sampleXY(x * SELECTOR_SCALE, z * SELECTOR_SCALE) / 8.0D > 0.0D) heightHigh = heightLow;
                double height = Math.max(heightLow, heightHigh) / 2.0D;
                if (height < 0.0D) height /= HEIGHT_UNDER_DAMP;
                heightmap[x + z * W] = (int) height;
            }
        }
    }

    // ---- Eroding: flattens patches to even heights ---------------------------------------------------

    private static void erode(Random random, int[] heightmap) {
        PerlinOctaveNoiseCombined selector = new PerlinOctaveNoiseCombined(new PerlinOctaveNoise(random, 8, false), new PerlinOctaveNoise(random, 8, false));
        PerlinOctaveNoiseCombined parity = new PerlinOctaveNoiseCombined(new PerlinOctaveNoise(random, 8, false), new PerlinOctaveNoise(random, 8, false));
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < L; z++) {
                double erodeSelector = selector.sample(x << 1, z << 1) / 8.0D;
                int erodeNoise = parity.sample(x << 1, z << 1) > 0.0D ? 1 : 0;
                if (erodeSelector > 2.0D) {
                    int h = heightmap[x + z * W];
                    heightmap[x + z * W] = ((h - erodeNoise) / 2 << 1) + erodeNoise;
                }
            }
        }
    }

    // ---- Soiling: stone under a dirt layer of noisy thickness, lava along the bottom -----------------

    private static void soil(Random random, int[] heightmap, byte[] blocks) {
        PerlinOctaveNoise dirtNoise = new PerlinOctaveNoise(random, 8, false);
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < L; z++) {
                int dirtThickness = (int) (dirtNoise.sampleXY(x, z) / 24.0D) - 4;
                int dirtThreshold = heightmap[x + z * W] + WATER;
                int stoneThreshold = dirtThickness + dirtThreshold;
                int top = Math.max(dirtThreshold, stoneThreshold);
                heightmap[x + z * W] = Math.max(1, Math.min(top, H - 2));
                for (int y = 0; y < H; y++) {
                    byte block = ClassicBlocks.AIR;
                    if (y <= dirtThreshold) block = ClassicBlocks.DIRT;
                    if (y <= stoneThreshold) block = ClassicBlocks.STONE;
                    if (y == 1) block = ClassicBlocks.LAVA;
                    if (y == 0) block = ClassicBlocks.BEDROCK;
                    blocks[ClassicLevel.index(x, y, z)] = block;
                }
            }
        }
    }

    // ---- Carving: worm caves of oblate spheroids -----------------------------------------------------

    private static void carve(Random random, byte[] blocks) {
        int caveCount = W * L * H / CAVE_RARITY;
        for (int i = 0; i < caveCount; i++) {
            float caveX = random.nextFloat() * W;
            float caveY = random.nextFloat() * H;
            float caveZ = random.nextFloat() * L;
            int caveLen = (int) ((random.nextFloat() + random.nextFloat()) * 200.0F);
            float theta = random.nextFloat() * (float) Math.PI * 2.0F;
            float deltaTheta = 0.0F;
            float phi = random.nextFloat() * (float) Math.PI * 2.0F;
            float deltaPhi = 0.0F;
            float caveRadius = random.nextFloat() * random.nextFloat() * CAVE_RADIUS;
            for (int len = 0; len < caveLen; len++) {
                caveX += LegacyMath.sin(theta) * LegacyMath.cos(phi);
                caveZ += LegacyMath.cos(theta) * LegacyMath.cos(phi);
                caveY += LegacyMath.sin(phi);
                theta = theta + deltaTheta * 0.2F;
                deltaTheta = deltaTheta * 0.9F + (random.nextFloat() - random.nextFloat());
                phi = phi * 0.5F + deltaPhi * 0.25F;
                deltaPhi = deltaPhi * 0.75F + (random.nextFloat() - random.nextFloat());
                if (random.nextFloat() >= 0.25F) {
                    float centerX = caveX + (random.nextFloat() * 4.0F - 2.0F) * 0.2F;
                    float centerY = caveY + (random.nextFloat() * 4.0F - 2.0F) * 0.2F;
                    float centerZ = caveZ + (random.nextFloat() * 4.0F - 2.0F) * 0.2F;
                    float radius = (H - centerY) / H;
                    radius = 1.2F + (radius * 3.5F + 1.0F) * caveRadius;
                    radius = radius * LegacyMath.sin(len * (float) Math.PI / caveLen);
                    fillOblateSpheroid(blocks, centerX, centerY, centerZ, radius, ClassicBlocks.AIR);
                }
            }
        }
    }

    /** Replace stone inside the spheroid (Y squashed ×2) with {@code fill}. */
    static void fillOblateSpheroid(byte[] blocks, float cx, float cy, float cz, float radius, byte fill) {
        for (int x = (int) (cx - radius); x < (int) (cx + radius); x++) {
            for (int y = (int) (cy - radius); y < (int) (cy + radius); y++) {
                for (int z = (int) (cz - radius); z < (int) (cz + radius); z++) {
                    float dx = x - cx;
                    float dy = y - cy;
                    float dz = z - cz;
                    if (dx * dx + dy * dy * 2.0F + dz * dz < radius * radius && inLevel(x, y, z)
                            && blocks[ClassicLevel.index(x, y, z)] == ClassicBlocks.STONE) {
                        blocks[ClassicLevel.index(x, y, z)] = fill;
                    }
                }
            }
        }
    }

    // ---- Watering / Melting: flood fills from the level edge and random sources ----------------------

    private static void water(Random random, byte[] blocks) {
        IntQueue queue = new IntQueue();
        for (int x = 0; x < W; x++) {
            flood(blocks, queue, x, WATER - 1, 0, ClassicBlocks.WATER);
            flood(blocks, queue, x, WATER - 1, L - 1, ClassicBlocks.WATER);
        }
        for (int z = 0; z < L; z++) {
            flood(blocks, queue, W - 1, WATER - 1, z, ClassicBlocks.WATER);
            flood(blocks, queue, 0, WATER - 1, z, ClassicBlocks.WATER);
        }
        int sources = W * L / WATER_RARITY;
        for (int i = 0; i < sources; i++) {
            int x = random.nextInt(W);
            int z = random.nextInt(L);
            int y = (WATER - 1) - random.nextInt(2);
            flood(blocks, queue, x, y, z, ClassicBlocks.WATER);
        }
    }

    private static void melt(Random random, byte[] blocks) {
        IntQueue queue = new IntQueue();
        int sources = W * L / LAVA_RARITY;
        for (int i = 0; i < sources; i++) {
            int x = random.nextInt(W);
            int z = random.nextInt(L);
            int y = (int) ((WATER - 3) * random.nextFloat() * random.nextFloat());
            flood(blocks, queue, x, y, z, ClassicBlocks.LAVA);
        }
    }

    /**
     * Fill the air region connected to {@code (x, y, z)} — spreading down and sideways, never up — with
     * {@code fill}. Cells are marked as they are queued, so the queue never holds a cell twice.
     */
    static void flood(byte[] blocks, IntQueue queue, int x, int y, int z, byte fill) {
        if (!inLevel(x, y, z) || blocks[ClassicLevel.index(x, y, z)] != ClassicBlocks.AIR) return;
        queue.clear();
        blocks[ClassicLevel.index(x, y, z)] = fill;
        queue.add(ClassicLevel.index(x, y, z));
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int cy = i % H;
            int cz = (i / H) % L;
            int cx = i / (H * L);
            tryFlood(blocks, queue, cx, cy - 1, cz, fill);
            tryFlood(blocks, queue, cx - 1, cy, cz, fill);
            tryFlood(blocks, queue, cx + 1, cy, cz, fill);
            tryFlood(blocks, queue, cx, cy, cz - 1, fill);
            tryFlood(blocks, queue, cx, cy, cz + 1, fill);
        }
    }

    private static void tryFlood(byte[] blocks, IntQueue queue, int x, int y, int z, byte fill) {
        if (!inLevel(x, y, z)) return;
        int i = ClassicLevel.index(x, y, z);
        if (blocks[i] != ClassicBlocks.AIR) return;
        blocks[i] = fill;
        queue.add(i);
    }

    // ---- Growing: grass, and sand / gravel beaches --------------------------------------------------

    private static void grow(Random random, int[] heightmap, byte[] blocks) {
        PerlinOctaveNoise sandNoise = new PerlinOctaveNoise(random, 8, false);
        PerlinOctaveNoise gravelNoise = new PerlinOctaveNoise(random, 8, false);
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < L; z++) {
                boolean sand = sandNoise.sampleXY(x, z) > SAND_BEACH_THRESHOLD;
                boolean gravel = gravelNoise.sampleXY(x, z) > GRAVEL_BEACH_THRESHOLD;
                int h = heightmap[x + z * W];
                byte above = get(blocks, x, h + 1, z);
                // Classic 0.30: sand only on dry shore, gravel only under water.
                sand &= h <= WATER - 1 && above == ClassicBlocks.AIR;
                gravel &= h <= WATER - 1 && above == ClassicBlocks.WATER;
                byte surface = sand ? ClassicBlocks.SAND
                        : gravel ? ClassicBlocks.GRAVEL
                        : above == ClassicBlocks.WATER ? ClassicBlocks.DIRT
                        : ClassicBlocks.GRASS;
                set(blocks, x, h, z, surface);
            }
        }
    }

    /** Growable FIFO of packed level indices for the flood fills. */
    static final class IntQueue {
        private int[] items = new int[1 << 14];
        private int head;
        private int tail;

        void clear() {
            head = 0;
            tail = 0;
        }

        boolean isEmpty() {
            return head == tail;
        }

        void add(int v) {
            if (tail == items.length) {
                int size = tail - head;
                if (head > items.length / 2) {
                    System.arraycopy(items, head, items, 0, size);
                } else {
                    int[] grown = new int[items.length * 2];
                    System.arraycopy(items, head, grown, 0, size);
                    items = grown;
                }
                head = 0;
                tail = size;
            }
            items[tail++] = v;
        }

        int poll() {
            return items[head++];
        }
    }
}
