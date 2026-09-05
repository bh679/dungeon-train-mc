package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Baked tile meshes, kept for the session and built at a strict pace.
 *
 * <p>Two jobs. It remembers what has been baked, so hovering a tile costs a matrix multiply rather
 * than a re-tesselation. And it rations the baking: <b>one template per frame</b>, because reading
 * an NBT off disk and walking a few thousand block models is tens of milliseconds and nine of them
 * in one frame is a visible lurch every time you scroll. A tile whose mesh isn't ready yet draws
 * its photo, so a fresh page fills in over the next handful of frames instead of stalling.</p>
 *
 * <p>Entries are held even when they resolve to nothing — a category tile, an unreadable file, a
 * template over the size cap. Without that the miss would be re-attempted every single frame, which
 * is the one thing more expensive than baking it.</p>
 *
 * <p>Bounded, because these are GPU buffers: past {@link #CAPACITY} the least recently drawn tile
 * is closed and dropped. Everything is thrown away on logout (the next world may be a different
 * install with different templates behind the same names) and on a resource reload (baked quads
 * hold texture-atlas UVs, and a reload moves them).</p>
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BuilderTileMeshCache {

    /** Nine tiles are on screen at once; this is a few screens' worth of scrollback. */
    private static final int CAPACITY = 48;

    /** Templates baked per frame. One. See the class note. */
    private static final int BAKES_PER_FRAME = 1;

    /** What a tile is: the same four things that find its photo. */
    private record Key(BuilderPhotoPaths.Kind kind, String id,
                       CarriagePartKind partKind, TrackKind trackKind) {}

    /** Wrapper so a resolved-to-nothing entry is distinguishable from an absent one. */
    private record Entry(BuilderTileMesh mesh, TemplateSummary summary) {}

    /** Access-ordered, so eviction drops the tile nobody has looked at in the longest. */
    private static final Map<Key, Entry> CACHE = new LinkedHashMap<>(16, 0.75F, true);

    private static int bakesLeftThisFrame;

    private BuilderTileMeshCache() {}

    /** Open the frame's bake budget. Call once per screen render, before drawing any tiles. */
    static void beginFrame() {
        beginFrame(BAKES_PER_FRAME);
    }

    /**
     * Open the frame's bake budget at an explicit size.
     *
     * <p>A grid of small square tiles can afford two: each bake is still tens of milliseconds, so
     * anything higher is a lurch again.</p>
     */
    static void beginFrame(int bakes) {
        bakesLeftThisFrame = Math.max(0, bakes);
    }

    /**
     * This tile's mesh, baking it if there's budget left, or null when there is nothing to draw
     * yet or ever.
     *
     * <p>Null is not a failure and callers must not treat it as one — it is "use the flat art this
     * frame". A tile that will never have a mesh answers null forever; a tile still waiting for its
     * turn answers null now and a mesh a frame or two later.</p>
     */
    static BuilderTileMesh meshFor(BuilderPhotoPaths.Kind kind, String id,
                                   CarriagePartKind partKind, TrackKind trackKind) {
        if (kind == null || id == null || id.isEmpty()) {
            return null;
        }
        Entry entry = resolve(new Key(kind, id, partKind, trackKind));
        return entry == null ? null : entry.mesh();
    }

    /**
     * This tile's data-sheet numbers, or null while its bake is still queued.
     *
     * <p>Shares the bake with {@link #meshFor}: the file is read once and both answers are kept,
     * so a sheet beside a drawn tile costs a map lookup.</p>
     */
    static TemplateSummary summaryFor(BuilderPhotoPaths.Kind kind, String id,
                                      CarriagePartKind partKind, TrackKind trackKind) {
        if (kind == null || id == null || id.isEmpty()) {
            return null;
        }
        Entry entry = resolve(new Key(kind, id, partKind, trackKind));
        return entry == null ? null : entry.summary();
    }

    /** The cached entry, baking it within this frame's budget, or null when it must wait. */
    private static Entry resolve(Key key) {
        Entry cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (bakesLeftThisFrame <= 0) {
            return null;
        }
        bakesLeftThisFrame--;

        BuilderTileTemplates.Loaded loaded = BuilderTileTemplates.load(
                key.kind(), key.id(), key.partKind(), key.trackKind());
        Map<BlockPos, BlockState> cells = loaded.cells();
        BuilderTileMesh mesh = cells.isEmpty() ? null : BuilderTileMesh.bake(cells);
        Entry entry = new Entry(mesh, loaded.summary());
        CACHE.put(key, entry);
        evictDown();
        return entry;
    }

    /**
     * Drop everything, closing the GPU buffers.
     *
     * <p>Render thread only — it closes {@code VertexBuffer}s.</p>
     */
    public static void clear() {
        for (Entry entry : CACHE.values()) {
            if (entry.mesh() != null) {
                entry.mesh().close();
            }
        }
        CACHE.clear();
    }

    /**
     * Trim to {@link #CAPACITY}, closing what goes.
     *
     * <p>Written out rather than done with {@code removeEldestEntry} because the eldest entry has a
     * GPU buffer to close, and that override gets no clean way to do it.</p>
     */
    private static void evictDown() {
        Iterator<Entry> it = CACHE.values().iterator();
        while (CACHE.size() > CAPACITY && it.hasNext()) {
            Entry entry = it.next();
            if (entry.mesh() != null) {
                entry.mesh().close();
            }
            it.remove();
        }
    }

    /**
     * A resource reload invalidates every baked quad: they carry texture-atlas UVs, and the reload
     * rebuilds the atlas somewhere else.
     */
    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> clear());
    }
}
