package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.editor.BlockVariantMenuController;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server: mutate the carriage variant's block-variant cell at
 * {@code localPos} (or, for {@link Op#COPY}, request a clipboard item).
 *
 * <ul>
 *   <li>{@link Op#ADD} — append a new candidate. {@code stateString} is
 *       the BlockState string (e.g. {@code "minecraft:cobblestone"}) the
 *       player picked from the search screen; weight defaults to 1,
 *       locked false.</li>
 *   <li>{@link Op#REMOVE} — drop entry by {@code entryIndex}. If the
 *       remaining list shrinks below
 *       {@link games.brennan.dungeontrain.editor.CarriageVariantBlocks#MIN_STATES_PER_ENTRY}
 *       the cell is removed from the sidecar entirely.</li>
 *   <li>{@link Op#CLEAR} — drop the whole cell (sidecar entry).</li>
 *   <li>{@link Op#BUMP_WEIGHT} — adjust entry's weight by {@code delta}
 *       (signed; clamped ≥ 1 server-side).</li>
 *   <li>{@link Op#TOGGLE_LOCK} — flip entry's locked flag.</li>
 *   <li>{@link Op#COPY} — server gives the player a {@link
 *       games.brennan.dungeontrain.item.VariantClipboardItem} stack with
 *       the cell's current entries encoded in NBT. No sidecar mutation.</li>
 * </ul>
 *
 * <p>The server validates that the player is OP and is standing inside
 * the carriage variant's editor plot before mutating — matches the
 * existing slash-command authorisation model.</p>
 */
public record BlockVariantEditPacket(Op op, String variantId, BlockPos localPos,
                                     int entryIndex, String stateString, int delta) {

    public enum Op { ADD, REMOVE, CLEAR, BUMP_WEIGHT, TOGGLE_LOCK, COPY }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(op.ordinal());
        buf.writeUtf(variantId, 64);
        buf.writeVarInt(localPos.getX());
        buf.writeVarInt(localPos.getY());
        buf.writeVarInt(localPos.getZ());
        buf.writeVarInt(entryIndex);
        buf.writeUtf(stateString == null ? "" : stateString, 256);
        buf.writeVarInt(delta);
    }

    public static BlockVariantEditPacket decode(FriendlyByteBuf buf) {
        byte opOrd = buf.readByte();
        Op op = Op.values()[opOrd];
        String id = buf.readUtf(64);
        BlockPos local = new BlockPos(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        int idx = buf.readVarInt();
        String stateStr = buf.readUtf(256);
        int delta = buf.readVarInt();
        return new BlockVariantEditPacket(op, id, local, idx, stateStr, delta);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) return;
            BlockVariantMenuController.applyEdit(sender, this);
        });
        ctx.setPacketHandled(true);
    }
}
