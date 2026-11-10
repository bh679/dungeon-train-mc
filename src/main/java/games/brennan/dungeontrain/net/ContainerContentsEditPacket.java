package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.ContainerContentsMenuController;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: mutate the container-contents pool at
 * {@code (plotKey, localPos)}.
 *
 * <ul>
 *   <li>{@link Op#ADD} — append an entry. {@code itemId} is the item
 *       registry name (e.g. {@code "minecraft:diamond"}); count/weight
 *       default to 1.</li>
 *   <li>{@link Op#REMOVE} — drop entry at {@code entryIndex}.</li>
 *   <li>{@link Op#CLEAR} — drop the whole pool.</li>
 *   <li>{@link Op#BUMP_WEIGHT} / {@link Op#BUMP_COUNT} — adjust by
 *       {@code delta} (signed; clamped server-side).</li>
 *   <li>{@link Op#SET_WEIGHT} — set the entry's weight to {@code delta}
 *       outright (clamped ≥ 1 server-side). Sent when the author cmd-clicks
 *       the weight cell and types a value instead of stepping it.</li>
 *   <li>{@link Op#UNLINK} — clear the loot-prefab link at this cell. No
 *       payload fields used.</li>
 *   <li>{@link Op#TOGGLE_RAND_DUR} / {@link Op#TOGGLE_RAND_ENCH} — flip the
 *       per-entry random-durability / random-enchantment master toggle.</li>
 *   <li>{@link Op#BUMP_DUR_CHANCE} / {@link Op#BUMP_ENCH_CHANCE} — adjust the
 *       0-100 % chance that the effect applies, wrapping at the boundaries.</li>
 *   <li>{@link Op#SET_COUNT_RANGE} — set the entry's smallest and largest stack at once,
 *       {@code delta} the max and {@code value2} the min (clamped server-side, min ≤ max).</li>
 * </ul>
 *
 * <p>Server validates OP + plot membership before mutating.</p>
 */
public record ContainerContentsEditPacket(Op op, String plotKey, BlockPos localPos,
                                          int entryIndex, String itemId, int delta, int value2)
    implements CustomPacketPayload {

    /** Only the {@code SET_*_RANGE} ops carry a second value; every other op's {@code value2} is 0. */
    public ContainerContentsEditPacket(Op op, String plotKey, BlockPos localPos,
                                       int entryIndex, String itemId, int delta) {
        this(op, plotKey, localPos, entryIndex, itemId, delta, 0);
    }

    // Ordinals are the wire encoding — append new ops, never reorder.
    public enum Op {
        ADD, REMOVE, CLEAR, BUMP_WEIGHT, BUMP_COUNT, BUMP_FILL_MIN, BUMP_FILL_MAX, UNLINK,
        TOGGLE_RAND_DUR, BUMP_DUR_CHANCE, TOGGLE_RAND_ENCH, BUMP_ENCH_CHANCE,
        CYCLE_SLOT_ASSIGN, SET_WEIGHT,
        /** Flip a random-potion placeholder entry's scale-with-distance toggle. */
        TOGGLE_SCALE,
        /** Step a random-potion placeholder entry's bottle form (Any → Potion → Splash → Lingering). */
        CYCLE_POTION_FORM,
        /**
         * Typed values from the cmd-click number pad — {@code delta} is the value outright, and the
         * fill max takes {@code FILL_ALL} for "all". Same clamps as their BUMP twins.
         */
        SET_FILL_MIN, SET_FILL_MAX, SET_COUNT, SET_DUR_CHANCE, SET_ENCH_CHANCE,
        /** Step a vault's unlock key (Trial Key → Ominous Trial Key), which also sets the vault type. */
        CYCLE_VAULT_KEY,
        /** Typed min and max stack from the count cell's cmd-click pad: {@code delta} max, {@code value2} min. */
        SET_COUNT_RANGE,
        /** Step a book entry's most random enchantments by {@code delta} (0 turns them off). */
        BUMP_BOOK_ENCH,
        /** Typed random-enchantment range of a book entry: {@code delta} max, {@code value2} min. */
        SET_BOOK_ENCH_RANGE,
        /** Step the lowest level a book's random enchantments roll at by {@code delta} (0 = top level only). */
        BUMP_BOOK_ENCH_LEVEL,
        /** Typed level range of a book's random enchantments: {@code delta} max, {@code value2} min (0 = top). */
        SET_BOOK_ENCH_LEVEL_RANGE,
        /** Switch one enchantment, named in {@code itemId}, on or off in a book's list. */
        TOGGLE_BOOK_ENCH,
        /** Every enchantment in a book's list on ({@code delta} 1, curses kept) or off (0). */
        SET_BOOK_ENCH_ALL,
        /** Flip a random-potion placeholder entry's Luck toggle (Luck / Luck II in the pick). */
        TOGGLE_LUCK,
        /**
         * Pack a shulker box entry from the loot prefab the author holds, or empty it when they hold no
         * loot prefab. The prefab id comes from the server's view of the held item, never the packet.
         */
        SET_CONTENTS
    }

    public static final Type<ContainerContentsEditPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "container_contents_edit"));

    public static final StreamCodec<FriendlyByteBuf, ContainerContentsEditPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            ContainerContentsEditPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(op.ordinal());
        buf.writeUtf(plotKey, 128);
        buf.writeVarInt(localPos.getX());
        buf.writeVarInt(localPos.getY());
        buf.writeVarInt(localPos.getZ());
        buf.writeVarInt(entryIndex);
        buf.writeUtf(itemId == null ? "" : itemId, 256);
        buf.writeVarInt(delta);
        buf.writeVarInt(value2);
    }

    public static ContainerContentsEditPacket decode(FriendlyByteBuf buf) {
        byte ord = buf.readByte();
        Op op = Op.values()[ord];
        String key = buf.readUtf(128);
        BlockPos local = new BlockPos(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        int idx = buf.readVarInt();
        String itemId = buf.readUtf(256);
        int delta = buf.readVarInt();
        int value2 = buf.readVarInt();
        return new ContainerContentsEditPacket(op, key, local, idx, itemId, delta, value2);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ContainerContentsEditPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (p instanceof ServerPlayer sender) {
                ContainerContentsMenuController.applyEdit(sender, packet);
            }
        });
    }
}
