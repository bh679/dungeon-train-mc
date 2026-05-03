package games.brennan.dungeontrain.tunnel;

import games.brennan.dungeontrain.editor.TunnelTemplateStore;
import games.brennan.dungeontrain.ship.ShipFilterProcessor;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Optional;

/**
 * Tunnel blueprint — a 10×14×13 (X×Y×Z) box covering one lamp-spaced section
 * of the auto-generated stone-brick tunnel. Two variants: {@link TunnelVariant#SECTION}
 * (uninterrupted tunnel interior, stamped end-to-end along a run) and
 * {@link TunnelVariant#PORTAL} (same footprint, with a pyramid facade at
 * {@code x = 0}; mirrored on +X for exit portals).
 *
 * <p>{@link #placeSectionAt(ServerLevel, BlockPos)} and
 * {@link #placePortalAt(ServerLevel, BlockPos, boolean)} first try the
 * NBT-backed template from {@link TunnelTemplateStore}; on miss they fall
 * back to {@link LegacyTunnelPaint#paintSection} / {@link LegacyTunnelPaint#paintPortal}
 * so the pre-refactor geometry still shows up when the player hasn't saved a
 * custom template.</p>
 */
public final class TunnelTemplate {

    public enum TunnelVariant {
        SECTION,
        PORTAL
    }

    /** X extent — aligns with {@code LAMP_SPACING} so stamps are lamp-station-aligned. */
    public static final int LENGTH = 10;

    /**
     * Y extent — from tunnel {@code floorY = bedY} up to the arched apex at
     * {@code apexY = ceilingY + ARCH_TIERS + 1 = bedY + 13}, inclusive → 14 rows.
     */
    public static final int HEIGHT = 14;

    /** Z extent — {@code wallMaxZ - wallMinZ + 1 = 13}. */
    public static final int WIDTH = 13;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private TunnelTemplate() {}

    /**
     * Editor-facing — stamp the named section variant at {@code origin}
     * (skips registry weight pick). Falls back to procedural paint if the
     * named template is missing. Sidecar applied with
     * {@code tileIndex = origin.getX()} for runtime determinism.
     */
    public static void placeSectionNamed(ServerLevel level, BlockPos origin, String name) {
        long worldSeed = level.getSeed();
        int tileIndex = origin.getX();
        Optional<StructureTemplate> stored = TunnelTemplateStore.getFor(level, TunnelVariant.SECTION, name);
        if (stored.isPresent()) {
            stampTemplate(level, origin, stored.get(), false);
            applyTunnelSidecar(level, origin, false, TrackKind.TUNNEL_SECTION, name, worldSeed, tileIndex);
            return;
        }
        TunnelGeometry tg = LegacyTunnelPaint.geometryForPlot(origin);
        LegacyTunnelPaint.paintSection(level, origin.getX(), tg);
    }

    /**
     * Editor-facing — stamp the named portal variant at {@code origin}
     * (skips registry weight pick). See {@link #placeSectionNamed} for
     * rationale.
     */
    public static void placePortalNamed(ServerLevel level, BlockPos origin, boolean mirrorX, String name) {
        long worldSeed = level.getSeed();
        int tileIndex = origin.getX();
        Optional<StructureTemplate> stored = TunnelTemplateStore.getFor(level, TunnelVariant.PORTAL, name);
        if (stored.isPresent()) {
            BlockPos stampOrigin = mirrorX ? origin.offset(LENGTH - 1, 0, 0) : origin;
            stampTemplate(level, stampOrigin, stored.get(), mirrorX);
            applyTunnelSidecar(level, origin, mirrorX, TrackKind.TUNNEL_PORTAL, name, worldSeed, tileIndex);
            return;
        }
        TunnelGeometry tg = LegacyTunnelPaint.geometryForPlot(origin);
        LegacyTunnelPaint.paintPortal(level, origin.getX(), tg, mirrorX);
    }

    /**
     * Worldgen-time tunnel section stamp. Mirrors {@link #placeSectionAt}
     * but reads/writes through {@link WorldGenLevel}. Returns {@code true}
     * if an NBT-backed stamp was placed; {@code false} if no template was
     * available (caller falls back to {@link LegacyTunnelPaint#paintSection}
     * which already runs in the worldgen path).
     *
     * <p>Skips the {@link Shipyards} check (no ships exist at chunkgen) and
     * the {@link ShipFilterProcessor} (same reason). Uses
     * {@link Block#UPDATE_CLIENTS} as the placement flag — neighbour-update
     * cascades are unsafe within the worldgen 3×3 decoration window.</p>
     */
    public static boolean placeSectionAtWorldgen(WorldGenLevel level, ServerLevel serverLevel, BlockPos origin) {
        long worldSeed = serverLevel.getSeed();
        int tileIndex = origin.getX();
        String name = TrackVariantRegistry.pickName(TrackKind.TUNNEL_SECTION, worldSeed, tileIndex);
        Optional<StructureTemplate> stored = TunnelTemplateStore.getFor(serverLevel, TunnelVariant.SECTION, name);
        if (stored.isEmpty()) return false;
        stampTemplateWorldgen(level, origin, stored.get(), false);
        applyTunnelSidecarWorldgen(level, origin, false, TrackKind.TUNNEL_SECTION, name, worldSeed, tileIndex);
        return true;
    }

    /**
     * Worldgen-time tunnel portal stamp. Mirrors {@link #placePortalAt} but
     * reads/writes through {@link WorldGenLevel}. See
     * {@link #placeSectionAtWorldgen} for the rationale on skipped runtime-
     * only post-processing (shipyard guard, sidecar SilentBlockOps).
     */
    public static boolean placePortalAtWorldgen(WorldGenLevel level, ServerLevel serverLevel, BlockPos origin, boolean mirrorX) {
        long worldSeed = serverLevel.getSeed();
        int tileIndex = origin.getX();
        String name = TrackVariantRegistry.pickName(TrackKind.TUNNEL_PORTAL, worldSeed, tileIndex);
        Optional<StructureTemplate> stored = TunnelTemplateStore.getFor(serverLevel, TunnelVariant.PORTAL, name);
        if (stored.isEmpty()) return false;
        BlockPos stampOrigin = mirrorX ? origin.offset(LENGTH - 1, 0, 0) : origin;
        stampTemplateWorldgen(level, stampOrigin, stored.get(), mirrorX);
        applyTunnelSidecarWorldgen(level, origin, mirrorX, TrackKind.TUNNEL_PORTAL, name, worldSeed, tileIndex);
        return true;
    }


    private static void stampTemplateWorldgen(WorldGenLevel level, BlockPos origin,
                                              StructureTemplate template, boolean mirrorX) {
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setIgnoreEntities(true);
        // No ShipFilterProcessor — no ships at chunkgen.
        if (mirrorX) settings.setMirror(Mirror.FRONT_BACK);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_CLIENTS);
    }

    /**
     * Worldgen variant of {@link #applyTunnelSidecar}. Direct
     * {@link WorldGenLevel#setBlock} with {@link Block#UPDATE_CLIENTS}; no
     * {@link Shipyards} guard (no ships at chunkgen); no block-entity NBT
     * stamping (BE wiring through WorldGenLevel is awkward and tunnel sidecars
     * rarely carry BE data).
     */
    private static void applyTunnelSidecarWorldgen(
        WorldGenLevel level, BlockPos origin, boolean mirrorX,
        TrackKind kind, String name, long worldSeed, int tileIndex
    ) {
        TrackVariantBlocks sidecar = TrackVariantBlocks.loadFor(
            kind, name, new Vec3i(LENGTH, HEIGHT, WIDTH));
        if (sidecar.isEmpty()) return;
        for (var entry : sidecar.entries()) {
            int lx = entry.localPos().getX();
            int ly = entry.localPos().getY();
            int lz = entry.localPos().getZ();
            int wx = mirrorX ? (origin.getX() + LENGTH - 1 - lx) : (origin.getX() + lx);
            int wy = origin.getY() + ly;
            int wz = origin.getZ() + lz;
            BlockPos wpos = new BlockPos(wx, wy, wz);
            games.brennan.dungeontrain.editor.VariantState picked =
                sidecar.resolve(entry.localPos(), worldSeed, tileIndex);
            if (picked == null) continue;
            level.setBlock(wpos, picked.state(), Block.UPDATE_CLIENTS);
        }
    }

    /** Zero-out the 10×14×13 footprint at {@code origin}. Used by the editor. */
    public static void eraseAt(ServerLevel level, BlockPos origin) {
        for (int dx = 0; dx < LENGTH; dx++) {
            for (int dy = 0; dy < HEIGHT; dy++) {
                for (int dz = 0; dz < WIDTH; dz++) {
                    level.setBlock(origin.offset(dx, dy, dz), AIR, 3);
                }
            }
        }
    }

    private static void stampTemplate(ServerLevel level, BlockPos origin,
                                      StructureTemplate template, boolean mirrorX) {
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setIgnoreEntities(true)
            // Skip positions owned by a managed ship so a tunnel stamp that
            // lands in the chunk the train is currently in doesn't wipe
            // out carriage voxels with the template's interior-air cells.
            .addProcessor(ShipFilterProcessor.INSTANCE);
        if (mirrorX) settings.setMirror(Mirror.FRONT_BACK);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), 3);
    }

    /**
     * Sidecar post-pass for a stamped tunnel template. Reads the
     * {@link TrackVariantBlocks} sidecar for {@code (kind, name)}, walks each
     * flagged template-local position, computes the world position
     * (accounting for {@code Mirror.FRONT_BACK} on portal exits), and
     * overwrites with the deterministic per-block pick.
     *
     * <p>{@code origin} is always the canonical-side origin
     * ({@code (worldX, floorY, wallMinZ)}); the portal's {@code mirrorX}
     * stamp uses a shifted stampOrigin internally but world positions
     * computed here are relative to the canonical origin so the same
     * sidecar entry maps to the same visible block on either side.</p>
     */
    private static void applyTunnelSidecar(
        ServerLevel level, BlockPos origin, boolean mirrorX,
        TrackKind kind, String name, long worldSeed, int tileIndex
    ) {
        TrackVariantBlocks sidecar = TrackVariantBlocks.loadFor(
            kind, name, new Vec3i(LENGTH, HEIGHT, WIDTH));
        if (sidecar.isEmpty()) return;
        for (var entry : sidecar.entries()) {
            int lx = entry.localPos().getX();
            int ly = entry.localPos().getY();
            int lz = entry.localPos().getZ();
            // FRONT_BACK negates X around the stamp origin (= origin + (LENGTH-1, 0, 0)),
            // so for the mirrored side world X = (origin.x + LENGTH - 1) - lx.
            // For the non-mirrored side world coords = origin + local.
            int wx = mirrorX ? (origin.getX() + LENGTH - 1 - lx) : (origin.getX() + lx);
            int wy = origin.getY() + ly;
            int wz = origin.getZ() + lz;
            BlockPos wpos = new BlockPos(wx, wy, wz);
            if (Shipyards.of(level).isInShip(wpos)) continue;
            games.brennan.dungeontrain.editor.VariantState picked =
                sidecar.resolve(entry.localPos(), worldSeed, tileIndex);
            if (picked == null) continue;
            SilentBlockOps.setBlockSilent(level, wpos, picked.state(), picked.blockEntityNbt());
        }
    }
}
