package games.brennan.dungeontrain.worldgen.legacy.classic;

import games.brennan.dungeontrain.worldgen.legacy.noise.LegacyMath;

import java.util.Random;

/**
 * The level-wide passes Classic 0.30 ran alongside its terrain — ore veins before the flood, then flowers,
 * mushrooms and trees ("Planting") at the end — which Moderner Beta's Classic port leaves to modern
 * features. Written after the shape of Classic's own level generator (clustered random walks across the
 * whole level, the small round oak), with counts tuned to the 256 × 256 × 64 level; an approximation, not
 * a byte-exact port. Works on {@link ClassicTerrain}'s level array, drawing from the same level
 * {@link Random}.
 */
final class ClassicPlanting {

    private static final int W = ClassicTerrain.W;
    private static final int L = ClassicTerrain.L;
    private static final int H = ClassicTerrain.H;

    private ClassicPlanting() {}

    // ---- ores ---------------------------------------------------------------------------------------

    /** Coal high and common, iron lower, gold deep and rare — veins carved into stone. */
    static void ores(Random random, byte[] blocks) {
        vein(random, blocks, ClassicBlocks.COAL_ORE, 90, 10, H * 4 / 5);
        vein(random, blocks, ClassicBlocks.IRON_ORE, 70, 8, H * 3 / 5);
        vein(random, blocks, ClassicBlocks.GOLD_ORE, 50, 6, H * 2 / 5);
    }

    private static void vein(Random random, byte[] blocks, byte ore, int percent, int length, int maxY) {
        int count = W * L * H / 256 / 64 * percent / 100;
        for (int i = 0; i < count; i++) {
            float x = random.nextFloat() * W;
            float y = random.nextFloat() * H;
            float z = random.nextFloat() * L;
            if (y > maxY) continue;
            int steps = (int) ((random.nextFloat() + random.nextFloat()) * length);
            float yaw = random.nextFloat() * (float) Math.PI * 2.0F;
            float dYaw = 0.0F;
            float pitch = random.nextFloat() * (float) Math.PI * 2.0F;
            float dPitch = 0.0F;
            for (int s = 0; s < steps; s++) {
                x += LegacyMath.sin(yaw) * LegacyMath.cos(pitch);
                z += LegacyMath.cos(yaw) * LegacyMath.cos(pitch);
                y += LegacyMath.sin(pitch);
                yaw += dYaw * 0.2F;
                dYaw = dYaw * 0.9F + (random.nextFloat() - random.nextFloat());
                pitch = (pitch + dPitch * 0.5F) * 0.5F;
                dPitch = dPitch * 0.9F + (random.nextFloat() - random.nextFloat());
                float radius = LegacyMath.sin(s * (float) Math.PI / steps) * 1.2F + 1.0F;
                ClassicTerrain.fillOblateSpheroid(blocks, x, y, z, radius, ore);
            }
        }
    }

    // ---- planting -----------------------------------------------------------------------------------

    static void plant(Random random, int[] heightmap, byte[] blocks) {
        flowers(random, heightmap, blocks, ClassicBlocks.DANDELION);
        flowers(random, heightmap, blocks, ClassicBlocks.ROSE);
        mushrooms(random, heightmap, blocks, ClassicBlocks.BROWN_MUSHROOM);
        mushrooms(random, heightmap, blocks, ClassicBlocks.RED_MUSHROOM);
        trees(random, heightmap, blocks);
    }

    /** Flower patches: short random walks from a spot, a flower on every open grass block reached. */
    private static void flowers(Random random, int[] heightmap, byte[] blocks, byte flower) {
        int patches = W * L / 3000;
        for (int i = 0; i < patches; i++) {
            int x = random.nextInt(W);
            int z = random.nextInt(L);
            for (int j = 0; j < 10; j++) {
                int fx = x;
                int fz = z;
                for (int k = 0; k < 5; k++) {
                    fx += random.nextInt(4) - random.nextInt(4);
                    fz += random.nextInt(4) - random.nextInt(4);
                    if (fx < 0 || fx >= W || fz < 0 || fz >= L) continue;
                    int y = heightmap[fx + fz * W] + 1;
                    if (y < H && blocks[ClassicLevel.index(fx, y, fz)] == ClassicBlocks.AIR
                            && blocks[ClassicLevel.index(fx, y - 1, fz)] == ClassicBlocks.GRASS) {
                        blocks[ClassicLevel.index(fx, y, fz)] = flower;
                    }
                }
            }
        }
    }

    /** Mushroom patches: in the dark only — cave floors of stone below the surface. */
    private static void mushrooms(Random random, int[] heightmap, byte[] blocks, byte mushroom) {
        int patches = W * L * H / 100_000;
        for (int i = 0; i < patches; i++) {
            int x = random.nextInt(W);
            int y = random.nextInt(H);
            int z = random.nextInt(L);
            for (int j = 0; j < 20; j++) {
                int mx = x;
                int my = y;
                int mz = z;
                for (int k = 0; k < 5; k++) {
                    mx += random.nextInt(4) - random.nextInt(4);
                    my += random.nextInt(2) - random.nextInt(2);
                    mz += random.nextInt(4) - random.nextInt(4);
                    if (!ClassicTerrain.inLevel(mx, my, mz) || my < 1) continue;
                    if (my >= heightmap[mx + mz * W]) continue;
                    if (blocks[ClassicLevel.index(mx, my, mz)] == ClassicBlocks.AIR
                            && blocks[ClassicLevel.index(mx, my - 1, mz)] == ClassicBlocks.STONE) {
                        blocks[ClassicLevel.index(mx, my, mz)] = mushroom;
                    }
                }
            }
        }
    }

    /** Tree clusters: many walks out from a centre, a quarter of the steps trying a tree. */
    private static void trees(Random random, int[] heightmap, byte[] blocks) {
        int clusters = W * L / 4000;
        for (int i = 0; i < clusters; i++) {
            int x = random.nextInt(W);
            int z = random.nextInt(L);
            for (int j = 0; j < 20; j++) {
                int tx = x;
                int tz = z;
                for (int k = 0; k < 20; k++) {
                    tx += random.nextInt(6) - random.nextInt(6);
                    tz += random.nextInt(6) - random.nextInt(6);
                    if (tx >= 0 && tx < W && tz >= 0 && tz < L && random.nextFloat() < 0.25F) {
                        growTree(random, blocks, tx, heightmap[tx + tz * W] + 1, tz);
                    }
                }
            }
        }
    }

    /** The small round oak: 4–6 log trunk under a two-wide leaf skirt and a one-wide top. */
    static boolean growTree(Random random, byte[] blocks, int x, int y, int z) {
        int height = random.nextInt(3) + 4;
        if (y < 1 || y + height + 1 > H) return false;
        for (int ty = y; ty <= y + height + 1; ty++) {
            int r = ty == y ? 0 : 1;
            if (ty >= y + 1 + height - 2) r = 2;
            for (int tx = x - r; tx <= x + r; tx++) {
                for (int tz = z - r; tz <= z + r; tz++) {
                    if (!ClassicTerrain.inLevel(tx, ty, tz)) {
                        if (ty < H) return false;
                        continue;
                    }
                    if (blocks[ClassicLevel.index(tx, ty, tz)] != ClassicBlocks.AIR) return false;
                }
            }
        }
        if (blocks[ClassicLevel.index(x, y - 1, z)] != ClassicBlocks.GRASS) return false;
        blocks[ClassicLevel.index(x, y - 1, z)] = ClassicBlocks.DIRT;
        for (int ty = y - 3 + height; ty <= y + height; ty++) {
            int dy = ty - (y + height);
            int r = 1 - dy / 2;
            for (int tx = x - r; tx <= x + r; tx++) {
                for (int tz = z - r; tz <= z + r; tz++) {
                    boolean corner = Math.abs(tx - x) == r && Math.abs(tz - z) == r;
                    if ((!corner || random.nextInt(2) != 0 && dy != 0) && ClassicTerrain.inLevel(tx, ty, tz)
                            && blocks[ClassicLevel.index(tx, ty, tz)] == ClassicBlocks.AIR) {
                        blocks[ClassicLevel.index(tx, ty, tz)] = ClassicBlocks.LEAVES;
                    }
                }
            }
        }
        for (int t = 0; t < height; t++) {
            blocks[ClassicLevel.index(x, y + t, z)] = ClassicBlocks.LOG;
        }
        return true;
    }
}
