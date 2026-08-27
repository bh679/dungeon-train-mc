package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderStructureStamp;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.builder.structure.BuilderStructureMode;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: what the Train Builder should do with the structures around the build.
 *
 * <p><b>A round trip, unlike the two-state ghost toggle this replaces.</b> That one only chose
 * whether the client drew blocks that were there either way, so it could be a client config and a
 * per-player preference. {@link BuilderStructureMode#SOLID} is the difference between a wall
 * existing and not existing: the server owns it, and everyone in the world has to see the same
 * answer.</p>
 *
 * <p>The value is a request, not an instruction — an unrecognised token falls back to the default
 * rather than being an error, and the authoritative result comes back in the
 * {@link BuilderBoundsPacket} pushed afterwards. That is also what lets the button read its own
 * label off the server's answer instead of off the click.</p>
 */
public record BuilderStructureModePacket(String modeId) implements CustomPacketPayload {

    public static final Type<BuilderStructureModePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_structure_mode"));

    public static final StreamCodec<FriendlyByteBuf, BuilderStructureModePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> buf.writeUtf(p.modeId, 16),
            buf -> new BuilderStructureModePacket(buf.readUtf(16)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderStructureModePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            MinecraftServer server = player.getServer();
            if (server == null) return;
            ServerLevel level = server.overworld();
            // A client can send anything, and this one clears and stamps blocks.
            if (!level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
                return;
            }

            BuilderStructureMode mode = BuilderStructureMode.orDefault(packet.modeId);
            DungeonTrainWorldData.get(level).setBuilderStructureMode(mode.id());
            BuilderStructureStamp.apply(level);

            // The button's label and tint read from the state this echoes, so without the resend a
            // click would appear not to have taken.
            BuilderBoundsPacket.sendTo(player, level);
        });
    }
}
