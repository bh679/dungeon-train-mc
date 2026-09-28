/*
 * Adapted from Moderner Beta (https://github.com/Nostalgica-Reverie/moderner-beta),
 * world/feature/BetaFancyOakFeature.java. Copyright (c) 2021 B3spectacled. MIT License — see
 * THIRD_PARTY_NOTICES.md. Restored Beta's own java.util.Random, its "stop at non-air, non-leaves"
 * line test, and the trunk shortening to the first obstruction.
 */
package games.brennan.dungeontrain.worldgen.legacy.beta;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/** Beta's "big tree" — the tall branching oak with foliage blobs, one in ten trees in most biomes. */
final class BetaBigTree {

    private static final byte[] AXIS = {2, 0, 0, 1, 2, 1};
    private static final int BLOB_HEIGHT = 5;
    private static final int HEIGHT_RANGE = 12;
    private static final BlockState LOG = Blocks.OAK_LOG.defaultBlockState();

    private BetaBigTree() {}

    static boolean grow(BetaWorld world, Random parent, int x, int y, int z) {
        Random rand = new Random(parent.nextLong());
        int height = 5 + rand.nextInt(HEIGHT_RANGE);
        int[] base = {x, y, z};
        BlockState below = world.get(x, y - 1, z);
        if (!below.is(Blocks.GRASS_BLOCK) && !below.is(Blocks.DIRT)) return false;
        int clear = lineLength(world, base, new int[] {x, y + height - 1, z});
        if (clear != -1) {
            if (clear < 6) return false;
            height = clear;
        }
        int trunkHeight = Math.min((int) (height * 0.618D), height - 1);
        int[][] blobs = placeBlobs(world, rand, base, height, trunkHeight);
        for (int[] blob : blobs) {
            for (int ly = blob[1]; ly < blob[1] + BLOB_HEIGHT; ly++) {
                int rel = ly - blob[1];
                float radius = rel != 0 && rel != BLOB_HEIGHT - 1 ? 3.0F : 2.0F;
                layer(world, blob[0], ly, blob[2], radius);
            }
        }
        line(world, base, new int[] {x, y + trunkHeight, z});
        for (int[] blob : blobs) {
            int[] start = {x, blob[3], z};
            if (blob[3] - y >= height * 0.2D) line(world, start, new int[] {blob[0], blob[1], blob[2]});
        }
        return true;
    }

    /** Foliage blob positions {@code [x, baseY, z, branchStartY]}; the first is the crown on the trunk. */
    private static int[][] placeBlobs(BetaWorld world, Random rand, int[] base, int height, int trunkHeight) {
        int perLayer = Math.max(1, (int) (1.382D + Math.pow(height / 13.0D, 2.0D)));
        int blobY = base[1] + height - BLOB_HEIGHT;
        int trunkTop = base[1] + trunkHeight;
        int rel = blobY - base[1];
        int[][] blobs = new int[perLayer * height][];
        blobs[0] = new int[] {base[0], blobY, base[2], trunkTop};
        int count = 1;
        blobY--;
        while (rel >= 0) {
            float dist = spread(rel, height);
            if (dist >= 0.0F) {
                for (int i = 0; i < perLayer; i++) {
                    double r = dist * (rand.nextFloat() + 0.328D);
                    double a = rand.nextFloat() * 2.0D * 3.14159D;
                    int bx = (int) (r * Math.sin(a) + base[0] + 0.5D);
                    int bz = (int) (r * Math.cos(a) + base[2] + 0.5D);
                    int[] start = {bx, blobY, bz};
                    if (lineLength(world, start, new int[] {bx, blobY + BLOB_HEIGHT, bz}) != -1) continue;
                    double drop = Math.sqrt(Math.pow(Math.abs(base[0] - bx), 2.0D) + Math.pow(Math.abs(base[2] - bz), 2.0D)) * 0.381D;
                    int branchY = blobY - drop > trunkTop ? trunkTop : (int) (blobY - drop);
                    if (lineLength(world, new int[] {base[0], branchY, base[2]}, start) == -1) {
                        blobs[count++] = new int[] {bx, blobY, bz, branchY};
                    }
                }
            }
            blobY--;
            rel--;
        }
        int[][] out = new int[count][];
        System.arraycopy(blobs, 0, out, 0, count);
        return out;
    }

    /** Horizontal distance of a blob from the trunk at {@code rel}; negative = no blob at that height. */
    private static float spread(int rel, int height) {
        if (rel < height * 0.3D) return -1.618F;
        float half = height / 2.0F;
        float off = half - rel;
        float d;
        if (off == 0.0F) d = half;
        else if (Math.abs(off) >= half) d = 0.0F;
        else d = (float) Math.sqrt(half * half - off * off);
        return d * 0.5F;
    }

    private static void layer(BetaWorld world, int x, int y, int z, float radius) {
        int r = (int) (radius + 0.618D);
        for (int a = -r; a <= r; a++) {
            for (int b = -r; b <= r; b++) {
                if (Math.sqrt(Math.pow(Math.abs(a) + 0.5D, 2.0D) + Math.pow(Math.abs(b) + 0.5D, 2.0D)) > radius) continue;
                if (world.isAirOrLeaves(x + a, y, z + b)) world.set(x + a, y, z + b, BetaTrees.OAK_LEAVES);
            }
        }
    }

    /** Lay logs from {@code start} to {@code end} along the longest axis. */
    private static void line(BetaWorld world, int[] start, int[] end) {
        int[] d = new int[3];
        int longest = 0;
        for (int i = 0; i < 3; i++) {
            d[i] = end[i] - start[i];
            if (Math.abs(d[i]) > Math.abs(d[longest])) longest = i;
        }
        if (d[longest] == 0) return;
        int a0 = AXIS[longest];
        int a1 = AXIS[longest + 3];
        int dir = d[longest] > 0 ? 1 : -1;
        double s0 = (double) d[a0] / d[longest];
        double s1 = (double) d[a1] / d[longest];
        int[] p = new int[3];
        for (int o = 0; o != d[longest] + dir; o += dir) {
            p[longest] = (int) Math.floor(start[longest] + o + 0.5D);
            p[a0] = (int) Math.floor(start[a0] + o * s0 + 0.5D);
            p[a1] = (int) Math.floor(start[a1] + o * s1 + 0.5D);
            world.set(p[0], p[1], p[2], LOG);
        }
    }

    /** Steps along the line until a block that is neither air nor leaves; {@code -1} if the whole line is clear. */
    private static int lineLength(BetaWorld world, int[] start, int[] end) {
        int[] d = new int[3];
        int longest = 0;
        for (int i = 0; i < 3; i++) {
            d[i] = end[i] - start[i];
            if (Math.abs(d[i]) > Math.abs(d[longest])) longest = i;
        }
        if (d[longest] == 0) return -1;
        int a0 = AXIS[longest];
        int a1 = AXIS[longest + 3];
        int dir = d[longest] > 0 ? 1 : -1;
        double s0 = (double) d[a0] / d[longest];
        double s1 = (double) d[a1] / d[longest];
        int[] p = new int[3];
        int o = 0;
        int endO = d[longest] + dir;
        for (; o != endO; o += dir) {
            p[longest] = start[longest] + o;
            p[a0] = (int) Math.floor(start[a0] + o * s0);
            p[a1] = (int) Math.floor(start[a1] + o * s1);
            if (!world.isAirOrLeaves(p[0], p[1], p[2])) break;
        }
        return o == endO ? -1 : Math.abs(o);
    }
}
