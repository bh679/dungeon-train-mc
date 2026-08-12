package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.editor.TemplateCells;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Map;

/**
 * The real blocks of a track-side template, for the builder's ghost preview.
 *
 * <p>Ghosts modelled from {@code TrackPalette} were stone bricks and a rail whatever the template
 * actually said, which makes a preview that cannot show you the thing you are previewing. These are
 * the authored blocks.</p>
 *
 * <p><b>Yes, on the client.</b> A builder world is single-player, so the client is also the server —
 * the same reason {@code EditorTemplateLists} is already called straight from the Open screen. The
 * {@code ServerLevel} that {@link TrackVariantStore} asks for is used for one block-registry lookup,
 * and {@link BuilderWorldCheck} already fetches one here via {@code getSingleplayerServer()}. On a
 * multiplayer client there is no integrated server and every lookup answers empty, which degrades to
 * the ghosts simply not drawing — and a builder world cannot be reached from a multiplayer client
 * anyway.</p>
 *
 * <p><b>Never call this from a render pass.</b> The first read for a name does file or classpath I/O
 * and NBT decompression inside a synchronized store; afterwards it is a map hit. The ghost renderer
 * resolves on its tick sweep and the render pass only walks the result.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderGhostTemplates {

    /** Resolved cells per {@code (kind, name)} — the store caches too, this saves the re-unpack. */
    private static final Map<String, Map<BlockPos, BlockState>> CACHE = new HashMap<>();

    private BuilderGhostTemplates() {}

    /** Forget everything. Template files can change under a running client via the editor. */
    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    /**
     * The named template's blocks, local to its own origin, or empty when it can't be read.
     *
     * <p>Empty is a normal answer, not a failure: a kind may have no authored template at all, in
     * which case the caller draws nothing rather than inventing something.</p>
     */
    public static Map<BlockPos, BlockState> cells(TrackKind kind, String name, CarriageDims dims) {
        if (kind == null) {
            return Map.of();
        }
        String resolved = name == null || name.isEmpty() ? TrackKind.DEFAULT_NAME : name;
        String key = kind.id() + ":" + resolved;
        synchronized (CACHE) {
            Map<BlockPos, BlockState> cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        Map<BlockPos, BlockState> loaded = load(kind, resolved, dims);
        synchronized (CACHE) {
            CACHE.put(key, loaded);
        }
        return loaded;
    }

    /**
     * The blocks of whichever variant the world would roll for this kind.
     *
     * <p>The ghosts stand in for pieces the generator would have placed, and the generator picks a
     * name per position rather than always using {@code default} — so the preview asks the same
     * registry the same question. {@code index} is the generator's own key: the tile index for a
     * tile, the pillar's world X for a column.</p>
     */
    public static Map<BlockPos, BlockState> cellsForPick(TrackKind kind, long worldSeed, long index,
                                                         CarriageDims dims) {
        if (kind == null) {
            return Map.of();
        }
        return cells(kind, TrackVariantRegistry.pickName(kind, worldSeed, index), dims);
    }

    private static Map<BlockPos, BlockState> load(TrackKind kind, String name, CarriageDims dims) {
        ServerLevel level = serverLevel();
        if (level == null) {
            return Map.of();
        }
        return TrackVariantStore.get(level, kind, name, dims)
                .map(TemplateCells::of)
                .orElse(Map.of());
    }

    /**
     * The world's seed, or 0 when there is no integrated server.
     *
     * <p>The generator keys variant picks and the stairs anchor grid off it, so the preview has to
     * use the same one or it would show a line this world would never build.</p>
     */
    public static long worldSeed() {
        ServerLevel level = serverLevel();
        return level == null ? 0L : level.getSeed();
    }

    /** The integrated server's overworld, or null when this client isn't hosting one. */
    private static ServerLevel serverLevel() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        return server == null ? null : server.overworld();
    }
}
