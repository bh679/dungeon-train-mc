package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.PortalTestSessionState;
import games.brennan.dungeontrain.portal.PortalTestSession;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.editor.StageStore;
import games.brennan.dungeontrain.editor.TestOverrides;
import games.brennan.dungeontrain.template.Stage;
import games.brennan.dungeontrain.template.TestGate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: whether this player is currently standing in a test carriage (a dimensional one, or a
 * carriage/contents template's test copy), so the worldspace
 * menu can offer the way back out of it.
 *
 * <p>A state rather than a place, unlike {@link PortalRoomSkyPacket} — and safe as one because the
 * server owns both edges: it sends {@code true} as it teleports the player in and {@code false} as
 * it brings them back, and the session it mirrors does not survive a disconnect either. A client
 * that missed the {@code false} rejoins to a fresh {@code false}.</p>
 */
public record PortalTestSessionPacket(boolean active, String roomName, boolean reseed,
                                      String stage, int level, int phase, int autoLevel,
                                      List<String> stageIds, List<String> stageNames)
    implements CustomPacketPayload {

    /**
     * The test settings rows' state rides along on every send: the author's picks ({@code stage}
     * empty, {@code level} and {@code phase} -1 for Auto), the level Auto stands for, and the stages
     * there are to pick from, lowest first.
     */
    public PortalTestSessionPacket {
        stage = stage == null ? "" : stage;
        stageIds = stageIds == null ? List.of() : List.copyOf(stageIds);
        stageNames = stageNames == null ? List.of() : List.copyOf(stageNames);
    }

    /**
     * What the server sends when the trip is over.
     *
     * @param player who it goes to — the world's reseed-on-test switch and their test settings ride
     *               along on every send, so the editor never shows a state the server does not hold
     */
    public static PortalTestSessionPacket none(ServerPlayer player) {
        return build(player, false, "");
    }

    /** What the server sends as it stands the author in a test of {@code shownName}. */
    public static PortalTestSessionPacket active(ServerPlayer player, String shownName) {
        return build(player, true, shownName);
    }

    /** This player's session as it stands — none if they are not on a test trip. */
    public static PortalTestSessionPacket of(ServerPlayer player) {
        PortalTestSession.Session trip = PortalTestSession.get(player.getUUID());
        if (trip != null) return build(player, true, trip.roomName());
        // A carriage or contents test drives the same Exit / Reseed controls, so it rides the same
        // packet: the client only needs to know a test is running and what to call it.
        games.brennan.dungeontrain.train.CarriageTestSession.Session carriage =
            games.brennan.dungeontrain.train.CarriageTestSession.get(player.getUUID());
        return carriage == null ? none(player) : build(player, true, carriage.templateId());
    }

    private static PortalTestSessionPacket build(ServerPlayer player, boolean active, String name) {
        boolean reseed = DungeonTrainWorldData.get(player.getServer().overworld()).isPortalTestReseed();
        TestGate gate = TestOverrides.get(player.getUUID());
        List<Stage> stages = new ArrayList<>(StageStore.allStages());
        stages.sort(Comparator.comparingInt((Stage st) -> st.gate().minLevel()).thenComparing(Stage::id));
        return new PortalTestSessionPacket(active, name, reseed,
            gate.stageId() == null ? "" : gate.stageId(), gate.level(),
            gate.phase() == null ? -1 : gate.phase().ordinal(),
            // What Auto stands for — read outside any test scope, which would answer the override.
            TestGate.with(TestGate.AUTO, () -> DifficultyProgression.positionTier(0)),
            stages.stream().map(Stage::id).toList(), stages.stream().map(Stage::name).toList());
    }

    public static final Type<PortalTestSessionPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_test_session"));

    public static final StreamCodec<FriendlyByteBuf, PortalTestSessionPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeBoolean(packet.active);
                buf.writeUtf(packet.roomName);
                buf.writeBoolean(packet.reseed);
                buf.writeUtf(packet.stage);
                buf.writeVarInt(packet.level + 1);
                buf.writeVarInt(packet.phase + 1);
                buf.writeVarInt(packet.autoLevel);
                buf.writeCollection(packet.stageIds, FriendlyByteBuf::writeUtf);
                buf.writeCollection(packet.stageNames, FriendlyByteBuf::writeUtf);
            },
            buf -> new PortalTestSessionPacket(buf.readBoolean(), buf.readUtf(), buf.readBoolean(),
                buf.readUtf(), buf.readVarInt() - 1, buf.readVarInt() - 1, buf.readVarInt(),
                buf.readList(FriendlyByteBuf::readUtf), buf.readList(FriendlyByteBuf::readUtf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PortalTestSessionPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> PortalTestSessionState.update(packet));
    }
}
