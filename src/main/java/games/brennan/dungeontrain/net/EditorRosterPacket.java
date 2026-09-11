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
 */
public record EditorRosterPacket(List<Group> groups, String stampedCategoryId, TrainSize trainSize)
    implements CustomPacketPayload {

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
                        String roomMode, int roomLength, int roomWidth, int roomHeight, int flipMask) {
        public Entry {
            if (roomMode == null || roomMode.isEmpty()) roomMode = EditorStatusPacket.NO_MODE;
        }

        public Entry(EditorTypeMenusPacket.Variant variant, int selfWeight) {
            this(variant, selfWeight, 0);
        }

        /** The three-field shape from before the screen could edit a room it is not stood in. */
        public Entry(EditorTypeMenusPacket.Variant variant, int selfWeight, int relayId) {
            this(variant, selfWeight, relayId, EditorStatusPacket.NO_MODE, EditorStatusPacket.NO_SIZE,
                EditorStatusPacket.NO_SIZE, EditorStatusPacket.NO_SIZE, EditorStatusPacket.NO_FLIP);
        }

        /**
         * A portal room's settings tag and box, so the detail pane can show the room's rows for a
         * selection the author is not standing in. Same sentinels as {@link EditorStatusPacket},
         * which is what the pane read those rows from before.
         */
        public Entry withRoom(String mode, int length, int width, int height) {
            return new Entry(variant, selfWeight, relayId, mode, length, width, height, flipMask);
        }

        /** A contents template's random-flip axes, packed as {@link EditorStatusPacket#flipMaskOf}. */
        public Entry withFlipMask(int mask) {
            return new Entry(variant, selfWeight, relayId, roomMode, roomLength, roomWidth, roomHeight, mask);
        }

        /** True when this row is a portal room whose tag and box rode along. */
        public boolean hasRoom() {
            return !EditorStatusPacket.NO_MODE.equals(roomMode);
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
            }
        }
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
                    buf.readVarInt(), buf.readVarInt()));
            }
            groups.add(new Group(categoryId, typeName, modelId, entries));
        }
        return new EditorRosterPacket(groups, stamped, trainSize);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorRosterPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorRosterClient.apply(packet));
    }
}
