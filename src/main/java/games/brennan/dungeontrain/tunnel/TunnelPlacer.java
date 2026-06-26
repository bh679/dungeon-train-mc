package games.brennan.dungeontrain.tunnel;

import games.brennan.dungeontrain.editor.TunnelTemplateStore;
import games.brennan.dungeontrain.ship.ShipFilterProcessor;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateType;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.FallingBlockAnchor;
import games.brennan.dungeontrain.worldgen.NetherFade;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

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
public final class TunnelPlacer {

    public enum TunnelVariant implements TemplateType {
        SECTION,
        PORTAL;

        @Override
        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        @Override
        public TemplateKind kind() {
            return TemplateKind.TUNNEL;
        }
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

    private TunnelPlacer() {}

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
        return placeTunnelWorldgen(level, serverLevel, origin,
            TunnelVariant.SECTION, TrackKind.TUNNEL_SECTION, false);
    }

    /**
     * Worldgen-time tunnel portal stamp. Mirrors {@link #placePortalAt} but
     * reads/writes through {@link WorldGenLevel}. See
     * {@link #placeSectionAtWorldgen} for the rationale on skipped runtime-
     * only post-processing (shipyard guard, sidecar SilentBlockOps).
     */
    public static boolean placePortalAtWorldgen(WorldGenLevel level, ServerLevel serverLevel, BlockPos origin, boolean mirrorX) {
        return placeTunnelWorldgen(level, serverLevel, origin,
            TunnelVariant.PORTAL, TrackKind.TUNNEL_PORTAL, mirrorX);
    }

    /**
     * Shared worldgen stamp for both tunnel variants. Outside the Nether crossfade this is the
     * classic single-variant stamp (hard {@link TrainPhase} pick). <b>Inside</b> the crossfade it
     * composites the Overworld and Nether-dark variants <em>per block</em>: it stamps the Overworld
     * variant in full, then overlays the Nether variant through a {@link NetherFadeMaskProcessor}
     * (and a fade-gated sidecar) so each cell takes the Nether block only where
     * {@link NetherFade#selectsNether} is true — turning the tunnel Nether in the same clumps the
     * surrounding terrain turns netherrack. Both the OW and Nether names are picked deterministically
     * from this tile's seed with a phase-forced {@link GateContext}, so the blend is reproducible.
     */
    private static boolean placeTunnelWorldgen(WorldGenLevel level, ServerLevel serverLevel, BlockPos origin,
                                               TunnelVariant variant, TrackKind kind, boolean mirrorX) {
        long worldSeed = serverLevel.getSeed();
        int tileIndex = origin.getX();
        ServerLevel overworld = serverLevel.getServer().overworld();
        BlockPos stampOrigin = mirrorX ? origin.offset(LENGTH - 1, 0, 0) : origin;
        GateContext baseCtx = GateContext.atWorldX(serverLevel, tileIndex);

        // Outside the crossfade: classic single-variant stamp (hard phase).
        if (!NetherFade.intersectsCrossfade(overworld, origin.getX(), origin.getX() + LENGTH - 1)) {
            String name = TrackVariantRegistry.pickName(kind, worldSeed, tileIndex, baseCtx);
            Optional<StructureTemplate> stored = TunnelTemplateStore.getFor(serverLevel, variant, name);
            if (stored.isEmpty()) return false;
            eraseInteriorAirspaceWorldgen(level, origin);
            stampTemplateWorldgen(level, stampOrigin, stored.get(), mirrorX);
            applyTunnelSidecarWorldgen(level, origin, mirrorX, kind, name, worldSeed, tileIndex);
            return true;
        }

        // Inside the crossfade: Overworld base, then a per-block-masked Nether overlay.
        String owName = TrackVariantRegistry.pickName(kind, worldSeed, tileIndex,
            new GateContext(baseCtx.level(), TrainPhase.OVERWORLD));
        Optional<StructureTemplate> owTemplate = TunnelTemplateStore.getFor(serverLevel, variant, owName);
        if (owTemplate.isEmpty()) return false;
        eraseInteriorAirspaceWorldgen(level, origin);
        stampTemplateWorldgen(level, stampOrigin, owTemplate.get(), mirrorX);
        applyTunnelSidecarWorldgen(level, origin, mirrorX, kind, owName, worldSeed, tileIndex);

        long genSeed = DungeonTrainWorldData.get(overworld).getGenerationSeed();
        String netherName = TrackVariantRegistry.pickName(kind, worldSeed, tileIndex,
            new GateContext(baseCtx.level(), TrainPhase.NETHER));
        Optional<StructureTemplate> netherTemplate = TunnelTemplateStore.getFor(serverLevel, variant, netherName);
        if (netherTemplate.isPresent()) {
            stampTemplateWorldgen(level, stampOrigin, netherTemplate.get(), mirrorX,
                new NetherFadeMaskProcessor(overworld, genSeed));
            applyTunnelSidecarWorldgen(level, origin, mirrorX, kind, netherName, worldSeed, tileIndex,
                overworld, genSeed);
        }
        return true;
    }


    private static void stampTemplateWorldgen(WorldGenLevel level, BlockPos origin,
                                              StructureTemplate template, boolean mirrorX) {
        stampTemplateWorldgen(level, origin, template, mirrorX, null);
    }

    /**
     * As {@link #stampTemplateWorldgen(WorldGenLevel, BlockPos, StructureTemplate, boolean)} but with
     * an optional extra {@link StructureProcessor} — used to overlay the Nether-dark variant through
     * a {@link NetherFadeMaskProcessor} so only the fade-selected cells of the second stamp land,
     * leaving the Overworld stamp beneath everywhere else. Re-anchoring the footprint is idempotent,
     * so the masked overlay can run it again harmlessly.
     */
    private static void stampTemplateWorldgen(WorldGenLevel level, BlockPos origin,
                                              StructureTemplate template, boolean mirrorX,
                                              @Nullable StructureProcessor extraProcessor) {
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setIgnoreEntities(true)
            // Honour the template's dry (waterlogged=false) state — never inherit terrain
            // water into the stamped stairs/slabs/walls/trapdoors. The default
            // APPLY_WATERLOGGING re-floods any waterloggable block placed into a cell that
            // still holds water, which waterlogs tunnel stairs wherever a column's worldgen
            // water-drain hasn't landed yet (band edges / cross-chunk decoration-order
            // races). The Nether band must stay bone-dry, so ignore existing fluids.
            .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING);
        // No ShipFilterProcessor — no ships at chunkgen.
        if (extraProcessor != null) settings.addProcessor(extraProcessor);
        if (mirrorX) settings.setMirror(Mirror.FRONT_BACK);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_CLIENTS);
        anchorAboveFootprintWorldgen(level, origin);
    }

    /**
     * Force-clear the walkable airspace volume inside the {@link #LENGTH}×{@link #HEIGHT}×{@link #WIDTH}
     * tunnel footprint before stamping the NBT template. Defends against
     * vanilla nether features (basalt pillars, deltas) that occasionally
     * leave blocks inside the corridor airspace because of decoration-order
     * races at the worldgen 3×3 chunk window — once a basalt block lands at
     * an AIR-cell position, the template's air cell stamps the same air on
     * top, but if some path through {@link StructureTemplate#placeInWorld}
     * silently skips the write the basalt persists. Same pattern as
     * {@link games.brennan.dungeontrain.train.CarriagePartPlacer}'s pre-erase.
     *
     * <p>Volume cleared: {@code floorY+1..ceilingY} (9 rows, walking
     * airspace only) × {@code airMinZ..airMaxZ} (11 cols, open interior) ×
     * 10 cols (full {@link #LENGTH}). Skips rail cells at
     * {@code (railY, railZMin|railZMax)} — they're already placed by
     * {@code TrackGenerator} and the template will re-stamp them anyway.</p>
     *
     * <p>A second pass clears the <b>arched-roof interior</b> ({@code ceilingY+1 .. ceilingY+ARCH_TIERS},
     * z narrowing one block per tier) but ONLY of leaked <em>underground material</em>. A densely
     * pre-filled corridor — the Nether-transition mountains fill the lane solid so {@code track_bed}
     * qualifies it as a tunnel — leaves bulk terrain inside the arch that {@code placeInWorld} doesn't
     * reliably overwrite, which read as stone inside the tunnel. The stone-brick arch shell / stair
     * smoothers / apex cap and any (sea-)lanterns are NOT underground material, so they survive untouched.</p>
     *
     * <p>Crucially does NOT touch:
     * <ul>
     *   <li>The floor row at {@code floorY} — template re-stamps stone-brick floor.</li>
     *   <li>The stone-brick arch shell + stair smoothers + apex cap — preserved by the underground-material filter on the arch pass.</li>
     *   <li>The corner wedges (at apex Y, z OUTSIDE {@code airMinZ..airMaxZ}) — intentional STRUCTURE_VOID cells so overworld terrain blends naturally with the arch corners. Both passes stay within {@code airMinZ..airMaxZ} (the arch pass insets further per tier), so the wedges and the mountain beside the arch are never touched.</li>
     * </ul></p>
     *
     * <p>Uses {@code origin} (canonical, unshifted) — the airspace geometry
     * is invariant under mirror, so callers don't need to pass mirror state.
     * Skips the {@link Shipyards} guard (no ships at chunkgen).</p>
     */
    private static void eraseInteriorAirspaceWorldgen(WorldGenLevel level, BlockPos origin) {
        TunnelGeometry tg = LegacyTunnelPaint.geometryForPlot(origin);
        int floorY = tg.floorY();
        int ceilingY = tg.ceilingY();
        int airMinZ = tg.airMinZ();
        int airMaxZ = tg.airMaxZ();
        int railY = tg.railY();
        int railZMin = tg.railZMin();
        int railZMax = tg.railZMax();
        int originX = origin.getX();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < LENGTH; dx++) {
            int worldX = originX + dx;
            // Flat interior (floorY+1 .. ceilingY): clear everything (rails excepted).
            for (int y = floorY + 1; y <= ceilingY; y++) {
                for (int z = airMinZ; z <= airMaxZ; z++) {
                    if (y == railY && (z == railZMin || z == railZMax)) continue;
                    pos.set(worldX, y, z);
                    if (level.getBlockState(pos).isAir()) continue;
                    level.setBlock(pos, AIR, Block.UPDATE_CLIENTS);
                }
            }
            // Arched-roof interior (ceilingY+1 .. ceilingY+ARCH_TIERS): clear ONLY leaked underground
            // material — a pre-filled corridor (the Nether-transition mountains) leaves bulk terrain the
            // template doesn't reliably carve. The stone-brick shell / apex / lanterns aren't underground
            // material so they survive; the per-tier inset keeps the clear inside the arch (never the
            // corner wedges or the mountain beside it).
            for (int tier = 1; tier <= LegacyTunnelPaint.ARCH_TIERS; tier++) {
                int y = ceilingY + tier;
                int zLo = airMinZ + (tier - 1);
                int zHi = airMaxZ - (tier - 1);
                for (int z = zLo; z <= zHi; z++) {
                    pos.set(worldX, y, z);
                    if (!TunnelPalette.isUndergroundMaterial(level.getBlockState(pos))) continue;
                    level.setBlock(pos, AIR, Block.UPDATE_CLIENTS);
                }
            }
        }
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
        applyTunnelSidecarWorldgen(level, origin, mirrorX, kind, name, worldSeed, tileIndex, null, 0L);
    }

    /**
     * As the unmasked overload, but when {@code fadeOverworld != null} each sidecar cell is written
     * only where {@link NetherFade#selectsNether} is true — the masked Nether-variant overlay, so a
     * Nether sidecar block lands exactly where the Nether base block did (and the Overworld sidecar
     * survives elsewhere). Keeps the base stamp and the sidecar overlay in lock-step per cell.
     */
    private static void applyTunnelSidecarWorldgen(
        WorldGenLevel level, BlockPos origin, boolean mirrorX,
        TrackKind kind, String name, long worldSeed, int tileIndex,
        @Nullable ServerLevel fadeOverworld, long genSeed
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
            // Masked overlay: only write cells the per-block fade selects as Nether; the Overworld
            // stamp/sidecar already filled the rest.
            if (fadeOverworld != null
                && !NetherFade.selectsNether(genSeed, wx, wy, wz, NetherFade.rampAt(fadeOverworld, wx))) {
                continue;
            }
            BlockPos wpos = new BlockPos(wx, wy, wz);
            games.brennan.dungeontrain.editor.VariantState picked =
                sidecar.resolve(entry.localPos(), worldSeed, tileIndex);
            if (picked == null) continue;
            if (picked.isMob()) {
                games.brennan.dungeontrain.track.TrackVariantMobs.warnDropped(
                    "tunnel", entry.localPos(), picked.entityId());
                level.setBlock(wpos, AIR, Block.UPDATE_CLIENTS);
                continue;
            }
            BlockState toPlace =
                games.brennan.dungeontrain.editor.CarriageVariantBlocks.isEmptyPlaceholder(picked.state())
                    ? AIR
                    : picked.state();
            level.setBlock(wpos, toPlace, Block.UPDATE_CLIENTS);
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
        anchorAboveFootprint(level, origin);
    }

    private static void stampTemplate(ServerLevel level, BlockPos origin,
                                      StructureTemplate template, boolean mirrorX) {
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setIgnoreEntities(true)
            // Skip positions owned by a managed ship so a tunnel stamp that
            // lands in the chunk the train is currently in doesn't wipe
            // out carriage voxels with the template's interior-air cells.
            .addProcessor(ShipFilterProcessor.INSTANCE)
            // Keep the tunnel dry — don't inherit terrain water into waterloggable
            // blocks (mirrors the worldgen path; see stampTemplateWorldgen).
            .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING);
        if (mirrorX) settings.setMirror(Mirror.FRONT_BACK);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), 3);
        anchorAboveFootprint(level, origin);
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
            if (picked.isMob()) {
                games.brennan.dungeontrain.track.TrackVariantMobs.warnDropped(
                    "tunnel-sidecar", entry.localPos(), picked.entityId());
                SilentBlockOps.setBlockSilent(level, wpos, AIR);
                continue;
            }
            if (games.brennan.dungeontrain.editor.CarriageVariantBlocks.isEmptyPlaceholder(picked.state())) {
                SilentBlockOps.setBlockSilent(level, wpos, AIR);
            } else {
                SilentBlockOps.setBlockSilent(level, wpos, picked.state(), picked.blockEntityNbt());
            }
        }
    }

    /**
     * Anchor any falling block sitting on the {@link #LENGTH}×{@link #WIDTH}
     * footprint immediately above the stamped template. Prevents sand /
     * gravel resting on the apex roof from falling into the tunnel via a
     * persisted {@link net.minecraft.world.level.block.FallingBlock}
     * scheduled tick. Defensive — the apex stone-brick cap usually covers
     * this, but the corner wedges and any custom template gaps benefit
     * from the guarantee.
     */
    private static void anchorAboveFootprint(ServerLevel level, BlockPos origin) {
        int anchorY = origin.getY() + HEIGHT;
        for (int dx = 0; dx < LENGTH; dx++) {
            for (int dz = 0; dz < WIDTH; dz++) {
                FallingBlockAnchor.anchorAt(
                    level, new BlockPos(origin.getX() + dx, anchorY, origin.getZ() + dz));
            }
        }
    }

    /** Worldgen variant of {@link #anchorAboveFootprint}. */
    private static void anchorAboveFootprintWorldgen(WorldGenLevel level, BlockPos origin) {
        int anchorY = origin.getY() + HEIGHT;
        for (int dx = 0; dx < LENGTH; dx++) {
            for (int dz = 0; dz < WIDTH; dz++) {
                FallingBlockAnchor.anchorAtWorldgen(
                    level, new BlockPos(origin.getX() + dx, anchorY, origin.getZ() + dz));
            }
        }
    }
}
