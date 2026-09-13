package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.relay.BuilderRelayPreview;
import games.brennan.dungeontrain.editor.TemplateCells;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.StagePreviewPacket;
import games.brennan.dungeontrain.net.StagePreviewRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The Stages tab's models: a carriage as a stage would stamp it, asked of the server
 * ({@link StagePreviewRequestPacket}), baked once, and drawn from a small client cache.
 *
 * <p>The shape of {@link RelayBuildPreviews}, keyed by {@code (stage, carriage, seed)} instead of a
 * relay row: a new seed is a new picture (Refresh), and the last few stay baked so paging back to a
 * carriage costs nothing. Render thread only, like every mesh cache here.</p>
 */
public final class StagePreviews {

    /** How many baked stage models stay around; each is one carriage's worth of quads. */
    /** Room for the Stages list's tile view (one model per stage) plus a few rolls of the overview's. */
    private static final int CAPACITY = 48;
    private static final int BAKES_PER_FRAME = 1;

    /** One ask: which stage on which carriage at which roll. */
    public record Key(String stageId, String carriageId, long seed) {}

    /** A baked model, or an ask that will never have one ({@code mesh} null). */
    private record Entry(BuilderTileMesh mesh, TemplateSummary summary) {}

    private record Pending(Key key, CompoundTag template) {}

    /** Access-ordered, so eviction drops whatever nobody has looked at in the longest. */
    private static final Map<Key, Entry> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static final Set<Key> IN_FLIGHT = new HashSet<>();
    private static final Deque<Pending> PENDING = new ArrayDeque<>();
    private static int bakesLeftThisFrame;

    private StagePreviews() {}

    /** Open the frame's bake budget, and spend it on whatever has arrived. Call once per render. */
    public static void beginFrame() {
        bakesLeftThisFrame = BAKES_PER_FRAME;
        while (bakesLeftThisFrame > 0 && !PENDING.isEmpty()) {
            bakesLeftThisFrame--;
            bake(PENDING.poll());
        }
    }

    /** Ask for this model unless it is here or already asked for. Cheap to call every frame. */
    public static void request(Key key) {
        if (key == null || key.stageId().isEmpty() || key.carriageId().isEmpty()) return;
        if (CACHE.containsKey(key) || IN_FLIGHT.contains(key)) return;
        IN_FLIGHT.add(key);
        DungeonTrainNet.sendToServer(new StagePreviewRequestPacket(key.stageId(), key.carriageId(), key.seed()));
    }

    /** An answer arrived: queue it for the next frame's bake, or remember that it has no picture. */
    public static void accept(StagePreviewPacket packet) {
        Key key = new Key(packet.stageId(), packet.carriageId(), packet.seed());
        IN_FLIGHT.remove(key);
        CompoundTag tag = packet.found() ? BuilderRelayPreview.decode(packet.template()) : null;
        if (tag == null || tag.isEmpty()) {
            CACHE.put(key, new Entry(null, TemplateSummary.NONE));
            evictDown();
            return;
        }
        PENDING.add(new Pending(key, tag));
    }

    /** Draw this model, or answer false while it is still coming — the caller draws its slate. */
    public static boolean draw(GuiGraphics g, Key key, int x, int y, int w, int h, float yaw, float fill) {
        Entry entry = CACHE.get(key);
        if (entry == null || entry.mesh() == null) return false;
        BuilderTileModelRenderer.render(g, entry.mesh(), x, y, w, h, yaw, fill);
        return true;
    }

    /** This model's data-sheet numbers, or null until it has been baked. */
    public static TemplateSummary summary(Key key) {
        Entry entry = CACHE.get(key);
        return entry == null || entry.summary() == TemplateSummary.NONE ? null : entry.summary();
    }

    /** Whether an answer is still out — what the box draws "loading" for. */
    public static boolean waitingOn(Key key) {
        return !CACHE.containsKey(key);
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
                // A capture this version cannot read keeps its slate rather than taking the screen down.
                entry = new Entry(null, TemplateSummary.NONE);
            }
        }
        CACHE.put(pending.key(), entry);
        evictDown();
    }

    private static HolderGetter<Block> blockRegistry() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? null : mc.level.registryAccess().lookupOrThrow(Registries.BLOCK);
    }

    private static void evictDown() {
        Iterator<Map.Entry<Key, Entry>> it = CACHE.entrySet().iterator();
        while (CACHE.size() > CAPACITY && it.hasNext()) {
            Map.Entry<Key, Entry> oldest = it.next();
            if (oldest.getValue().mesh() != null) oldest.getValue().mesh().close();
            it.remove();
        }
    }

    /** Drop everything, closing the GPU buffers. Render thread only; on the way out of the screen. */
    public static void clear() {
        for (Entry entry : CACHE.values()) {
            if (entry.mesh() != null) entry.mesh().close();
        }
        CACHE.clear();
        PENDING.clear();
        IN_FLIGHT.clear();
    }
}
