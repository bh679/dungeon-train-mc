package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server → client: the mob ghosts to draw over the variant cells of the plot the player stands in —
 * one per cell whose variant pool holds a mob entry, while the editor's Mobs setting is Blocks.
 *
 * <p>Pushed by {@code VariantOverlayRenderer.pushMobGhostsSnapshot} whenever the plot or its variant
 * index moves; an empty packet clears the overlay. Same shape as {@link EditorDoorGhostsPacket}.</p>
 */
public record EditorMobGhostsPacket(List<Ghost> ghosts) implements CustomPacketPayload {

    /** One ghost: the cell it stands in, the entity id, and the entry's entity NBT if it has any. */
    public record Ghost(BlockPos cell, String entityId, @Nullable CompoundTag nbt) {}

    public static final Type<EditorMobGhostsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_mob_ghosts"));

    public static final StreamCodec<FriendlyByteBuf, EditorMobGhostsPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            EditorMobGhostsPacket::decode
        );

    public static EditorMobGhostsPacket empty() {
        return new EditorMobGhostsPacket(Collections.emptyList());
    }

    public boolean isEmpty() {
        return ghosts.isEmpty();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(ghosts.size());
        for (Ghost ghost : ghosts) {
            buf.writeBlockPos(ghost.cell());
            buf.writeUtf(ghost.entityId());
            buf.writeNbt(ghost.nbt() == null ? new CompoundTag() : ghost.nbt());
        }
    }

    public static EditorMobGhostsPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Ghost> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BlockPos cell = buf.readBlockPos();
            String id = buf.readUtf();
            CompoundTag nbt = buf.readNbt();
            out.add(new Ghost(cell, id, nbt == null || nbt.isEmpty() ? null : nbt));
        }
        return new EditorMobGhostsPacket(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorMobGhostsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
            games.brennan.dungeontrain.client.menu.EditorMobGhostRenderer.applySnapshot(packet));
    }
}
