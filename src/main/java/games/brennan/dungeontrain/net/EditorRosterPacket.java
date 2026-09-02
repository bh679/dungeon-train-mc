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
 */
public record EditorRosterPacket(List<Group> groups, String stampedCategoryId)
    implements CustomPacketPayload {

    /** A type strip entry: the templates of one kind within one category. */
    public record Group(String categoryId, String typeName, String modelId, List<Entry> entries) {
        public Group {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /** One template row plus its group's self weight ({@code NO_WEIGHT} when it is no group). */
    public record Entry(EditorTypeMenusPacket.Variant variant, int selfWeight) {}

    public static final Type<EditorRosterPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_roster"));

    public static final StreamCodec<FriendlyByteBuf, EditorRosterPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> packet.encode(buf), EditorRosterPacket::decode);

    public EditorRosterPacket {
        groups = groups == null ? List.of() : List.copyOf(groups);
        if (stampedCategoryId == null) stampedCategoryId = "";
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(stampedCategoryId, 32);
        buf.writeVarInt(groups.size());
        for (Group g : groups) {
            buf.writeUtf(g.categoryId(), 32);
            buf.writeUtf(g.typeName(), 64);
            buf.writeUtf(g.modelId(), 64);
            buf.writeVarInt(g.entries().size());
            for (Entry e : g.entries()) {
                EditorTypeMenusPacket.encodeVariant(buf, e.variant());
                buf.writeVarInt(e.selfWeight());
            }
        }
    }

    public static EditorRosterPacket decode(FriendlyByteBuf buf) {
        String stamped = buf.readUtf(32);
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
                entries.add(new Entry(v, buf.readVarInt()));
            }
            groups.add(new Group(categoryId, typeName, modelId, entries));
        }
        return new EditorRosterPacket(groups, stamped);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorRosterPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> EditorRosterClient.apply(packet));
    }
}
