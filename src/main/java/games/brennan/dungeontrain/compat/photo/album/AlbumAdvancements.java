package games.brennan.dungeontrain.compat.photo.album;

import games.brennan.dungeontrain.advancement.EnchiridionAdvancements;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import io.github.mortuusars.exposure.world.item.component.album.AlbumContent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * The album advancements earned from what a player's own album holds: Keepsake (a photo in it) and No Room
 * Left (every page holds one). Checked each time the album is saved ({@link PlayerAlbums#save}), the one place
 * every edit lands — placing a photo, and closing the album after changing it. Memory Lane (finding an album)
 * is plain data: an {@code inventory_changed} criterion.
 */
public final class AlbumAdvancements {

    /** Pages in an album, so a photo on every one is a full album. */
    public static final int FULL = AlbumSavePayload.MAX_PAGES;

    private AlbumAdvancements() {}

    /** Award what {@code content} has earned. Re-firing an earned advancement is a no-op. */
    static void onSaved(ServerPlayer player, AlbumContent content) {
        for (String action : actionsFor(photos(content))) {
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, action);
        }
    }

    /** Pages holding a photograph. A page with only a note doesn't count. */
    static int photos(AlbumContent content) {
        return (int) content.pages().stream().filter(p -> !p.photograph().isEmpty()).count();
    }

    /** The {@code gameplay_action} ids an album holding {@code photos} photographs fires. Pure; package-private for tests. */
    static List<String> actionsFor(int photos) {
        if (photos >= FULL) return List.of(EnchiridionAdvancements.ALBUM_PHOTO, EnchiridionAdvancements.ALBUM_FULL);
        if (photos > 0) return List.of(EnchiridionAdvancements.ALBUM_PHOTO);
        return List.of();
    }
}
