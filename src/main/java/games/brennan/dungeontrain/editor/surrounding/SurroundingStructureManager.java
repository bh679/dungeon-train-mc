package games.brennan.dungeontrain.editor.surrounding;

import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.SurroundingStructureGhostPacket;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side per-player state for the Surrounding Structures editor preview. Mirrors
 * {@link games.brennan.dungeontrain.editor.EditorPlotLabels}'s style: static-method utility keyed off
 * {@link ServerPlayer}, driven from the same tick loop that already calls
 * {@link EditorCategory#locate}.
 *
 * <p><b>GHOST</b> is genuinely per-player: each player gets their own translucent preview pushed over
 * {@link SurroundingStructureGhostPacket}, with no shared state.</p>
 *
 * <p><b>SOLID</b> places real world blocks, which are inherently shared per-plot state — two players
 * standing in the same plot with different SOLID/NONE choices for a category can't both be right, so
 * this deliberately implements "last write wins": {@link #SOLID_STAMPS} is keyed by plot identity (not
 * by player), and whichever player's reconcile last ran for that (plot, category) determines whether
 * the blocks exist. A player who triggered a fresh stamp is recorded in
 * {@link #PLAYER_SOLID_CONTRIBUTIONS} so their own logout reverts what they caused to appear, without
 * touching a plot another player is still actively stamping.</p>
 */
public final class SurroundingStructureManager {

    private static final RenderMode DEFAULT_MODE = RenderMode.GHOST;

    /** Per-player effective render mode overrides — set by {@code /dt editor surrounding} commands and
     *  seeded once from the client's synced config default on login. Missing entry ⇒ {@link #DEFAULT_MODE}. */
    private static final Map<UUID, EnumMap<SurroundingStructureCategory, RenderMode>> PLAYER_MODES =
        new ConcurrentHashMap<>();

    /** Dedup key of the last ghost packet sent per (player, category), so steady-state ticks send nothing. */
    private static final Map<UUID, EnumMap<SurroundingStructureCategory, String>> LAST_GHOST_KEY =
        new ConcurrentHashMap<>();

    /** Shared per-plot SOLID stamps: {@code plotKey|category} -> the exact positions stamped, for precise revert. */
    private static final Map<String, List<BlockPos>> SOLID_STAMPS = new ConcurrentHashMap<>();

    /** Which {@code plotKey|category} entries this player caused to be freshly stamped — reverted on their logout. */
    private static final Map<UUID, Set<String>> PLAYER_SOLID_CONTRIBUTIONS = new ConcurrentHashMap<>();

    private SurroundingStructureManager() {}

    // ----- Mode read/write -----

    public static RenderMode effectiveMode(ServerPlayer player, SurroundingStructureCategory category) {
        EnumMap<SurroundingStructureCategory, RenderMode> modes = PLAYER_MODES.get(player.getUUID());
        if (modes == null) return DEFAULT_MODE;
        RenderMode mode = modes.get(category);
        return mode == null ? DEFAULT_MODE : mode;
    }

    /** Set this player's mode for one category — used by {@code /dt editor surrounding <category> <mode>}. */
    public static void setMode(ServerPlayer player, SurroundingStructureCategory category, RenderMode mode) {
        PLAYER_MODES.computeIfAbsent(player.getUUID(), k -> new EnumMap<>(SurroundingStructureCategory.class))
            .put(category, mode);
    }

    /** Set every category to the same mode — used by {@code /dt editor surrounding <mode>} (the global default command). */
    public static void setAllModes(ServerPlayer player, RenderMode mode) {
        for (SurroundingStructureCategory c : SurroundingStructureCategory.values()) {
            setMode(player, c, mode);
        }
    }

    /** Clear every per-category override for this player, back to {@link #DEFAULT_MODE}. */
    public static void resetModes(ServerPlayer player) {
        PLAYER_MODES.remove(player.getUUID());
    }

    /**
     * Seed this player's modes from their synced client-config default, without clobbering any
     * {@code /dt editor surrounding} override already set this session. Called on
     * {@code SurroundingStructureModePacket} receipt (login, and whenever the client's config changes).
     */
    public static void applyClientDefaults(ServerPlayer player, EnumMap<SurroundingStructureCategory, RenderMode> synced) {
        PLAYER_MODES.putIfAbsent(player.getUUID(), synced);
    }

    // ----- Reconcile -----

    /**
     * Recompute every category's placements for the plot {@code player} is currently standing in (or
     * clear everything when they've left every plot) and push/stamp the result. Call once per player
     * per tick alongside {@link EditorCategory#locate} — see {@code VariantOverlayRenderer.onLevelTick}.
     */
    public static void reconcile(ServerPlayer player, ServerLevel level, CarriageDims dims,
                                  Optional<EditorCategory.Located> locatedOpt) {
        if (locatedOpt.isEmpty()) {
            clearAllGhosts(player);
            return;
        }
        EditorCategory.Located located = locatedOpt.get();
        BlockPos origin = located.model().editorPlotOrigin(level, dims);
        if (origin == null) {
            clearAllGhosts(player);
            return;
        }
        String plotKey = plotKeyFor(located);

        for (SurroundingStructureCategory category : SurroundingStructureCategory.values()) {
            RenderMode mode = effectiveMode(player, category);
            String stampKey = plotKey + "|" + category.name();
            switch (mode) {
                case SOLID -> {
                    sendGhostClearIfNeeded(player, category);
                    handleSolid(player, level, dims, located, origin, category, stampKey);
                }
                case GHOST -> {
                    revertSolidStamp(level, stampKey);
                    handleGhost(player, level, dims, located, origin, category);
                }
                case NONE -> {
                    revertSolidStamp(level, stampKey);
                    sendGhostClearIfNeeded(player, category);
                }
            }
        }
    }

    /** Called when a player leaves the editor entirely / logs out — see {@code VariantOverlayRenderer.forget}. */
    public static void forget(ServerPlayer player) {
        clearAllGhosts(player);
        Set<String> contributions = PLAYER_SOLID_CONTRIBUTIONS.remove(player.getUUID());
        if (contributions != null) {
            ServerLevel level = player.serverLevel();
            for (String stampKey : contributions) {
                revertSolidStamp(level, stampKey);
            }
        }
    }

    /** Wipe every bit of state — call on server stop, mirroring {@code VariantOverlayRenderer.onServerStopped}. */
    public static void clearAll() {
        PLAYER_MODES.clear();
        LAST_GHOST_KEY.clear();
        SOLID_STAMPS.clear();
        PLAYER_SOLID_CONTRIBUTIONS.clear();
    }

    // ----- SOLID -----

    private static void handleSolid(ServerPlayer player, ServerLevel level, CarriageDims dims,
                                     EditorCategory.Located located, BlockPos origin,
                                     SurroundingStructureCategory category, String stampKey) {
        if (SOLID_STAMPS.containsKey(stampKey)) return; // already stamped (by this player or another)
        List<PlacementSpec> specs = category.resolve(level, dims, located);
        if (specs.isEmpty()) return;
        List<BlockPos> placed = new ArrayList<>();
        for (PlacementSpec spec : specs) {
            Optional<StructureTemplate> templateOpt = spec.templateSupplier().get();
            if (templateOpt.isEmpty()) continue;
            StructureTemplate template = templateOpt.get();
            BlockPos stampPos = origin.offset(spec.worldOffset());
            StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setMirror(spec.mirror());
            SolidCaptureProcessor capture = new SolidCaptureProcessor();
            settings.addProcessor(capture);
            template.placeInWorld(level, stampPos, stampPos, settings, level.getRandom(), Block.UPDATE_ALL);
            placed.addAll(capture.placed);
        }
        if (placed.isEmpty()) return;
        SOLID_STAMPS.put(stampKey, placed);
        PLAYER_SOLID_CONTRIBUTIONS.computeIfAbsent(player.getUUID(), k -> ConcurrentHashMap.newKeySet())
            .add(stampKey);
    }

    private static void revertSolidStamp(ServerLevel level, String stampKey) {
        List<BlockPos> placed = SOLID_STAMPS.remove(stampKey);
        if (placed == null) return;
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos pos : placed) {
            level.setBlock(pos, air, Block.UPDATE_ALL);
        }
    }

    private static String plotKeyFor(EditorCategory.Located located) {
        return located.category().name() + "|" + located.model().id() + "|" + located.model().variantName();
    }

    /** Non-mutating placeInWorld processor: records the resolved positions of every real block a SOLID stamp touches. */
    private static final class SolidCaptureProcessor extends StructureProcessor {
        private static final StructureProcessorType<SolidCaptureProcessor> TYPE =
            () -> MapCodec.unit(new SolidCaptureProcessor());

        final List<BlockPos> placed = new ArrayList<>();

        @Override
        @Nullable
        public StructureTemplate.StructureBlockInfo processBlock(
            LevelReader world, BlockPos offset, BlockPos pivot,
            StructureTemplate.StructureBlockInfo source, StructureTemplate.StructureBlockInfo target,
            StructurePlaceSettings settings
        ) {
            if (target != null && !target.state().is(Blocks.STRUCTURE_VOID)) {
                placed.add(target.pos().immutable());
            }
            // Return unchanged so placeInWorld performs its own normal write for this cell — this
            // processor only observes, exactly matching how a real placer would stamp it.
            return target;
        }

        @Override
        protected StructureProcessorType<?> getType() {
            return TYPE;
        }
    }

    // ----- GHOST -----

    private static void handleGhost(ServerPlayer player, ServerLevel level, CarriageDims dims,
                                     EditorCategory.Located located, BlockPos origin,
                                     SurroundingStructureCategory category) {
        List<PlacementSpec> specs = category.resolve(level, dims, located);
        List<SurroundingStructureGhostPacket.Entry> entries = new ArrayList<>();
        for (PlacementSpec spec : specs) {
            Optional<StructureTemplate> templateOpt = spec.templateSupplier().get();
            if (templateOpt.isEmpty()) continue;
            StructureTemplate template = templateOpt.get();
            BlockPos stampPos = origin.offset(spec.worldOffset());
            StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setMirror(spec.mirror());
            GhostCaptureProcessor capture = new GhostCaptureProcessor();
            settings.addProcessor(capture);
            // Returning null from every cell means this call writes nothing to the real world — see
            // GhostCaptureProcessor's doc. Reuses the exact same geometry/rotation/mirror pipeline as
            // the real SOLID stamp and Template#placeAt.
            template.placeInWorld(level, stampPos, stampPos, settings, level.getRandom(), Block.UPDATE_CLIENTS);
            for (int i = 0; i < capture.positions.size(); i++) {
                entries.add(new SurroundingStructureGhostPacket.Entry(
                    capture.positions.get(i), capture.states.get(i)));
            }
        }
        sendGhostSnapshot(player, category, entries);
    }

    private static void sendGhostSnapshot(ServerPlayer player, SurroundingStructureCategory category,
                                           List<SurroundingStructureGhostPacket.Entry> entries) {
        UUID uuid = player.getUUID();
        EnumMap<SurroundingStructureCategory, String> lastKeys =
            LAST_GHOST_KEY.computeIfAbsent(uuid, k -> new EnumMap<>(SurroundingStructureCategory.class));
        StringBuilder key = new StringBuilder(entries.size() * 12);
        for (SurroundingStructureGhostPacket.Entry e : entries) {
            key.append(e.pos().getX()).append(',').append(e.pos().getY()).append(',').append(e.pos().getZ())
                .append(':').append(e.state()).append(';');
        }
        String newKey = key.toString();
        if (newKey.equals(lastKeys.get(category))) return;
        lastKeys.put(category, newKey);
        DungeonTrainNet.sendTo(player, SurroundingStructureGhostPacket.chunkFirst(category, entries));
        // Chunk at ~4096 entries per packet for very large resolved sets.
        for (int i = SurroundingStructureGhostPacket.CHUNK_SIZE; i < entries.size();
             i += SurroundingStructureGhostPacket.CHUNK_SIZE) {
            int end = Math.min(i + SurroundingStructureGhostPacket.CHUNK_SIZE, entries.size());
            DungeonTrainNet.sendTo(player,
                new SurroundingStructureGhostPacket(category, entries.subList(i, end), false));
        }
    }

    private static void sendGhostClearIfNeeded(ServerPlayer player, SurroundingStructureCategory category) {
        UUID uuid = player.getUUID();
        EnumMap<SurroundingStructureCategory, String> lastKeys = LAST_GHOST_KEY.get(uuid);
        if (lastKeys == null || !lastKeys.containsKey(category)) return;
        String prev = lastKeys.remove(category);
        if (prev == null || prev.isEmpty()) return;
        DungeonTrainNet.sendTo(player, SurroundingStructureGhostPacket.empty(category));
    }

    private static void clearAllGhosts(ServerPlayer player) {
        UUID uuid = player.getUUID();
        EnumMap<SurroundingStructureCategory, String> lastKeys = LAST_GHOST_KEY.remove(uuid);
        if (lastKeys == null) return;
        for (SurroundingStructureCategory category : lastKeys.keySet()) {
            DungeonTrainNet.sendTo(player, SurroundingStructureGhostPacket.empty(category));
        }
    }

    /** Non-mutating placeInWorld processor: captures the resolved (pos, state) pairs for a GHOST preview without writing. */
    private static final class GhostCaptureProcessor extends StructureProcessor {
        private static final StructureProcessorType<GhostCaptureProcessor> TYPE =
            () -> MapCodec.unit(new GhostCaptureProcessor());

        final List<BlockPos> positions = new ArrayList<>();
        final List<BlockState> states = new ArrayList<>();

        @Override
        @Nullable
        public StructureTemplate.StructureBlockInfo processBlock(
            LevelReader world, BlockPos offset, BlockPos pivot,
            StructureTemplate.StructureBlockInfo source, StructureTemplate.StructureBlockInfo target,
            StructurePlaceSettings settings
        ) {
            if (target != null) {
                BlockState state = target.state();
                if (!state.isAir() && !state.is(Blocks.STRUCTURE_VOID)) {
                    positions.add(target.pos().immutable());
                    states.add(state);
                }
            }
            // Always drop the cell — placeInWorld's own write is skipped for every block, so this call
            // never touches the real world (same trick as SectionLocalStampProcessor).
            return null;
        }

        @Override
        protected StructureProcessorType<?> getType() {
            return TYPE;
        }
    }
}
