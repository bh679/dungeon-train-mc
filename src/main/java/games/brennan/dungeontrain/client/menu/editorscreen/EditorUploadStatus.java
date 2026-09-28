package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.client.builder.BuilderTilePreviews;
import games.brennan.dungeontrain.client.builder.RelayBuildPreviews;
import games.brennan.dungeontrain.net.BuilderProfileRequestPacket;
import games.brennan.dungeontrain.net.BuilderUploadStatusPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The client end of {@link BuilderUploadStatusPacket}: keeps the "Uploading…" note's state and,
 * when a save lands on the relay, re-asks for everything the editor screen showed from before it.
 *
 * <p>Before this the screen fetched the roster, the player's own listing and the build's versions
 * once, on open — so a save's new relay row, its enabled Submit icon, its new version and even its
 * re-baked model only appeared after closing and reopening X.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class EditorUploadStatus {

    private static final UploadStatusBook BOOK = new UploadStatusBook();

    private EditorUploadStatus() {}

    /** Main client thread — the packet handler enqueues it there. */
    public static void onPacket(BuilderUploadStatusPacket packet) {
        String key = UploadStatusBook.key(packet.photoKind(), packet.id());
        long now = System.currentTimeMillis();
        // Whatever the phase, the file on disk is newer than the baked model: STARTED only goes out
        // after the local write, and DONE/FAILED follow it.
        evictModel(packet.photoKind(), packet.id());
        switch (packet.phase()) {
            case BuilderUploadStatusPacket.STARTED -> BOOK.started(key, now);
            case BuilderUploadStatusPacket.DONE -> {
                BOOK.finished(key, true, now);
                refreshAfterUpload(packet.relayId());
            }
            default -> {
                BOOK.finished(key, false, now);
                // A failure can still have changed the row (a build the relay no longer has is
                // forgotten server-side), so the roster is re-read either way.
                EditorRosterClient.request();
            }
        }
    }

    /** What the note beside Save should say for this template now, or null for nothing. */
    public static UploadStatusBook.Shown shown(TemplateArt art) {
        if (art == null || art.kind() == null) return null;
        return BOOK.shown(UploadStatusBook.key(art.kind().name(), art.id()), System.currentTimeMillis());
    }

    /** Drop the selected template's baked model — for a save that never goes to the relay. */
    public static void evictModel(TemplateArt art) {
        if (art != null) BuilderTilePreviews.evict(art.kind(), art.id());
    }

    private static void evictModel(String photoKind, String id) {
        try {
            BuilderTilePreviews.evict(BuilderPhotoPaths.Kind.valueOf(photoKind), id);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // A kind this client does not know — nothing of it can be cached here either.
        }
    }

    /** The relay has the save: the tile's relay id, the Submit icon and the version strip all moved. */
    private static void refreshAfterUpload(int relayId) {
        EditorRosterClient.request();
        DungeonTrainNet.sendToServer(new BuilderProfileRequestPacket());
        RelayBuildPreviews.forget(relayId);
    }
}
