package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.editorscreen.EditorRosterClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: every template in every editor category, for the inventory-style editor
 * screen. The reply to {@link EditorRosterRequestPacket}.
 *
 * <p>Rows reuse {@link EditorTypeMenusPacket.Variant} and its codec, so a template reads the same
 * here as on the world-space panels. Each is wrapped in an {@link Entry} carrying the one thing
 * the panels compute elsewhere — a group parent's self weight.</p>
 *
 * @param groups            one per type strip entry, in tab and strip order
 * @param stampedCategoryId the lowercase id of the category whose plots are stamped right now,
 *                          or {@code ""} when none is; the screen routes cross-category enters on it
 * @param trainSize         the world's carriage footprint. It rides here because it is a fact about
 *                          the editor as a whole rather than about any one template, and because
 *                          the client is otherwise told it only while a world is being created —
 *                          which is no use to an author editing one that already exists
 * @param stages            every Stage with the blocks its linked parts use, for the screen's
 *                          Stages tab. Rides here rather than on the world-space type-menu snapshot
 *                          because that snapshot exists only at plot height, and the screen opens
 *                          anywhere in the editor world
 */
public record EditorRosterPacket(List<Group> groups, String stampedCategoryId, TrainSize trainSize,
                                 List<StageEntry> stages, TunnelGroups tunnelGroups, Layout layout)
    implements CustomPacketPayload {

    /**
     * How often each way of filling a group is drawn — Rooms ×3, Halves ×2, Group ×1 — for the
     * Settings tab's Carriage layout rows. {@link #UNKNOWN} until a server has sent it.
     */
    public record Layout(int rooms, int halves, int group) {
        public static final Layout UNKNOWN = new Layout(-1, -1, -1);

        public boolean isKnown() {
            return rooms >= 0 && halves >= 0 && group >= 0;
        }

        public int total() {
            return Math.max(0, rooms) + Math.max(0, halves) + Math.max(0, group);
        }
    }

    /**
     * The tunnel template groups the editor can offer — every registered group plus any a template
     * already names — each with the weight a tunnel rolls it at, and the ungrouped pool's weight.
     */
    public record TunnelGroups(java.util.Map<String, Integer> weights, int ungroupedWeight, List<Member> members,
                               java.util.Map<String, Gate> gates) {
        public static final TunnelGroups EMPTY = new TunnelGroups(java.util.Map.of(), 1, List.of(), java.util.Map.of());

        /**
         * A group's spawn gate as a template row sends it: the <b>effective</b> Diff-Level band and
         * phase mask (its Stage's when linked), and the Stage id ({@code ""} = Custom).
         */
        public record Gate(int minLevel, int maxLevel, int phaseMask, String stageId) {
            public Gate {
                stageId = stageId == null ? "" : stageId;
            }

            public boolean linked() {
                return !stageId.isEmpty();
            }
        }

        /** The shape from before groups carried gates. */
        public TunnelGroups(java.util.Map<String, Integer> weights, int ungroupedWeight, List<Member> members) {
            this(weights, ungroupedWeight, members, java.util.Map.of());
        }

        /** {@code id}'s gate, or null when none travelled (an ungated group reads as every band). */
        public Gate gateOf(String id) {
            return gates.get(id);
        }

        /**
         * One tunnel template in one group, at the weight it draws at there. {@code groupId} is
         * {@code ""} for the ungrouped pool; {@code kindId} is {@code tunnel_section} / {@code tunnel_portal}.
         */
        public record Member(String groupId, String kindId, String name, int weight) {}

        public TunnelGroups {
            weights = weights == null ? java.util.Map.of() : java.util.Map.copyOf(new java.util.TreeMap<>(weights));
            members = members == null ? List.of() : List.copyOf(members);
            gates = gates == null ? java.util.Map.of() : java.util.Map.copyOf(gates);
        }

        /** The shape from before members travelled — weights only. */
        public TunnelGroups(java.util.Map<String, Integer> weights, int ungroupedWeight) {
            this(weights, ungroupedWeight, List.of());
        }

        /** The members of {@code groupId} ({@code ""} = ungrouped) of kind {@code kindId}, in order. */
        public List<Member> membersOf(String groupId, String kindId) {
            List<Member> out = new ArrayList<>();
            for (Member m : members) if (m.groupId().equals(groupId) && m.kindId().equals(kindId)) out.add(m);
            return out;
        }

        /** Group ids in sorted order. */
        public List<String> ids() {
            return List.copyOf(new java.util.TreeSet<>(weights.keySet()));
        }
    }

    /**
     * One Stage: its gate as the same {@link EditorTypeMenusPacket.Variant} the world-space Stages
     * panel lists it as ({@code modelId} = stage id, {@code name} = display name), plus the blocks
     * its linked carriage parts use — usage-ordered and capped at
     * {@link StageBlocksSyncPacket#BLOCKS_CAP}, the same definition as the Stage Blocks panel.
     *
     * @param totalUnique the real distinct-block count, so a capped list can still say "+K"
     * @param parts       the carriage parts that link to the stage, as {@code <kind id>:<name>}
     *                    (the key the part commands take), in the index's stable order
     */
    public record StageEntry(EditorTypeMenusPacket.Variant stage, List<StageBlocksSyncPacket.BlockCount> blocks,
                             int totalUnique, List<String> parts, Palette palette) {
        public StageEntry {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            parts = parts == null ? List.of() : List.copyOf(parts);
            palette = palette == null ? Palette.NONE : palette;
        }

        /** The pre-palette shape. */
        public StageEntry(EditorTypeMenusPacket.Variant stage, List<StageBlocksSyncPacket.BlockCount> blocks,
                          int totalUnique, List<String> parts) {
            this(stage, blocks, totalUnique, parts, Palette.NONE);
        }

        public int partCount() {
            return parts.size();
        }

        public String id() {
            return stage.modelId();
        }

        public String name() {
            return stage.name();
        }
    }

    /** How long, wide and tall every carriage in this world is. */
    public record TrainSize(int length, int width, int height) {
        /** Unknown — the screen shows the template's measured size instead. */
        public static final TrainSize UNKNOWN = new TrainSize(0, 0, 0);

        public boolean isKnown() {
            return length > 0 && width > 0 && height > 0;
        }
    }

    /** A type strip entry: the templates of one kind within one category. */
    public record Group(String categoryId, String typeName, String modelId, List<Entry> entries) {
        public Group {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /** One template row plus its group's self weight ({@code NO_WEIGHT} when it is no group). */
    /**
     * One template, with the weight its own tile carries inside a group and — when this install has
     * uploaded it — the relay row it lives in, so the previewer can page through the versions the
     * relay recorded. {@code relayId} is 0 for a template the relay has never seen.
     */
    public record Entry(EditorTypeMenusPacket.Variant variant, int selfWeight, int relayId,
                        String roomMode, int roomLength, int roomWidth, int roomHeight, int flipMask,
                        String shellSize, int shellWins) {
        /** {@link #shellSize} for every row that is not a carriage template. */
        public static final String NO_SHELL_SIZE = "";
        /** {@link #shellWins} for a row with no "carriage blocks win" switch. */
        public static final int NO_SHELL_WINS = 0;
        public static final int SHELL_WINS_OFF = 1;
        public static final int SHELL_WINS_ON = 2;

        public Entry {
            if (roomMode == null || roomMode.isEmpty()) roomMode = EditorStatusPacket.NO_MODE;
            if (shellSize == null) shellSize = NO_SHELL_SIZE;
        }

        /** The shape from before the "carriage blocks win" switch rode along. */
        public Entry(EditorTypeMenusPacket.Variant variant, int selfWeight, int relayId,
                     String roomMode, int roomLength, int roomWidth, int roomHeight, int flipMask,
                     String shellSize) {
            this(variant, selfWeight, relayId, roomMode, roomLength, roomWidth, roomHeight, flipMask,
                shellSize, NO_SHELL_WINS);
        }

        public Entry(EditorTypeMenusPacket.Variant variant, int selfWeight) {
            this(variant, selfWeight, 0);
        }

        /** The three-field shape from before the screen could edit a room it is not stood in. */
        public Entry(EditorTypeMenusPacket.Variant variant, int selfWeight, int relayId) {
            this(variant, selfWeight, relayId, EditorStatusPacket.NO_MODE, EditorStatusPacket.NO_SIZE,
                EditorStatusPacket.NO_SIZE, EditorStatusPacket.NO_SIZE, EditorStatusPacket.NO_FLIP, NO_SHELL_SIZE);
        }

        /**
         * A portal room's settings tag and box, so the detail pane can show the room's rows for a
         * selection the author is not standing in. Same sentinels as {@link EditorStatusPacket},
         * which is what the pane read those rows from before.
         */
        public Entry withRoom(String mode, int length, int width, int height) {
            return new Entry(variant, selfWeight, relayId, mode, length, width, height, flipMask, shellSize, shellWins);
        }

        /** A contents template's random-flip axes, packed as {@link EditorStatusPacket#flipMaskOf}. */
        public Entry withFlipMask(int mask) {
            return new Entry(variant, selfWeight, relayId, roomMode, roomLength, roomWidth, roomHeight, mask, shellSize, shellWins);
        }

        /** A carriage template's size key ({@code ContentsSize#key}) — Room, Half or Group. */
        public Entry withShellSize(String size) {
            return new Entry(variant, selfWeight, relayId, roomMode, roomLength, roomWidth, roomHeight, flipMask, size, shellWins);
        }

        /** Whether a carriage template keeps its own blocks against its contents. */
        public Entry withShellWins(boolean wins) {
            return new Entry(variant, selfWeight, relayId, roomMode, roomLength, roomWidth, roomHeight, flipMask,
                shellSize, wins ? SHELL_WINS_ON : SHELL_WINS_OFF);
        }

        /** True when this row is a portal room whose tag and box rode along. */
        public boolean hasRoom() {
            return !EditorStatusPacket.NO_MODE.equals(roomMode);
        }
    }

    /**
     * A stage's placeholder palette as the Stage Palette panel reads it: every placeholder's
     * effective block, the two family ids and whether the author locked them. {@link #NONE} for a
     * roster built with no world to bake from.
     */
    public record Palette(List<StagePaletteSyncPacket.Entry> entries, String wood, String stone,
                          boolean woodLocked, boolean stoneLocked) {
        public static final Palette NONE = new Palette(List.of(), "", "", false, false);

        public Palette {
            entries = entries == null ? List.of() : List.copyOf(entries);
            wood = wood == null ? "" : wood;
            stone = stone == null ? "" : stone;
        }

        /** The entry for a placeholder name, or null. */
        public StagePaletteSyncPacket.Entry entry(String name) {
            for (StagePaletteSyncPacket.Entry e : entries) {
                if (e.name().equals(name)) return e;
            }
            return null;
        }
    }

    public static final Type<EditorRosterPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_roster"));

    public static final StreamCodec<FriendlyByteBuf, EditorRosterPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> packet.encode(buf), EditorRosterPacket::decode);

    public EditorRosterPacket {
        groups = groups == null ? List.of() : List.copyOf(groups);
        if (stampedCategoryId == null) stampedCategoryId = "";
        if (trainSize == null) trainSize = TrainSize.UNKNOWN;
        stages = stages == null ? List.of() : List.copyOf(stages);
        if (tunnelGroups == null) tunnelGroups = TunnelGroups.EMPTY;
        if (layout == null) layout = Layout.UNKNOWN;
    }

    /** The shape from before carriage layouts: no layout weights. */
    public EditorRosterPacket(List<Group> groups, String stampedCategoryId, TrainSize trainSize,
                              List<StageEntry> stages, TunnelGroups tunnelGroups) {
        this(groups, stampedCategoryId, trainSize, stages, tunnelGroups, Layout.UNKNOWN);
    }

    /** The shape from before tunnel groups: a roster with no group registry. */
    public EditorRosterPacket(List<Group> groups, String stampedCategoryId, TrainSize trainSize,
                              List<StageEntry> stages) {
        this(groups, stampedCategoryId, trainSize, stages, TunnelGroups.EMPTY);
    }

    /** The shape from before the Stages tab: a roster with no stage list. */
    public EditorRosterPacket(List<Group> groups, String stampedCategoryId, TrainSize trainSize) {
        this(groups, stampedCategoryId, trainSize, List.of());
    }

    /** Convenience for call sites with no world to read a footprint from. */
    public EditorRosterPacket(List<Group> groups, String stampedCategoryId) {
        this(groups, stampedCategoryId, TrainSize.UNKNOWN);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(stampedCategoryId, 32);
        buf.writeVarInt(trainSize.length());
        buf.writeVarInt(trainSize.width());
        buf.writeVarInt(trainSize.height());
        buf.writeVarInt(groups.size());
        for (Group g : groups) {
            buf.writeUtf(g.categoryId(), 32);
            buf.writeUtf(g.typeName(), 64);
            buf.writeUtf(g.modelId(), 64);
            buf.writeVarInt(g.entries().size());
            for (Entry e : g.entries()) {
                EditorTypeMenusPacket.encodeVariant(buf, e.variant());
                buf.writeVarInt(e.selfWeight());
                buf.writeVarInt(e.relayId());
                buf.writeUtf(e.roomMode(), EditorStatusPacket.MODE_TAG_MAX);
                buf.writeVarInt(e.roomLength());
                buf.writeVarInt(e.roomWidth());
                buf.writeVarInt(e.roomHeight());
                buf.writeVarInt(e.flipMask());
                buf.writeUtf(e.shellSize(), 16);
                buf.writeVarInt(e.shellWins());
            }
        }
        buf.writeVarInt(stages.size());
        for (StageEntry s : stages) {
            EditorTypeMenusPacket.encodeVariant(buf, s.stage());
            buf.writeVarInt(s.blocks().size());
            for (StageBlocksSyncPacket.BlockCount b : s.blocks()) {
                buf.writeUtf(b.blockId(), 256);
                buf.writeVarInt(b.count());
            }
            buf.writeVarInt(s.totalUnique());
            buf.writeVarInt(s.parts().size());
            for (String part : s.parts()) buf.writeUtf(part, 128);
            Palette pal = s.palette();
            buf.writeVarInt(pal.entries().size());
            for (StagePaletteSyncPacket.Entry e : pal.entries()) {
                buf.writeUtf(e.name(), 64);
                buf.writeUtf(e.blockId(), 256);
                buf.writeBoolean(e.overridden());
            }
            buf.writeUtf(pal.wood(), 32);
            buf.writeUtf(pal.stone(), 32);
            buf.writeBoolean(pal.woodLocked());
            buf.writeBoolean(pal.stoneLocked());
        }
        buf.writeVarInt(tunnelGroups.ungroupedWeight());
        List<String> ids = tunnelGroups.ids();
        buf.writeVarInt(ids.size());
        for (String id : ids) {
            buf.writeUtf(id, 64);
            buf.writeVarInt(tunnelGroups.weights().get(id));
        }
        buf.writeVarInt(tunnelGroups.members().size());
        for (TunnelGroups.Member m : tunnelGroups.members()) {
            buf.writeUtf(m.groupId(), 64);
            buf.writeUtf(m.kindId(), 32);
            buf.writeUtf(m.name(), 128);
            buf.writeVarInt(m.weight());
        }
        buf.writeVarInt(tunnelGroups.gates().size());
        for (var e : new java.util.TreeMap<>(tunnelGroups.gates()).entrySet()) {
            buf.writeUtf(e.getKey(), 64);
            buf.writeVarInt(e.getValue().minLevel());
            buf.writeVarInt(e.getValue().maxLevel());
            buf.writeVarInt(e.getValue().phaseMask());
            buf.writeUtf(e.getValue().stageId(), 64);
        }
        buf.writeVarInt(layout.rooms());
        buf.writeVarInt(layout.halves());
        buf.writeVarInt(layout.group());
    }

    public static EditorRosterPacket decode(FriendlyByteBuf buf) {
        String stamped = buf.readUtf(32);
        TrainSize trainSize = new TrainSize(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        int n = buf.readVarInt();
        List<Group> groups = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String categoryId = buf.readUtf(32);
            String typeName = buf.readUtf(64);
            String modelId = buf.readUtf(64);
            int en = buf.readVarInt();
            List<Entry> entries = new ArrayList<>(en);
            for (int j = 0; j < en; j++) {
                EditorTypeMenusPacket.Variant v = EditorTypeMenusPacket.decodeVariant(buf);
                entries.add(new Entry(v, buf.readVarInt(), buf.readVarInt(),
                    buf.readUtf(EditorStatusPacket.MODE_TAG_MAX), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readUtf(16), buf.readVarInt()));
            }
            groups.add(new Group(categoryId, typeName, modelId, entries));
        }
        int ns = buf.readVarInt();
        List<StageEntry> stages = new ArrayList<>(ns);
        for (int i = 0; i < ns; i++) {
            EditorTypeMenusPacket.Variant stage = EditorTypeMenusPacket.decodeVariant(buf);
            int nb = buf.readVarInt();
            List<StageBlocksSyncPacket.BlockCount> blocks = new ArrayList<>(nb);
            for (int j = 0; j < nb; j++) {
                blocks.add(new StageBlocksSyncPacket.BlockCount(buf.readUtf(256), buf.readVarInt()));
            }
            int totalUnique = buf.readVarInt();
            int np = buf.readVarInt();
            List<String> parts = new ArrayList<>(np);
            for (int j = 0; j < np; j++) parts.add(buf.readUtf(128));
            int ne = buf.readVarInt();
            List<StagePaletteSyncPacket.Entry> entries = new ArrayList<>(ne);
            for (int j = 0; j < ne; j++) {
                entries.add(new StagePaletteSyncPacket.Entry(buf.readUtf(64), buf.readUtf(256), buf.readBoolean()));
            }
            Palette palette = new Palette(entries, buf.readUtf(32), buf.readUtf(32), buf.readBoolean(), buf.readBoolean());
            stages.add(new StageEntry(stage, blocks, totalUnique, parts, palette));
        }
        int ungroupedWeight = buf.readVarInt();
        int ng = buf.readVarInt();
        java.util.Map<String, Integer> weights = new java.util.TreeMap<>();
        for (int i = 0; i < ng; i++) weights.put(buf.readUtf(64), buf.readVarInt());
        int nm = buf.readVarInt();
        List<TunnelGroups.Member> members = new ArrayList<>(nm);
        for (int i = 0; i < nm; i++) {
            members.add(new TunnelGroups.Member(buf.readUtf(64), buf.readUtf(32), buf.readUtf(128), buf.readVarInt()));
        }
        int ngt = buf.readVarInt();
        java.util.Map<String, TunnelGroups.Gate> gates = new java.util.TreeMap<>();
        for (int i = 0; i < ngt; i++) {
            gates.put(buf.readUtf(64), new TunnelGroups.Gate(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readUtf(64)));
        }
        Layout layout = new Layout(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        return new EditorRosterPacket(groups, stamped, trainSize, stages,
            new TunnelGroups(weights, ungroupedWeight, members, gates), layout);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorRosterPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorRosterClient.apply(packet));
    }
}
