package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.editor.TemplateCells;
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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
 * <p>Bounded and closed like every other mesh cache: past {@link #CAPACITY} the least recently drawn
 * build is dropped, and the whole thing goes on the way out of the screen.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RelayBuildPreviews {

    /** A page of the grid is a dozen tiles; this is a few pages of scrollback. */
    private static final int CAPACITY = 36;

    /** Asks allowed out at once. Each is a relay round trip made on the player's behalf. */
    private static final int MAX_IN_FLIGHT = 3;

    /** Bakes allowed per frame. One, like every other tile bake — see {@link BuilderTileMeshCache}. */
    private static final int BAKES_PER_FRAME = 1;

    /** How long a build whose fetch failed is left alone before it is asked about again. */
    private static final long RETRY_MILLIS = 5_000L;

    /** A baked build, or a build that will never have a picture ({@code mesh} null). */
    private record Entry(BuilderTileMesh mesh, TemplateSummary summary) {}

    /** Access-ordered, so eviction drops whatever nobody has looked at in the longest. */
    private static final Map<Integer, Entry> CACHE = new LinkedHashMap<>(16, 0.75F, true);

    /** Asked for and not yet answered. */
    private static final Set<Integer> IN_FLIGHT = new HashSet<>();

    /** Builds whose ask failed for a reason that may pass, and the moment they may be asked again. */
    private static final Map<Integer, Long> RETRY_AFTER = new HashMap<>();

    /** Answered and waiting for a frame with budget to bake in. */
    private static final Deque<Pending> PENDING = new ArrayDeque<>();

    private record Pending(int relayId, CompoundTag template) {}

    private static int bakesLeftThisFrame;

    private RelayBuildPreviews() {}

    /** Open the frame's bake budget, and spend it on whatever has arrived. Call once per render. */
    public static void beginFrame() {
        bakesLeftThisFrame = BAKES_PER_FRAME;
        while (bakesLeftThisFrame > 0 && !PENDING.isEmpty()) {
            bakesLeftThisFrame--;
            bake(PENDING.poll());
        }
    }

    /**
     * Ask for this build's blocks, unless they are already here, already asked for, or the queue is
     * full for this frame. Cheap to call for every visible tile, every frame — that is the point.
     */
    public static void request(int relayId, String ownerUuid, boolean live) {
        if (relayId <= 0 || CACHE.containsKey(relayId) || IN_FLIGHT.contains(relayId)) return;
        if (IN_FLIGHT.size() >= MAX_IN_FLIGHT) return;
        Long notBefore = RETRY_AFTER.get(relayId);
        if (notBefore != null && System.currentTimeMillis() < notBefore) return;
        IN_FLIGHT.add(relayId);
        DungeonTrainNet.sendToServer(new RelayBuildPreviewRequestPacket(relayId,
            ownerUuid == null ? "" : ownerUuid, live));
    }

    /**
     * An answer arrived: queue it for the next frame's bake, or remember that it has no picture.
     *
     * <p>A {@code retryable} miss is not remembered. A relay that timed out says nothing about the
     * build, and caching that answer would leave a tile blank for as long as the screen is open
     * because of one bad moment — it is asked again after {@link #RETRY_MILLIS}, which is long
     * enough that a relay having a hard time is not hammered by a grid full of tiles.</p>
     */
    public static void accept(int relayId, CompoundTag template, boolean retryable) {
        IN_FLIGHT.remove(relayId);
        if (template == null || template.isEmpty()) {
            if (retryable) {
                RETRY_AFTER.put(relayId, System.currentTimeMillis() + RETRY_MILLIS);
            } else {
                CACHE.put(relayId, new Entry(null, TemplateSummary.NONE));
                evictDown();
            }
            return;
        }
        RETRY_AFTER.remove(relayId);
        PENDING.add(new Pending(relayId, template));
    }

    /** Draw this build, or answer false while it is still coming — the caller draws its slate. */
    public static boolean draw(GuiGraphics g, int relayId, int x, int y, int w, int h,
                               float yaw, float fill) {
        Entry entry = CACHE.get(relayId);
        if (entry == null || entry.mesh() == null) return false;
        BuilderTileModelRenderer.render(g, entry.mesh(), x, y, w, h, yaw, fill);
        return true;
    }

    /** This build's data-sheet numbers, or null until it has been baked. */
    public static TemplateSummary summary(int relayId) {
        Entry entry = CACHE.get(relayId);
        return entry == null || entry.summary() == TemplateSummary.NONE ? null : entry.summary();
    }

    /** Whether an answer for this build is still out — what a tile draws "loading" for. */
    public static boolean waitingOn(int relayId) {
        return !CACHE.containsKey(relayId);
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
                        tally.containers(), TemplateCells.entityCount(pending.template())));
            } catch (RuntimeException e) {
                // A build this version cannot read keeps its slate rather than taking the screen down.
                entry = new Entry(null, TemplateSummary.NONE);
            }
        }
        CACHE.put(pending.relayId(), entry);
        evictDown();
    }

    private static HolderGetter<Block> blockRegistry() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? null : mc.level.registryAccess().lookupOrThrow(Registries.BLOCK);
    }

    private static void evictDown() {
        Iterator<Map.Entry<Integer, Entry>> it = CACHE.entrySet().iterator();
        while (CACHE.size() > CAPACITY && it.hasNext()) {
            Map.Entry<Integer, Entry> oldest = it.next();
            if (oldest.getValue().mesh() != null) oldest.getValue().mesh().close();
            it.remove();
        }
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
        CACHE.clear();
        PENDING.clear();
        IN_FLIGHT.clear();
        RETRY_AFTER.clear();
        bakesLeftThisFrame = 0;
    }
}
