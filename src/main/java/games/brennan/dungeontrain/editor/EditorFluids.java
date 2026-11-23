package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

import java.util.HashSet;
import java.util.Set;

/**
 * The editor's <b>Fluid flow</b> setting (Settings → Fluid flow | On | Off): while it is Off, water
 * and lava placed inside an editor plot stay where they were put — the source blocks stand, nothing
 * spreads from them — so an author can place a pool or a waterfall's source without the plot filling
 * up. Off by default while editing, On by default in a Test-the-Carriage copy
 * ({@link EditorModeSetting#FLUID_FLOW}).
 *
 * <p>Scoped by position exactly as {@link EditorObservers} is: a plot located by
 * {@link EditorCategory#locateAt}, or anywhere in the Train Editor void world. A play world's train
 * and terrain sit in no plot, so their fluids flow as ever. Consumed by
 * {@code FlowingFluidEditorStillMixin}, which runs on every fluid spread in the game, so a world with
 * no editor plots standing answers after one volatile read and one null check.</p>
 */
public final class EditorFluids {

    private EditorFluids() {}

    /** True when fluid at {@code pos} must not spread under the setting. Client levels: never. */
    public static boolean isStill(Level level, BlockPos pos) {
        EditorModeSettings settings = EditorModeSettingsService.current();
        if (settings.onInBoth(EditorModeSetting.FLUID_FLOW)) return false;
        if (!(level instanceof ServerLevel serverLevel)) return false;
        if (!EditorWorldLayout.isEditorWorld(serverLevel)) {
            if (EditorStampedCategoryState.current().isEmpty()) return false;
            DungeonTrainWorldData data = DungeonTrainWorldData.get(serverLevel.getServer().overworld());
            if (EditorCategory.locateAt(pos, data.dims()).isEmpty()) return false;
        }
        return !settings.get(EditorSettingMode.at(serverLevel, pos), EditorModeSetting.FLUID_FLOW);
    }

    /** How far round each player {@link #wake} looks, in chunks — the plot they stand in and its neighbours. */
    static final int WAKE_RADIUS_CHUNKS = 8;

    /**
     * Fluid flow switched back On: give every fluid near a player in editor space a fresh tick.
     *
     * <p>Fluid held still while the setting was Off has had its one scheduled tick, and nothing
     * schedules another until a neighbour changes — so without this, turning flow back on would move
     * only the water placed afterwards. Sections whose palette holds no fluid are skipped whole, so
     * a void editor world costs a palette check per section.</p>
     *
     * @return how many fluid cells were woken
     */
    public static int wake(MinecraftServer server) {
        int woken = 0;
        Set<ChunkKey> seen = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            if (!inEditorSpace(level, player.blockPosition())) continue;
            ChunkPos centre = player.chunkPosition();
            for (int dx = -WAKE_RADIUS_CHUNKS; dx <= WAKE_RADIUS_CHUNKS; dx++) {
                for (int dz = -WAKE_RADIUS_CHUNKS; dz <= WAKE_RADIUS_CHUNKS; dz++) {
                    int cx = centre.x + dx;
                    int cz = centre.z + dz;
                    if (!seen.add(new ChunkKey(level.dimension(), ChunkPos.asLong(cx, cz)))) continue;
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk != null) woken += wakeChunk(level, chunk);
                }
            }
        }
        return woken;
    }

    /** One chunk of one level, so two players near the same plot wake it once. */
    private record ChunkKey(net.minecraft.resources.ResourceKey<Level> dimension, long chunk) {}

    private static int wakeChunk(ServerLevel level, LevelChunk chunk) {
        int woken = 0;
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir() || !section.maybeHas(state -> !state.getFluidState().isEmpty())) continue;
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        FluidState fluid = section.getFluidState(x, y, z);
                        if (fluid.isEmpty()) continue;
                        BlockPos pos = new BlockPos(chunk.getPos().getBlockX(x), baseY + y, chunk.getPos().getBlockZ(z));
                        level.scheduleTick(pos, fluid.getType(), fluid.getType().getTickDelay(level));
                        woken++;
                    }
                }
            }
        }
        return woken;
    }

    private static boolean inEditorSpace(ServerLevel level, BlockPos pos) {
        if (EditorWorldLayout.isEditorWorld(level)) return true;
        if (EditorStampedCategoryState.current().isEmpty()) return false;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level.getServer().overworld());
        return EditorCategory.locateAt(pos, data.dims()).isPresent();
    }
}
