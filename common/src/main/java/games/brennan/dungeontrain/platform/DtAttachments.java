package games.brennan.dungeontrain.platform;

import games.brennan.dungeontrain.player.PlayerBiomeProgress;
import games.brennan.dungeontrain.player.PlayerRunState;

import java.util.ServiceLoader;

/**
 * Loader-neutral access point for Dungeon Train's per-player persisted state.
 *
 * <p>Exposes the four {@link DtAttachment} handles as {@code :common}-visible
 * constants so game logic in {@code :common} reads/writes per-player run data
 * without ever referencing NeoForge's {@code AttachmentType} API or the
 * root-only {@code ModDataAttachments} registry. The handles are produced by a
 * loader-supplied {@link DtAttachmentsProvider}, resolved once via
 * {@link ServiceLoader} — the same holder pattern as
 * {@code games.brennan.dungeontrain.net.platform.DtNetSender}.</p>
 *
 * <p>The seam indirects <em>access</em> only. Attachment REGISTRATION, codecs,
 * and {@code copyOnDeath} semantics remain entirely loader-side (see the
 * NeoForge {@code ModDataAttachments}); the on-disk NBT format is unchanged.</p>
 */
public final class DtAttachments {

    /** Per-player run state (streak, distances, counters). Codec-persisted. */
    public static final DtAttachment<PlayerRunState> PLAYER_RUN_STATE = provider().playerRunState();

    /** Per-player distinct-biomes-ridden progress. Codec-persisted; cleared on death. */
    public static final DtAttachment<PlayerBiomeProgress> PLAYER_BIOME_PROGRESS = provider().playerBiomeProgress();

    /** Per-player "has seen the spawn intro cinematic" flag. Codec-persisted; copyOnDeath. */
    public static final DtAttachment<Boolean> SEEN_INTRO_CINEMATIC = provider().seenIntroCinematic();

    /** Per-player "this run has been cheated" flag. Codec-persisted; copyOnDeath; sticky. */
    public static final DtAttachment<Boolean> RUN_CHEATED = provider().runCheated();

    private DtAttachments() {}

    private static DtAttachmentsProvider provider() {
        return Holder.INSTANCE;
    }

    private static final class Holder {
        static final DtAttachmentsProvider INSTANCE = load();

        private Holder() {}

        private static DtAttachmentsProvider load() {
            ServiceLoader<DtAttachmentsProvider> loader =
                ServiceLoader.load(DtAttachmentsProvider.class, DtAttachmentsProvider.class.getClassLoader());
            for (DtAttachmentsProvider impl : loader) {
                return impl;
            }
            throw new IllegalStateException(
                "No DtAttachmentsProvider implementation found via ServiceLoader — the loader module "
                    + "must provide META-INF/services/" + DtAttachmentsProvider.class.getName());
        }
    }
}
