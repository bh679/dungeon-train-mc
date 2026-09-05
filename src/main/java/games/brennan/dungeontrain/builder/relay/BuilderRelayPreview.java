package games.brennan.dungeontrain.builder.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import games.brennan.dungeontrain.train.CarriageSnapshotTemplate;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
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

    /** Bytes of encoded NBT past which a preview is dropped — the cheap size check. */
    private static final int MAX_WIRE_BYTES = 512 * 1024;

    /**
     * What the client's NBT accounter is allowed to charge for a preview.
     *
     * <p>The number that actually matters, and not the same number as the byte count: the accounter
     * bills per-tag overhead on top of the payload, so half a megabyte of small block entries can
     * account past two megabytes. Measured by re-reading the encoded bytes through an accounter of
     * this size — the client's own arithmetic, run here where refusing costs a name plate rather
     * than a connection. Below the 2 MiB the client allows, so the margin absorbs whatever the
     * payload wrapper adds.</p>
     */
    private static final long MAX_ACCOUNTED_BYTES = 1_500_000L;

    /**
     * A finished preview attempt: the bytes to send, and whether asking again could do better.
     *
     * <p>The distinction is the whole reason this is not just a nullable byte array. A relay that
     * did not answer is worth another go a moment later; a build too heavy to picture never will be,
     * and re-fetching it every few seconds would be a relay call per tile per retry for a name plate
     * that was never going to change.</p>
     *
     * @param bytes     the structure NBT to send, or null when there is no picture
     * @param retryable whether the failure was the moment rather than the build
     */
    public record Attempt(byte[] bytes, boolean retryable) {
        static final Attempt GONE = new Attempt(null, false);
        static final Attempt LATER = new Attempt(null, true);
    }

    private BuilderRelayPreview() {}

    /** The build's structure NBT, ready to send, or why there is nothing to send. */
    public static CompletableFuture<Attempt> fetch(ServerPlayer player, ServerLevel level,
                                                   int relayId, String ownerUuid, boolean live) {
        if (player == null || level == null || level.getServer() == null) {
            return CompletableFuture.completedFuture(Attempt.GONE);
        }
        if (!BuilderRelayUpload.canUpload(player)) {
            // Profiles off, or consent not granted. Nothing about this changes while the screen is
            // open, so the tiles are not going to be asked about again.
            LOGGER.debug("[DungeonTrain] Builder relay preview: id={} refused — previews need profiles on "
                    + "and network consent granted", relayId);
            return CompletableFuture.completedFuture(Attempt.GONE);
        }
        String own = player.getUUID().toString();
        String owner = ownerUuid == null || ownerUuid.isBlank() ? own : ownerUuid.trim();
        return SharedCarriageClient.fetchBuild(relayId, owner, RelayTarget.of(live))
                .thenCompose(result -> {
                    if (result.status() != SharedCarriageClient.CallStatus.OK) {
                        LOGGER.info("[DungeonTrain] Builder relay preview: id={} not fetched — relay said {}",
                                relayId, result.status());
                        // A timeout or a 5xx is the moment; FORBIDDEN and UNKNOWN are the build.
                        return CompletableFuture.completedFuture(
                                result.status() == SharedCarriageClient.CallStatus.ERROR
                                        ? Attempt.LATER : Attempt.GONE);
                    }
                    // The conversion reads the block registry, so it goes back to the server
                    // thread — the same hop the install path makes for the same reason.
                    return level.getServer().submit(() -> convert(level, result.build()));
                });
    }

    /**
     * The build as it stood at {@code seq} of its relay history, ready to send, with the seqs the
     * history holds — the previewer's back and forward.
     *
     * <p>Admin cap only, so a release build answers {@link Attempt#GONE} without asking. The frame
     * is a full snapshot plus the deltas recorded since it, folded exactly as a fetched build's
     * pending deltas are; a seq the relay does not hold is a build with no picture at that
     * version, not a retry.</p>
     */
    public static CompletableFuture<Versioned> fetchVersion(ServerPlayer player, ServerLevel level,
                                                            int relayId, boolean live, int seq) {
        if (player == null || level == null || level.getServer() == null
                || !BuilderRelayUpload.canUpload(player)) {
            return CompletableFuture.completedFuture(new Versioned(Attempt.GONE, List.of()));
        }
        return SharedCarriageClient.historyIndex(relayId, live).thenCompose(seqs -> {
            if (seqs == null || seqs.isEmpty()) {
                return CompletableFuture.completedFuture(new Versioned(Attempt.GONE, List.of()));
            }
            int want = seq > 0 ? seq : seqs.get(seqs.size() - 1);
            return SharedCarriageClient.historyFrame(relayId, want, live).thenCompose(frame -> {
                if (frame == null) {
                    return CompletableFuture.completedFuture(new Versioned(Attempt.GONE, seqs));
                }
                return level.getServer().submit(() -> new Versioned(convertFrame(level, relayId, frame), seqs));
            });
        });
    }

    /** A versioned preview: the attempt at the seq asked for, and every seq the history holds. */
    public record Versioned(Attempt attempt, List<Integer> seqs) {}

    /** Decode and fold one history frame, then convert and measure it like a live build. */
    private static Attempt convertFrame(ServerLevel level, int relayId, SharedCarriageClient.HistoryFrame frame) {
        CompoundTag tag;
        try {
            CompoundTag folded = CarriageBlockSnapshot.decode(frame.base());
            for (String cells : frame.deltas()) {
                folded = CarriageBlockSnapshot.applyDeltaCells(folded, CarriageBlockSnapshot.decode(cells));
            }
            HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
            tag = CarriageSnapshotTemplate.toTemplate(folded, blocks).save(new CompoundTag());
        } catch (Throwable t) {
            LOGGER.info("[DungeonTrain] Builder relay preview: id={} seq={} would not convert: {}",
                relayId, frame.seq(), t.toString());
            return Attempt.GONE;
        }
        byte[] bytes = encode(tag);
        return bytes == null ? Attempt.GONE : new Attempt(bytes, false);
    }

    /** Decode, fold, convert and measure — everything the install path does before it writes. */
    private static Attempt convert(ServerLevel level, SharedCarriageClient.BuildFetch build) {
        if (build == null) return Attempt.GONE;
        CompoundTag tag;
        try {
            CompoundTag snapshot = BuilderRelayDownload.fold(CarriageBlockSnapshot.decode(build.blocks()), build);
            HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
            StructureTemplate template = CarriageSnapshotTemplate.toTemplate(snapshot, blocks);
            tag = template.save(new CompoundTag());
        } catch (Throwable t) {
            // A build this version cannot read is a tile that keeps its name plate, not a broken
            // screen — but it is worth saying which build and why, because "some of them have no
            // picture" is otherwise a thing that can only be guessed at.
            LOGGER.info("[DungeonTrain] Builder relay preview: id={} ('{}', {}) would not convert: {}",
                    build.id(), build.buildName(), build.kind(), t.toString());
            return Attempt.GONE;
        }
        byte[] bytes = encode(tag);
        if (bytes == null) {
            LOGGER.info("[DungeonTrain] Builder relay preview: id={} ('{}', {}) has no picture — {} blocks, "
                            + "{} bytes encoded, over one of the caps ({} blocks / {} bytes / {} accounted)",
                    build.id(), build.buildName(), build.kind(),
                    tag.getList("blocks", Tag.TAG_COMPOUND).size(), encodedSize(tag),
                    MAX_BLOCKS, MAX_WIRE_BYTES, MAX_ACCOUNTED_BYTES);
            return Attempt.GONE;
        }
        return new Attempt(bytes, false);
    }

    /** The encoded size, for the log line that explains a refusal. */
    private static int encodedSize(CompoundTag tag) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            NbtIo.write(tag, new DataOutputStream(out));
            return out.size();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * This structure as the bytes the packet carries, or null when it should not be sent.
     *
     * <p>Three gates, cheapest first: too many blocks to be worth a tile, too many bytes for the
     * wire, and — the one that matters — more than the client's NBT accounter will spend reading
     * it. The last is checked by reading the bytes back through an accounter of that size, so the
     * question asked here is the same question the client will ask, rather than an estimate of
     * it.</p>
     */
    public static byte[] encode(CompoundTag tag) {
        if (tag == null || tag.getList("blocks", Tag.TAG_COMPOUND).size() > MAX_BLOCKS) return null;
        byte[] bytes;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            NbtIo.write(tag, new DataOutputStream(out));
            bytes = out.toByteArray();
        } catch (IOException e) {
            return null;
        }
        if (bytes.length > MAX_WIRE_BYTES || decode(bytes) == null) return null;
        return bytes;
    }

    /**
     * The tag those bytes hold, or null when there is no reading them within the accounter's budget.
     *
     * <p>Used on both sides, which is the point: the server asks it to find out whether the client
     * could read what it is about to send, and the client asks it instead of letting the payload
     * decoder do it — down here a refusal is a missing picture, up there it is a disconnect.</p>
     */
    public static CompoundTag decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return NbtIo.read(in, NbtAccounter.create(MAX_ACCOUNTED_BYTES));
        } catch (Exception e) {
            return null;
        }
    }
}
