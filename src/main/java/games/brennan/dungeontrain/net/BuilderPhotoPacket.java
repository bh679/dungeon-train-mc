package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderCarriageCount;
import games.brennan.dungeontrain.builder.BuilderCinematic;
import games.brennan.dungeontrain.builder.BuilderPhotoRequest;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.client.builder.BuilderPhotoClient;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: photograph the build that was just saved.
 *
 * <p>Carries the shot, not the picture. Capturing has to happen on the client during a render frame,
 * but <em>framing</em> is server-side maths — the same {@code BuilderCinematic} calls the world's
 * opening cinematic makes — so the photo and the cinematic agree by construction rather than by two
 * copies of the same arithmetic staying in step.</p>
 *
 * <p>Nothing comes back. A builder world is single-player, so the client shares the game directory
 * and writes the PNG beside the template itself. That also sidesteps the payload cap the echo photo
 * flow has to work around: a lossless PNG at this resolution regularly exceeds it, which is why that
 * path ships JPEG.</p>
 *
 * <p>{@code onlyIfMissing} is what lets browsing fill the library in: New sends it true, so stamping
 * a template that has never been photographed takes its picture once and leaves it alone
 * afterwards. An explicit Save sends false — it always re-photographs what you just made.</p>
 */
public record BuilderPhotoPacket(String kindId, String id, String partKindId, boolean onlyIfMissing,
                                 double camX, double camY, double camZ,
                                 double focusX, double focusY, double focusZ)
        implements CustomPacketPayload {

    public static final Type<BuilderPhotoPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_photo"));

    public static final StreamCodec<FriendlyByteBuf, BuilderPhotoPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.kindId, 16);
                buf.writeUtf(p.id, 64);
                buf.writeUtf(p.partKindId, 16);
                buf.writeBoolean(p.onlyIfMissing);
                buf.writeDouble(p.camX); buf.writeDouble(p.camY); buf.writeDouble(p.camZ);
                buf.writeDouble(p.focusX); buf.writeDouble(p.focusY); buf.writeDouble(p.focusZ);
            },
            buf -> new BuilderPhotoPacket(buf.readUtf(16), buf.readUtf(64), buf.readUtf(16),
                    buf.readBoolean(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderPhotoPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> BuilderPhotoClient.capture(packet));
    }

    /**
     * Ask {@code player} to photograph {@code request} from the shot their builder world opened on.
     *
     * <p>The pose is the cinematic's <em>final</em> frame: eye height at
     * {@link BuilderWorldLayout#spawnPos}, aimed at the build's centre — the same two
     * {@code BuilderCinematic} calls {@code BuilderCinematicService.playNow} makes. Sharing them is
     * what keeps the photo and the cinematic in agreement, including the framing guarantee, since
     * {@code spawnPos} already stands back far enough to fit the whole train in.</p>
     */
    public static void send(ServerPlayer player, ServerLevel level,
                            BuilderPhotoRequest request, boolean onlyIfMissing) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        CarriageDims dims = data.dims();
        int carriages = BuilderCarriageCount.of(data.builderMode(), data.builderSubType());

        BlockPos spawn = BuilderWorldLayout.spawnPos(dims, carriages);
        Vec3 cam = BuilderCinematic.eyeOf(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        Vec3 focus = BuilderCinematic.focus(carriages, dims);

        DungeonTrainNet.sendTo(player, new BuilderPhotoPacket(
                request.kind().id(), request.id(), request.partKindId(), onlyIfMissing,
                cam.x, cam.y, cam.z, focus.x, focus.y, focus.z));
    }
}
