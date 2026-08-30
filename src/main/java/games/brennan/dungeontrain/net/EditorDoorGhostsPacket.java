package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server → client snapshot of where the two portal corridor doors stand at each portal-room editor
 * plot. Drives the translucent door ghosts that show an author where the corridors open onto the
 * room they are building.
 *
 * <p>Each entry is a door's <b>lower</b> cell plus which of the room's two mouths it is; the
 * renderer draws the upper half from the block above it. One entry per door rather than one per
 * cell, because a door is one object — see
 * {@link games.brennan.dungeontrain.portal.PortalRoomDoorCells#doorBases}.</p>
 *
 * <p><b>The role rides with the position</b> rather than being inferred from the list's order. The
 * snapshot is flattened across every registered room, so parity only names the ends for as long as
 * every room contributes an exact pair — a degenerate box that contributes none would silently
 * relabel every door after it. The client draws the two mouths in different colours and words, so
 * getting this wrong is not a cosmetic slip: it would point an author at the far door.</p>
 *
 * <p>Positions are absolute, like {@link EditorStrayBlocksPacket}'s, and here they have to be: a
 * door cell sits one column <b>outside</b> its plot's box, so it has no plot-local coordinate. An
 * empty list clears the client cache — sent when the player leaves the portals category or turns the
 * ghosts off.</p>
 *
 * <p><b>Its own payload rather than a second list on the stray packet.</b> The two overlays mean
 * opposite things — a stray is a mistake to remove, a door is a fitting to build around — and are
 * drawn in different colours behind independent toggles. Folding them together would tie the amber
 * ghosts' refresh to the stray sweep's generation counter, which moves for reasons that have nothing
 * to do with where a door is.</p>
 *
 * <p>Sent by {@link games.brennan.dungeontrain.editor.VariantOverlayRenderer} with a per-player dedup
 * on {@link games.brennan.dungeontrain.editor.EditorDoorGhosts#key}, so a steady editor generates no
 * traffic at all.</p>
 */
public record EditorDoorGhostsPacket(List<Door> doors) implements CustomPacketPayload {

    /**
     * One door ghost: the <b>lower</b> cell it stands in, and whether it is the room's entry mouth
     * (the near, {@code -X} column) rather than its exit one.
     *
     * <p>A boolean and not a {@code PortalCarriageRole} because that is all the wire has to carry —
     * a door is one end or the other — and a boolean encodes without pinning the packet to the
     * ordinal of an enum that exists for a different purpose.</p>
     */
    public record Door(BlockPos base, boolean entry) {}

    public static final Type<EditorDoorGhostsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_door_ghosts"));

    public static final StreamCodec<FriendlyByteBuf, EditorDoorGhostsPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            EditorDoorGhostsPacket::decode
        );

    public static EditorDoorGhostsPacket empty() {
        return new EditorDoorGhostsPacket(Collections.emptyList());
    }

    public boolean isEmpty() {
        return doors.isEmpty();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(doors.size());
        for (Door door : doors) {
            buf.writeBlockPos(door.base());
            buf.writeBoolean(door.entry());
        }
    }

    public static EditorDoorGhostsPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Door> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BlockPos base = buf.readBlockPos();
            out.add(new Door(base, buf.readBoolean()));
        }
        return new EditorDoorGhostsPacket(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorDoorGhostsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
            games.brennan.dungeontrain.client.menu.EditorDoorGhostRenderer.applySnapshot(packet));
    }
}
