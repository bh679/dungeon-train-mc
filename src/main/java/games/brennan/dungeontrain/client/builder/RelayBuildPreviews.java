package games.brennan.dungeontrain.client.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.TemplateCells;
import games.brennan.dungeontrain.editor.TemplateLoot;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.RelayBuildPreviewRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pictures of builds that live on the relay rather than on this machine.
 *
 * <p>{@link BuilderTileMeshCache} bakes a tile from a template FILE; a builder's uploads have no
 * file here, which is why browsing somebody's profile used to be a wall of name plates. This is the
 * same bake fed from the wire instead: a tile scrolling into view asks the server for that build's
 * blocks ({@link RelayBuildPreviewRequestPacket}), the answer is baked once, and the mesh is drawn
 * from then on. Nothing is written to disk — loading a build into the world stays the deliberate
 * download it always was.</p>
 *
 * <p>Three things keep this from being expensive. Only tiles actually on screen ask. At most
 * {@link #MAX_IN_FLIGHT} asks are out at once, so a fast scroll queues rather than floods — each one
 * is an HTTP call the server makes to the relay. And an answer of "no picture" is remembered, so a
 * build that cannot be drawn is asked about once rather than every frame.</p>
 *
 * <p>Asks are not sent the moment a tile wants one. A frame's wishes are collected and the next frame
 * sends them in {@link RelayPreviewQueue}'s order: the selected build first, then every build on
 * screen as it is now, and only once all of those are here, their past versions.</p>
 *
 * <p>Bounded and closed like every other mesh cache. Pictures of builds as they are now and pictures
 * of past versions are held apart, so a long history can never push a tile's current picture out:
 * past {@link #CAPACITY} (or twice what is on screen) the least recently drawn current picture is
 * dropped; past {@link #VERSION_CEILING} versions, those of builds no longer on screen go first, and
 * background history stops rather than drop what is on screen. Everything goes on the way out of
 * the screen.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RelayBuildPreviews {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A page of the grid is a dozen tiles; this is a few pages of scrollback. */
    private static final int CAPACITY = 36;

    /** Past versions held at most — a safety ceiling, not a working budget. */
    static final int VERSION_CEILING = 256;

    /** Asks allowed out at once. Each is a relay round trip made on the player's behalf. */
    private static final int MAX_IN_FLIGHT = 3;

    /** Bakes allowed per frame. One, like every other tile bake — see {@link BuilderTileMeshCache}. */
    private static final int BAKES_PER_FRAME = 1;

    /** How long a build whose fetch failed is left alone before it is asked about again. */
    private static final long RETRY_MILLIS = 5_000L;

    /** A baked build, or a build that will never have a picture ({@code mesh} null). */
    private record Entry(BuilderTileMesh mesh, TemplateSummary summary) {}

    /** A build at a version: {@code seq} 0 is the build as it is now. */
    private record Key(int relayId, int seq) {}

    /** Builds as they are now (seq 0). Access-ordered, so eviction drops the longest unlooked-at. */
    private static final Map<Key, Entry> CACHE = new LinkedHashMap<>(16, 0.75F, true);

    /** Past versions (seq > 0), held apart from {@link #CACHE}. Access-ordered likewise. */
    private static final Map<Key, Entry> HISTORY = new LinkedHashMap<>(16, 0.75F, true);

    /** Asked for and not yet answered. */
    private static final Set<Key> IN_FLIGHT = new HashSet<>();

    /** Builds whose ask failed for a reason that may pass, and the moment they may be asked again. */
    private static final Map<Key, Long> RETRY_AFTER = new HashMap<>();

    /** Every seq a build's history holds, oldest first, once an ask for a version has answered. */
    private static final Map<Integer, int[]> VERSIONS = new HashMap<>();

    /** Answered and waiting for a frame with budget to bake in. */
    private static final Deque<Pending> PENDING = new ArrayDeque<>();

    private record Pending(Key key, CompoundTag template) {}

    private static int bakesLeftThisFrame;

    /** Who to ask on behalf of, for a build wished for this frame. */
    private record Wish(String ownerUuid, boolean live) {}

    /** Builds on screen this frame, in the order they were drawn. */
    private static final Map<Integer, Wish> WISHED = new LinkedHashMap<>();

    /** Builds on screen last frame — what the queue ran on, and what eviction spares. */
    private static List<Integer> onScreen = List.of();

    /** The picked build, its owner, and the version it is paged to; id 0 for none. */
    private static int selectedId;
    private static int selectedSeq;
    private static Wish selectedWish = new Wish("", false);
    private static boolean selectedThisFrame;

    private RelayBuildPreviews() {}

    /**
     * Open the frame's bake budget and spend it on whatever has arrived, then send last frame's
     * wishes in priority order. Call once per render, before anything is drawn.
     */
    public static void beginFrame() {
        bakesLeftThisFrame = BAKES_PER_FRAME;
        while (bakesLeftThisFrame > 0 && !PENDING.isEmpty()) {
            bakesLeftThisFrame--;
            bake(PENDING.poll());
        }
        dispatch();
    }

    /** Run the queue on what was wished for last frame, then start this frame's list afresh. */
    private static void dispatch() {
        onScreen = List.copyOf(WISHED.keySet());
        if (!selectedThisFrame) selectedId = 0;
        Map<Integer, Wish> wished = new LinkedHashMap<>(WISHED);
        WISHED.clear();
        selectedThisFrame = false;
        int slots = MAX_IN_FLIGHT - IN_FLIGHT.size();
        if (slots <= 0) return;
        List<RelayPreviewQueue.Ask> asks = RelayPreviewQueue.next(onScreen, selectedId, selectedSeq,
            QUEUE_STATE, slots, historyRoom());
        for (RelayPreviewQueue.Ask ask : asks) {
            Wish wish = ask.relayId() == selectedId ? selectedWish : wished.get(ask.relayId());
            if (wish == null) continue;
            send(ask.relayId(), wish, ask.seq());
            LOGGER.debug("[DungeonTrain] Relay preview ask: id={} seq={} tier={}",
                ask.relayId(), ask.seq(), ask.tier());
        }
    }

    private static void send(int relayId, Wish wish, int seq) {
        IN_FLIGHT.add(new Key(relayId, seq));
        DungeonTrainNet.sendToServer(new RelayBuildPreviewRequestPacket(relayId,
            wish.ownerUuid() == null ? "" : wish.ownerUuid(), wish.live(), seq));
    }

    /** The cache as the queue sees it. */
    private static final RelayPreviewQueue.State QUEUE_STATE = new RelayPreviewQueue.State() {
        @Override public boolean has(int relayId, int seq) {
            return seq == 0 ? CACHE.containsKey(new Key(relayId, 0))
                : HISTORY.containsKey(new Key(relayId, seq));
        }
        @Override public boolean inFlight(int relayId, int seq) {
            return IN_FLIGHT.contains(new Key(relayId, seq));
        }
        @Override public boolean backingOff(int relayId, int seq) {
            Long notBefore = RETRY_AFTER.get(new Key(relayId, seq));
            return notBefore != null && System.currentTimeMillis() < notBefore;
        }
        @Override public int[] versions(int relayId) {
            return VERSIONS.get(relayId);
        }
    };

    /** Whether background history may still be fetched: under the ceiling, or something to spare. */
    private static boolean historyRoom() {
        return HISTORY.size() < VERSION_CEILING || evictableVersion(true) != null;
    }

    /**
     * This build is on screen: wish for its picture, and once every build on screen has one, its
     * past versions. Cheap to call for every visible tile, every frame — that is the point; the next
     * frame decides what is actually sent.
     */
    public static void request(int relayId, String ownerUuid, boolean live) {
        if (relayId <= 0) return;
        WISHED.putIfAbsent(relayId, new Wish(ownerUuid == null ? "" : ownerUuid, live));
    }

    /**
     * This build is the one picked, paged to {@code seq} (0 for as it is now): its picture, its
     * version index and its whole history go ahead of everything else. Call every frame it stays
     * picked; a frame without a call un-picks it. {@code relayId <= 0} picks nothing.
     */
    public static void select(int relayId, String ownerUuid, boolean live, int seq) {
        if (relayId <= 0) return;
        selectedId = relayId;
        selectedSeq = Math.max(0, seq);
        selectedWish = new Wish(ownerUuid == null ? "" : ownerUuid, live);
        selectedThisFrame = true;
    }

    /** The seqs this build's history holds, oldest first, or null until a version ask has answered. */
    public static int[] versions(int relayId) {
        int[] v = VERSIONS.get(relayId);
        return v == null ? null : v.clone();
    }

    /**
     * An answer arrived: queue it for the next frame's bake, or remember that it has no picture.
     *
     * <p>A {@code retryable} miss is not remembered. A relay that timed out says nothing about the
     * build, and caching that answer would leave a tile blank for as long as the screen is open
     * because of one bad moment — it is asked again after {@link #RETRY_MILLIS}, which is long
     * enough that a relay having a hard time is not hammered by a grid full of tiles.</p>
     */
    public static void accept(int relayId, int seq, int[] seqs, CompoundTag template, boolean retryable) {
        // A version ask was sent as -1 and answered with the seq it resolved to; both keys are
        // released so neither the ask nor its answer stays "in flight" forever. Only a version
        // answer releases it: the build's current picture may come back while its index is out.
        if (seq != 0) IN_FLIGHT.remove(new Key(relayId, RelayPreviewQueue.INDEX));
        if (seqs != null && seqs.length > 0) VERSIONS.put(relayId, seqs.clone());
        else if (seq == RelayPreviewQueue.INDEX) VERSIONS.put(relayId, new int[0]);
        if (seq == RelayPreviewQueue.INDEX) return;   // no history: nothing to draw, nothing to hold
        Key key = new Key(relayId, seq);
        if (template == null || template.isEmpty()) {
            IN_FLIGHT.remove(key);
            if (retryable) {
                RETRY_AFTER.put(key, System.currentTimeMillis() + RETRY_MILLIS);
            } else {
                hold(key, new Entry(null, TemplateSummary.NONE));
            }
            return;
        }
        RETRY_AFTER.remove(key);
        // Still in flight until baked, or every waiting tile re-asks each frame until its turn. An
        // index answer arrives under the seq it resolved to, which was never asked for by name.
        IN_FLIGHT.add(key);
        PENDING.add(new Pending(key, template));
    }

    /**
     * Forget everything held for one build — its baked versions and its history — so the next ask
     * goes back to the relay. Called when a save of that build has just landed there: the cached
     * copy is the version before it, and the history is one entry short.
     *
     * <p>Render thread only — it closes the dropped meshes' buffers.</p>
     */
    public static void forget(int relayId) {
        if (relayId <= 0) return;
        for (Map<Key, Entry> map : List.of(CACHE, HISTORY)) {
            Iterator<Map.Entry<Key, Entry>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Key, Entry> e = it.next();
                if (e.getKey().relayId() != relayId) continue;
                if (e.getValue().mesh() != null) e.getValue().mesh().close();
                it.remove();
            }
        }
        IN_FLIGHT.removeIf(k -> k.relayId() == relayId);
        RETRY_AFTER.keySet().removeIf(k -> k.relayId() == relayId);
        PENDING.removeIf(p -> p.key().relayId() == relayId);
        VERSIONS.remove(relayId);
    }

    /** Draw this build, or answer false while it is still coming — the caller draws its slate. */
    public static boolean draw(GuiGraphics g, int relayId, int x, int y, int w, int h,
                               float yaw, float fill) {
        return draw(g, relayId, 0, x, y, w, h, yaw, fill);
    }

    /** As above at a version of the build; {@code seq} 0 is the build as it is now. */
    public static boolean draw(GuiGraphics g, int relayId, int seq, int x, int y, int w, int h,
                               float yaw, float fill) {
        Entry entry = held(new Key(relayId, seq));
        if (entry == null || entry.mesh() == null) return false;
        BuilderTileModelRenderer.render(g, entry.mesh(), x, y, w, h, yaw, fill);
        return true;
    }

    /** This build's baked mesh as it is now, or null until it has been baked — for a capture. */
    static BuilderTileMesh mesh(int relayId) {
        Entry entry = CACHE.get(new Key(relayId, 0));
        return entry == null ? null : entry.mesh();
    }

    /** This build's data-sheet numbers, or null until it has been baked. */
    public static TemplateSummary summary(int relayId) {
        return summary(relayId, 0);
    }

    public static TemplateSummary summary(int relayId, int seq) {
        Entry entry = held(new Key(relayId, seq));
        return entry == null || entry.summary() == TemplateSummary.NONE ? null : entry.summary();
    }

    /** Whether an answer for this build is still out — what a tile draws "loading" for. */
    public static boolean waitingOn(int relayId) {
        return !CACHE.containsKey(new Key(relayId, 0));
    }

    /** Turn the structure NBT into a mesh. Render thread, inside the frame's budget. */
    private static void bake(Pending pending) {
        if (pending == null) return;
        Entry entry = new Entry(null, TemplateSummary.NONE);
        HolderGetter<Block> blocks = blockRegistry();
        if (blocks != null) {
            try {
                StructureTemplate template = new StructureTemplate();
                template.load(blocks, pending.template());
                Map<BlockPos, BlockState> cells = TemplateCells.of(template);
                TemplateCells.NbtTally tally = TemplateCells.tallyBlockEntities(template);
                entry = new Entry(cells.isEmpty() ? null : BuilderTileMesh.bake(cells),
                    new TemplateSummary(cells.size(), template.getSize(), tally.blockEntities(),
                        tally.containers(), TemplateCells.entityCount(pending.template()),
                        TemplateCells.lights(cells), TemplateLoot.of(template)));
            } catch (RuntimeException e) {
                // A build this version cannot read keeps its slate rather than taking the screen down.
                entry = new Entry(null, TemplateSummary.NONE);
            }
        }
        IN_FLIGHT.remove(pending.key());
        hold(pending.key(), entry);
    }

    /** The cache this key lives in: current pictures and past versions are held apart. */
    private static Map<Key, Entry> mapFor(Key key) {
        return key.seq() == 0 ? CACHE : HISTORY;
    }

    private static Entry held(Key key) {
        return mapFor(key).get(key);
    }

    /** Keep an answer, closing whatever it replaces, and trim its cache back to size. */
    private static void hold(Key key, Entry entry) {
        Entry old = mapFor(key).put(key, entry);
        if (old != null && old != entry && old.mesh() != null) old.mesh().close();
        if (key.seq() == 0) evictDown();
        else evictHistory();
    }

    private static HolderGetter<Block> blockRegistry() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? null : mc.level.registryAccess().lookupOrThrow(Registries.BLOCK);
    }

    private static void evictDown() {
        int capacity = Math.max(CAPACITY, onScreen.size() * 2);
        Iterator<Map.Entry<Key, Entry>> it = CACHE.entrySet().iterator();
        while (CACHE.size() > capacity && it.hasNext()) {
            Map.Entry<Key, Entry> oldest = it.next();
            if (oldest.getValue().mesh() != null) oldest.getValue().mesh().close();
            it.remove();
        }
    }

    /**
     * Past the ceiling, drop the least recently drawn version of a build no longer on screen; failing
     * that, of any build but the selected one. Background history stops asking before it gets here
     * ({@link #historyRoom}), so this only makes room for the selected build's history.
     */
    private static void evictHistory() {
        while (HISTORY.size() > VERSION_CEILING) {
            Key victim = evictableVersion(true);
            if (victim == null) victim = evictableVersion(false);
            if (victim == null) return;
            Entry gone = HISTORY.remove(victim);
            if (gone != null && gone.mesh() != null) gone.mesh().close();
        }
    }

    /**
     * The least recently drawn version that may go: never the selected build's, and with
     * {@code offScreenOnly} never one of a build on screen either.
     */
    private static Key evictableVersion(boolean offScreenOnly) {
        for (Key key : HISTORY.keySet()) {
            if (key.relayId() == selectedId) continue;
            if (offScreenOnly && onScreen.contains(key.relayId())) continue;
            return key;
        }
        return null;
    }

    /**
     * Drop everything, closing the GPU buffers.
     *
     * <p>Render thread only. Called on the way out of the editor screen: these are somebody else's
     * builds and the next screen has no use for them.</p>
     */
    public static void clear() {
        for (Entry entry : CACHE.values()) {
            if (entry.mesh() != null) entry.mesh().close();
        }
        for (Entry entry : HISTORY.values()) {
            if (entry.mesh() != null) entry.mesh().close();
        }
        CACHE.clear();
        HISTORY.clear();
        WISHED.clear();
        onScreen = List.of();
        selectedId = 0;
        selectedSeq = 0;
        selectedThisFrame = false;
        PENDING.clear();
        IN_FLIGHT.clear();
        RETRY_AFTER.clear();
        VERSIONS.clear();
        bakesLeftThisFrame = 0;
    }
}
