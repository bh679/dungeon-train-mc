package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.net.platform.DtModId;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import games.brennan.dungeontrain.net.platform.DtPayloadContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server → client: the block-variant world-space menu's current state for
 * a single cell.
 *
 * <p>An "active" packet ({@code localPos != null}) tells the client to
 * render the menu anchored at {@code anchorPos} with axes {@code anchorRight}
 * / {@code anchorUp}, listing {@code entries} for the carriage variant
 * {@code variantId}'s cell at {@code localPos}.</p>
 *
 * <p>An "inactive" packet ({@code localPos == null}, signalled by writing
 * a sentinel byte) tells the client to close its menu — the player has
 * tapped Z while looking away from any flagged block, or explicitly
 * dismissed the panel.</p>
 *
 * <p>Search registry: the full list of block IDs is NOT transmitted; the
 * client builds its own search list from {@code BuiltInRegistries.BLOCK}
 * since vanilla guarantees identical registries on both sides.</p>
 */
public record BlockVariantSyncPacket(
    String variantId,
    @Nullable BlockPos localPos,
    List<Entry> entries,
    int lockId,
    Vec3 anchorPos,
    Vec3 anchorRight,
    Vec3 anchorUp
) implements CustomPacketPayload {

    /**
     * Single per-cell candidate, mirrored on the wire. Lock semantics live
     * at the cell level — see {@link BlockVariantSyncPacket#lockId()}.
     *
     * <p>{@code rotMode} ordinal: 0=LOCK, 1=RANDOM, 2=OPTIONS — matches
     * {@link games.brennan.dungeontrain.editor.VariantRotation.Mode#ordinal()}.
     * Out-of-range values on read are treated as RANDOM with mask 0
     * (the {@code VariantRotation.NONE} default).</p>
     *
     * <p>{@code rotDirMask} is the same 6-bit mask over
     * {@link net.minecraft.core.Direction#ordinal()} as the JSON sidecar
     * uses.</p>
     *
     * <p>{@code linkedLootPrefabId} is non-null when this candidate was
     * added from a saved loot-prefab item; the editor renders it as the
     * row label and the spawn pipeline rolls contents from the linked
     * pool (see {@code LootPrefabStore}).</p>
     *
     * <p>{@code halfMode} ordinal: 0=TOP, 1=RANDOM, 2=BOTTOM — matches
     * {@link games.brennan.dungeontrain.editor.VariantHalf.Mode#ordinal()}.
     * Out-of-range values on read are treated as RANDOM (the
     * {@code VariantHalf.NONE} default).</p>
     *
     * <p>{@code minDiff} / {@code maxDiff} mirror the v8
     * {@link games.brennan.dungeontrain.editor.VariantDifficulty} band for mob
     * rows: the inclusive difficulty-tier range the egg may spawn in.
     * {@code maxDiff == -1} is the "all" sentinel (no upper bound). Both
     * default to {@code 0} / {@code -1} on non-mob rows and are only rendered
     * for mob rows.</p>
     */
    public record Entry(String stateString, @Nullable String beNbt, int weight,
                        byte rotMode, byte rotDirMask, @Nullable String linkedLootPrefabId,
                        @Nullable String entityId, byte halfMode, int minDiff, int maxDiff) {

        /** Backward-compat constructor for call sites that don't carry a loot link. */
        public Entry(String stateString, @Nullable String beNbt, int weight,
                     byte rotMode, byte rotDirMask) {
            this(stateString, beNbt, weight, rotMode, rotDirMask, null, null, (byte) 1, 0, -1);
        }

        /** Backward-compat constructor for call sites that don't carry an entity id. */
        public Entry(String stateString, @Nullable String beNbt, int weight,
                     byte rotMode, byte rotDirMask, @Nullable String linkedLootPrefabId) {
            this(stateString, beNbt, weight, rotMode, rotDirMask, linkedLootPrefabId, null, (byte) 1, 0, -1);
        }

        /** Backward-compat constructor for call sites that don't carry a halfMode (defaults to RANDOM = 1). */
        public Entry(String stateString, @Nullable String beNbt, int weight,
                     byte rotMode, byte rotDirMask, @Nullable String linkedLootPrefabId,
                     @Nullable String entityId) {
            this(stateString, beNbt, weight, rotMode, rotDirMask, linkedLootPrefabId, entityId, (byte) 1, 0, -1);
        }

        /** Backward-compat constructor for call sites that carry a halfMode but no difficulty band (defaults 0 / all). */
        public Entry(String stateString, @Nullable String beNbt, int weight,
                     byte rotMode, byte rotDirMask, @Nullable String linkedLootPrefabId,
                     @Nullable String entityId, byte halfMode) {
            this(stateString, beNbt, weight, rotMode, rotDirMask, linkedLootPrefabId, entityId, halfMode, 0, -1);
        }

        /** True for v7 mob entries — the C-menu renders the spawn-egg icon for these rows. */
        public boolean isMob() {
            return entityId != null && !entityId.isEmpty();
        }
    }

    public static final Type<BlockVariantSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DtModId.MOD_ID, "block_variant_sync"));

    public static final StreamCodec<FriendlyByteBuf, BlockVariantSyncPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            BlockVariantSyncPacket::decode
        );

    public static BlockVariantSyncPacket empty() {
        return new BlockVariantSyncPacket(
            "", null, Collections.emptyList(), 0,
            Vec3.ZERO, Vec3.ZERO, Vec3.ZERO);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(variantId);
        if (localPos == null) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeVarInt(localPos.getX());
        buf.writeVarInt(localPos.getY());
        buf.writeVarInt(localPos.getZ());
        buf.writeVarInt(lockId);
        writeVec3(buf, anchorPos);
        writeVec3(buf, anchorRight);
        writeVec3(buf, anchorUp);
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUtf(e.stateString(), 256);
            boolean hasNbt = e.beNbt() != null && !e.beNbt().isEmpty();
            buf.writeBoolean(hasNbt);
            if (hasNbt) buf.writeUtf(e.beNbt(), 32767);
            buf.writeVarInt(e.weight());
            buf.writeByte(e.rotMode());
            buf.writeByte(e.rotDirMask());
            boolean hasLink = e.linkedLootPrefabId() != null && !e.linkedLootPrefabId().isEmpty();
            buf.writeBoolean(hasLink);
            if (hasLink) buf.writeUtf(e.linkedLootPrefabId(), 128);
            boolean hasEid = e.entityId() != null && !e.entityId().isEmpty();
            buf.writeBoolean(hasEid);
            if (hasEid) buf.writeUtf(e.entityId(), 128);
            buf.writeByte(e.halfMode());
            buf.writeVarInt(e.minDiff());
            buf.writeVarInt(e.maxDiff());
        }
    }

    public static BlockVariantSyncPacket decode(FriendlyByteBuf buf) {
        String id = buf.readUtf(64);
        boolean active = buf.readBoolean();
        if (!active) {
            return new BlockVariantSyncPacket(
                id, null, Collections.emptyList(), 0,
                Vec3.ZERO, Vec3.ZERO, Vec3.ZERO);
        }
        BlockPos local = new BlockPos(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        int lockId = buf.readVarInt();
        Vec3 anchor = readVec3(buf);
        Vec3 right = readVec3(buf);
        Vec3 up = readVec3(buf);
        int n = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String stateStr = buf.readUtf(256);
            boolean hasNbt = buf.readBoolean();
            String nbt = hasNbt ? buf.readUtf(32767) : null;
            int weight = buf.readVarInt();
            byte rotMode = buf.readByte();
            byte rotDirMask = buf.readByte();
            boolean hasLink = buf.readBoolean();
            String linkedLootPrefabId = hasLink ? buf.readUtf(128) : null;
            boolean hasEid = buf.readBoolean();
            String entityId = hasEid ? buf.readUtf(128) : null;
            byte halfMode = buf.readByte();
            int minDiff = buf.readVarInt();
            int maxDiff = buf.readVarInt();
            entries.add(new Entry(stateStr, nbt, weight, rotMode, rotDirMask,
                linkedLootPrefabId, entityId, halfMode, minDiff, maxDiff));
        }
        return new BlockVariantSyncPacket(id, local, entries, lockId, anchor, right, up);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BlockVariantSyncPacket packet, DtPayloadContext ctx) {
        ctx.enqueueWork(() ->
            games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantMenu.applySync(packet));
    }

    private static void writeVec3(FriendlyByteBuf buf, Vec3 v) {
        buf.writeDouble(v.x);
        buf.writeDouble(v.y);
        buf.writeDouble(v.z);
    }

    private static Vec3 readVec3(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }
}
