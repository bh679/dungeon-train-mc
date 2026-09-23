/*
 * Tunnel geometry adapted from Moderner Beta (https://github.com/Nostalgica-Reverie/moderner-beta),
 * world/carver/BetaCaveCarver.java. Copyright (c) 2021 B3spectacled. MIT License — see
 * THIRD_PARTY_NOTICES.md. Restored to Beta's own java.util.Random seeding and in-chunk carve.
 */
package games.brennan.dungeontrain.worldgen.legacy.beta;

import games.brennan.dungeontrain.worldgen.legacy.noise.LegacyMath;

import java.util.Random;

/**
 * Beta 1.7.3's cave carver. Carves one chunk's {@code byte[]} directly (it ran inside chunk generation,
 * before decoration): every chunk within {@link #RANGE} rolls its own tunnel systems from a seed derived
 * from its coordinates, and the tunnels that pass through the target chunk are cut into it. Lava fills
 * the lowest ten blocks; tunnels never break into water. Stateless between calls (a fresh {@link Random}
 * per call), so it is thread-safe.
 */
public final class BetaCaves {

    private static final int RANGE = 8;

    private final long seed;
    private final long mulX;
    private final long mulZ;

    public BetaCaves(long seed) {
        this.seed = seed;
        Random r = new Random(seed);
        this.mulX = r.nextLong() / 2L * 2L + 1L;
        this.mulZ = r.nextLong() / 2L * 2L + 1L;
    }

    public void carve(int chunkX, int chunkZ, byte[] blocks) {
        Random rand = new Random();
        for (int sx = chunkX - RANGE; sx <= chunkX + RANGE; sx++) {
            for (int sz = chunkZ - RANGE; sz <= chunkZ + RANGE; sz++) {
                rand.setSeed((long) sx * mulX + (long) sz * mulZ ^ seed);
                startSystems(rand, sx, sz, chunkX, chunkZ, blocks);
            }
        }
    }

    private void startSystems(Random rand, int sourceX, int sourceZ, int chunkX, int chunkZ, byte[] blocks) {
        int count = rand.nextInt(rand.nextInt(rand.nextInt(40) + 1) + 1);
        if (rand.nextInt(15) != 0) count = 0;
        for (int i = 0; i < count; i++) {
            double x = sourceX * 16 + rand.nextInt(16);
            double y = rand.nextInt(rand.nextInt(120) + 8);
            double z = sourceZ * 16 + rand.nextInt(16);
            int tunnels = 1;
            if (rand.nextInt(4) == 0) {
                tunnel(rand, chunkX, chunkZ, blocks, x, y, z, 1.0F + rand.nextFloat() * 6.0F, 0.0F, 0.0F, -1, -1, 0.5D);
                tunnels += rand.nextInt(4);
            }
            for (int t = 0; t < tunnels; t++) {
                float yaw = rand.nextFloat() * 3.141593F * 2.0F;
                float pitch = (rand.nextFloat() - 0.5F) * 2.0F / 8.0F;
                float width = rand.nextFloat() * 2.0F + rand.nextFloat();
                tunnel(rand, chunkX, chunkZ, blocks, x, y, z, width, yaw, pitch, 0, 0, 1.0D);
            }
        }
    }

    private void tunnel(Random parent, int chunkX, int chunkZ, byte[] blocks, double x, double y, double z,
                        float width, float yaw, float pitch, int branch, int branchCount, double yawPitchRatio) {
        double centreX = chunkX * 16 + 8;
        double centreZ = chunkZ * 16 + 8;
        float yawDrift = 0.0F;
        float pitchDrift = 0.0F;
        Random random = new Random(parent.nextLong());
        if (branchCount <= 0) {
            int max = RANGE * 16 - 16;
            branchCount = max - random.nextInt(max / 4);
        }
        boolean room = false;
        if (branch == -1) {
            branch = branchCount / 2;
            room = true;
        }
        int splitAt = random.nextInt(branchCount / 2) + branchCount / 4;
        boolean steep = random.nextInt(6) == 0;
        for (; branch < branchCount; branch++) {
            double radiusH = 1.5D + (double) (LegacyMath.sin((float) branch * 3.141593F / (float) branchCount) * width * 1.0F);
            double radiusV = radiusH * yawPitchRatio;
            float cosPitch = LegacyMath.cos(pitch);
            float sinPitch = LegacyMath.sin(pitch);
            x += LegacyMath.cos(yaw) * cosPitch;
            y += sinPitch;
            z += LegacyMath.sin(yaw) * cosPitch;
            pitch *= steep ? 0.92F : 0.7F;
            pitch += pitchDrift * 0.1F;
            yaw += yawDrift * 0.1F;
            pitchDrift *= 0.9F;
            yawDrift *= 0.75F;
            pitchDrift += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 2.0F;
            yawDrift += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 4.0F;
            if (!room && branch == splitAt && width > 1.0F) {
                // Beta seeds the split branches from the carver-wide random, not this tunnel's own.
                tunnel(parent, chunkX, chunkZ, blocks, x, y, z, random.nextFloat() * 0.5F + 0.5F,
                        yaw - 1.570796F, pitch / 3.0F, branch, branchCount, 1.0D);
                tunnel(parent, chunkX, chunkZ, blocks, x, y, z, random.nextFloat() * 0.5F + 0.5F,
                        yaw + 1.570796F, pitch / 3.0F, branch, branchCount, 1.0D);
                return;
            }
            if (!room && random.nextInt(4) == 0) continue;
            double dx = x - centreX;
            double dz = z - centreZ;
            double remaining = branchCount - branch;
            double reach = width + 2.0F + 16.0F;
            if (dx * dx + dz * dz - remaining * remaining > reach * reach) return;
            if (x < centreX - 16.0D - radiusH * 2.0D || z < centreZ - 16.0D - radiusH * 2.0D
                    || x > centreX + 16.0D + radiusH * 2.0D || z > centreZ + 16.0D + radiusH * 2.0D) {
                continue;
            }
            carveEllipsoid(chunkX, chunkZ, blocks, x, y, z, radiusH, radiusV);
            if (room) break;
        }
    }

    private static void carveEllipsoid(int chunkX, int chunkZ, byte[] blocks, double x, double y, double z,
                                       double radiusH, double radiusV) {
        int minX = Math.max(0, LegacyMath.floor(x - radiusH) - chunkX * 16 - 1);
        int maxX = Math.min(16, LegacyMath.floor(x + radiusH) - chunkX * 16 + 1);
        int minY = Math.max(1, LegacyMath.floor(y - radiusV) - 1);
        int maxY = Math.min(120, LegacyMath.floor(y + radiusV) + 1);
        int minZ = Math.max(0, LegacyMath.floor(z - radiusH) - chunkZ * 16 - 1);
        int maxZ = Math.min(16, LegacyMath.floor(z + radiusH) - chunkZ * 16 + 1);
        if (touchesWater(blocks, minX, maxX, minY, maxY, minZ, maxZ)) return;
        for (int lx = minX; lx < maxX; lx++) {
            double rx = ((double) (lx + chunkX * 16) + 0.5D - x) / radiusH;
            for (int lz = minZ; lz < maxZ; lz++) {
                double rz = ((double) (lz + chunkZ * 16) + 0.5D - z) / radiusH;
                if (rx * rx + rz * rz >= 1.0D) continue;
                boolean hitGrass = false;
                // Beta's off-by-one: the test for row ly carves the block one above it.
                for (int ly = maxY - 1; ly >= minY; ly--) {
                    double ry = ((double) ly + 0.5D - y) / radiusV;
                    if (ry > -0.7D && rx * rx + ry * ry + rz * rz < 1.0D) {
                        int i = BetaTerrain.index(lx, ly + 1, lz);
                        byte b = blocks[i];
                        if (b == BetaBlocks.GRASS) hitGrass = true;
                        if (b == BetaBlocks.STONE || b == BetaBlocks.DIRT || b == BetaBlocks.GRASS) {
                            if (ly < 10) {
                                blocks[i] = BetaBlocks.LAVA;
                            } else {
                                blocks[i] = BetaBlocks.AIR;
                                if (hitGrass && blocks[i - 1] == BetaBlocks.DIRT) blocks[i - 1] = BetaBlocks.GRASS;
                            }
                        }
                    }
                }
            }
        }
    }

    /** Beta refuses to carve a region whose shell (or any block, for interior columns' ends) holds water. */
    private static boolean touchesWater(byte[] blocks, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        for (int lx = minX; lx < maxX; lx++) {
            for (int lz = minZ; lz < maxZ; lz++) {
                for (int ly = maxY + 1; ly >= minY - 1; ly--) {
                    if (ly < 0 || ly >= BetaTerrain.HEIGHT) continue;
                    if (blocks[BetaTerrain.index(lx, ly, lz)] == BetaBlocks.WATER) return true;
                    if (ly != minY - 1 && lx != minX && lx != maxX - 1 && lz != minZ && lz != maxZ - 1) {
                        ly = minY;
                    }
                }
            }
        }
        return false;
    }
}
