package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.portal.PortalPuppetsClient;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Server → client snapshot of the puppets one player should be seeing: stand-ins for the entities in
 * the <b>other</b> half of a portal pair, at their mirrored positions.
 *
 * <p>Sent every tick while a portal corridor is occupied, and once more — empty — when it empties, so
 * the client has an explicit signal to clear rather than only a timeout. Everything about a puppet
 * arrives here; the client never queries the server for one and never invents one.</p>
 *
 * <p><b>Two position spaces, and the flag that tells them apart.</b> A puppet standing in a twin
 * corridor is at rest in the world, so it travels as world coordinates. A puppet riding a portal
 * carriage is not: its blocks live in a Sable sub-level whose pose moves every tick, so it travels as
 * that sub-level's UUID plus a <b>shipyard-local</b> position, and the client resolves it against the
 * same interpolated pose Sable draws the carriage blocks with. Sending world coordinates for those
 * would hand the client a point derived from the live ship AABB, which carries the train's jitter —
 * the puppet would shimmer against the very carriage it is standing in. This is the same split
 * {@code ClientCarriedStatics} makes for End Crystals riding a carriage.</p>
 *
 * <p><b>Never the viewer's own puppet.</b> The server filters that out per recipient rather than the
 * client hiding it on arrival, so a player's stand-in is not merely invisible to them — it is never
 * sent.</p>
 */
public record PortalPuppetsPacket(List<Entry> entries) implements CustomPacketPayload {

    /** {@link Entry#kind()} for a puppet of a player, drawn with that player's skin. */
    public static final byte KIND_PLAYER = 0;
    /** {@link Entry#kind()} for a puppet of any other entity, drawn by its own renderer. */
    public static final byte KIND_MOB = 1;

    /**
     * One puppet.
     *
     * @param key       stable id for this puppet across ticks — the source entity's id, which is what
     *                  lets the client hold one render model per source instead of rebuilding it
     * @param kind      {@link #KIND_PLAYER} or {@link #KIND_MOB}
     * @param typeId    registry id of the source's entity type; ignored for players
     * @param sourceId  the source player's UUID, for skin lookup; unused for mobs
     * @param name      the source player's name, shown on the puppet's nameplate; empty for mobs
     * @param subLevel  the carriage sub-level to resolve {@code x,y,z} against, or {@code null} when
     *                  they are world coordinates
     * @param x         world X, or shipyard-local X when {@code subLevel} is set
     * @param y         world Y, or shipyard-local Y when {@code subLevel} is set
     * @param z         world Z, or shipyard-local Z when {@code subLevel} is set
     * @param yaw       body yaw, carried across unchanged — both corridors are axis-aligned
     * @param headYaw   head yaw, so a puppet looks where its source is looking
     * @param pitch     head pitch
     * @param data      the source's non-default synched entity data — see {@link Entry#data()}
     * @param mainHand  held item, so a puppet is not empty-handed when its source is armed
     * @param head      helmet slot
     * @param chest     chestplate slot
     * @param legs      leggings slot
     * @param feet      boots slot
     */
    public record Entry(
        int key,
        byte kind,
        ResourceLocation typeId,
        UUID sourceId,
        String name,
        UUID subLevel,
        double x,
        double y,
        double z,
        float yaw,
        float headYaw,
        float pitch,
        List<SynchedEntityData.DataValue<?>> data,
        ItemStack mainHand,
        ItemStack head,
        ItemStack chest,
        ItemStack legs,
        ItemStack feet
    ) {

        public boolean isPlayer() {
            return kind == KIND_PLAYER;
        }

        /** True when {@code x,y,z} are shipyard-local and need the carriage's pose to resolve. */
        public boolean isPlotSpace() {
            return subLevel != null;
        }

        /**
         * The source's non-default synched entity data, applied verbatim to the puppet.
         *
         * <p>This is what makes a puppet the <b>same</b> creature rather than merely the same
         * species. A villager's biome type and profession, a sheep's colour, a charged creeper, a
         * baby zombie, a named mob's custom name, a player's crouch and skin-layer settings — all of
         * it lives in synched data, and copying the lot is both simpler and more faithful than
         * enumerating the fields that happen to matter. It is also exactly what vanilla sends to
         * make a client-side entity look right, so a puppet inherits appearance support for entity
         * types this code has never heard of, from other mods included.</p>
         *
         * <p>Empty when the source is entirely default.</p>
         */
        public List<SynchedEntityData.DataValue<?>> data() {
            return data;
        }

        /** The source's entity type, or {@code null} if this client does not know that id. */
        public EntityType<?> entityType() {
            return BuiltInRegistries.ENTITY_TYPE.getOptional(typeId).orElse(null);
        }
    }

    public static final Type<PortalPuppetsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_puppets"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PortalPuppetsPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            PortalPuppetsPacket::decode
        );

    public static PortalPuppetsPacket empty() {
        return new PortalPuppetsPacket(Collections.emptyList());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void encode(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeVarInt(e.key());
            buf.writeByte(e.kind());
            buf.writeResourceLocation(e.typeId());

            // Players carry an identity for the skin and the nameplate; mobs carry neither, and
            // writing the presence flag keeps the two shapes self-describing on the wire.
            buf.writeBoolean(e.isPlayer());
            if (e.isPlayer()) {
                buf.writeUUID(e.sourceId());
                buf.writeUtf(e.name());
            }

            buf.writeBoolean(e.isPlotSpace());
            if (e.isPlotSpace()) {
                buf.writeUUID(e.subLevel());
            }
            buf.writeDouble(e.x());
            buf.writeDouble(e.y());
            buf.writeDouble(e.z());

            buf.writeFloat(e.yaw());
            buf.writeFloat(e.headYaw());
            buf.writeFloat(e.pitch());

            // Each value writes its own field id, so a count prefix is all the framing needed —
            // ClientboundSetEntityDataPacket uses a terminator byte for the same job.
            buf.writeVarInt(e.data().size());
            for (SynchedEntityData.DataValue<?> value : e.data()) {
                value.write(buf);
            }

            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, e.mainHand());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, e.head());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, e.chest());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, e.legs());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, e.feet());
        }
    }

    public static PortalPuppetsPacket decode(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Entry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int key = buf.readVarInt();
            byte kind = buf.readByte();
            ResourceLocation typeId = buf.readResourceLocation();

            UUID sourceId = null;
            String name = "";
            if (buf.readBoolean()) {
                sourceId = buf.readUUID();
                name = buf.readUtf();
            }

            UUID subLevel = buf.readBoolean() ? buf.readUUID() : null;
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();

            float yaw = buf.readFloat();
            float headYaw = buf.readFloat();
            float pitch = buf.readFloat();

            int dataCount = buf.readVarInt();
            List<SynchedEntityData.DataValue<?>> data = new ArrayList<>(dataCount);
            for (int d = 0; d < dataCount; d++) {
                data.add(SynchedEntityData.DataValue.read(buf, buf.readByte()));
            }

            out.add(new Entry(key, kind, typeId, sourceId, name, subLevel, x, y, z,
                yaw, headYaw, pitch, data,
                ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                ItemStack.OPTIONAL_STREAM_CODEC.decode(buf)));
        }
        return new PortalPuppetsPacket(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PortalPuppetsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> PortalPuppetsClient.applySnapshot(packet));
    }
}
