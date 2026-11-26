package games.brennan.dungeontrain.perks.effects.archivist.follow;

import games.brennan.dungeontrain.narrative.AuthorBookPool;
import games.brennan.dungeontrain.narrative.SharedBookPool;
import games.brennan.dungeontrain.narrative.SharedBookSelector;
import games.brennan.dungeontrain.narrative.WorldLanguage;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Favourite Author's book pick: the followed authors' own catalogues ({@link AuthorBookPool}, the same
 * per-author fetch an author-locked library room uses), fed to {@link SharedBookSelector#selectFollowed}.
 * A catalogue not fetched yet is asked for and skipped this time: the pickup never waits on it.
 */
public final class FavouriteAuthorBooks {

    private FavouriteAuthorBooks() {}

    /** A followed author's unread book for this pickup, or empty for the normal pick. */
    public static Optional<SharedBookPool.PoolBook> pick(ServerPlayer player, SharedBookSelector.PlayerContext ctx, long seed) {
        double share = FollowService.share(player);
        if (share <= 0) return Optional.empty();
        List<String> tokens = FollowService.findableTokens(player);
        if (tokens.isEmpty()) return Optional.empty();
        String lang = WorldLanguage.hostLocale(player.getServer());
        boolean kidSafe = WorldLanguage.hostFetchesKidSafeBooks(player.getServer());
        List<SharedBookPool.PoolBook> books = new ArrayList<>();
        for (String token : tokens) {
            if (!AuthorBookPool.isFetched(token, false)) AuthorBookPool.refreshAsync(token, lang, kidSafe, null);
            books.addAll(AuthorBookPool.booksFor(token, false));
        }
        return SharedBookSelector.selectFollowed(books, share, ctx, seed);
    }
}
