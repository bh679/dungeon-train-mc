package games.brennan.dungeontrain.builder.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import games.brennan.dungeontrain.train.CarriageSnapshotTemplate;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;

/**
 * A relay build's blocks, fetched to be <em>looked at</em> rather than installed.
 *
 * <p>{@link BuilderRelayDownload} is the other half of this: the same fetch and the same fold, but
 * ending in files on disk and rows in the world data. Browsing somebody's uploads needs neither —
 * it needs a picture — so this stops one step earlier and hands back the structure NBT for the
 * client to bake a mesh from. Nothing here writes anything, which is what makes it safe to run for
 * every tile that scrolls into view.</p>
 *
 * <p>Gated exactly as a download is ({@link BuilderRelayUpload#canUpload}): profiles on, network
 * consent granted. A preview reads the same rows an install would, so it does not get a looser
 * door.</p>
 */
public final class BuilderRelayPreview {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Blocks past which a preview is not worth sending — the cheap check, made before the exact one.
     *
     * <p>Well under the client's own bake cap, because the binding limit here is the wire rather
     * than the GPU, and a picture 100 pixels wide is not worth spending a whole payload on.</p>
     */
    private static final int MAX_BLOCKS = 12_000;

    /**
     * Bytes of encoded NBT past which a preview is dropped.
     *
     * <p>The counted size, because guessing by block count is what put a 2 MB tag on the wire and
     * dropped the player out of their world: the client reads a payload's NBT through an accounter
     * capped at 2 MiB, which charges per-tag overhead on top of the bytes, and a decode that trips
     * it is a {@code DecoderException} — which is to say a disconnect, not a missing picture. Half a
     * megabyte leaves that accounting room to breathe.</p>
     */
    private static final int MAX_WIRE_BYTES = 512 * 1024;

    private BuilderRelayPreview() {}

    /** The build's structure NBT, or null when it cannot or should not be pictured. */
    public static CompletableFuture<CompoundTag> fetch(ServerPlayer player, ServerLevel level,
                                                       int relayId, String ownerUuid, boolean live) {
        if (player == null || level == null || level.getServer() == null
                || !BuilderRelayUpload.canUpload(player)) {
            return CompletableFuture.completedFuture(null);
        }
        String own = player.getUUID().toString();
        String owner = ownerUuid == null || ownerUuid.isBlank() ? own : ownerUuid.trim();
        return SharedCarriageClient.fetchBuild(relayId, owner, RelayTarget.of(live))
                .thenCompose(result -> result.status() != SharedCarriageClient.CallStatus.OK
                        ? CompletableFuture.completedFuture((CompoundTag) null)
                        // The conversion reads the block registry, so it goes back to the server
                        // thread — the same hop the install path makes for the same reason.
                        : level.getServer().submit(() -> toTemplateTag(level, result.build())));
    }

    /** Decode, fold and convert — everything the install path does before it starts writing. */
    private static CompoundTag toTemplateTag(ServerLevel level, SharedCarriageClient.BuildFetch build) {
        if (build == null) return null;
        try {
            CompoundTag snapshot = BuilderRelayDownload.fold(CarriageBlockSnapshot.decode(build.blocks()), build);
            HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
            StructureTemplate template = CarriageSnapshotTemplate.toTemplate(snapshot, blocks);
            CompoundTag tag = template.save(new CompoundTag());
            return oversized(tag) ? null : tag;
        } catch (Throwable t) {
            // A build this version cannot read is a tile that keeps its slate, not a broken screen.
            LOGGER.debug("[DungeonTrain] Builder relay preview: id={} would not convert: {}", build.id(), t.toString());
            return null;
        }
    }

    /** Whether this structure is past what a tile-sized picture is worth, or past what will send. */
    static boolean oversized(CompoundTag tag) {
        if (tag.getList("blocks", Tag.TAG_COMPOUND).size() > MAX_BLOCKS) return true;
        return encodedSize(tag) > MAX_WIRE_BYTES;
    }

    /**
     * How many bytes this tag becomes on the wire, counted rather than estimated.
     *
     * <p>Encoding it twice — once to measure, once to send — is the cost of never sending one that
     * cannot be read. A tag that will not even encode counts as infinite, which drops it.</p>
     */
    static int encodedSize(CompoundTag tag) {
        ByteCounter counter = new ByteCounter();
        try (DataOutputStream out = new DataOutputStream(counter)) {
            NbtIo.write(tag, out);
        } catch (IOException e) {
            return Integer.MAX_VALUE;
        }
        return counter.count;
    }

    /** Counts bytes and keeps none of them. */
    private static final class ByteCounter extends OutputStream {
        private int count;

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            count += len;
        }
    }
}
