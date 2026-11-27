package games.brennan.dungeontrain.perks.effects.archivist.follow;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The authors a player follows, newest first: a copy of the relay's list (the relay is the record),
 * kept on the player ({@code copyOnDeath}) so the perk works while the relay is slow or away.
 * Immutable.
 */
public record AuthorFollows(List<Follow> follows) {

    /** One followed player: the relay's opaque author token, their name, when followed, and whether
     *  nothing unread is left from them. */
    public record Follow(String token, String name, long ts, boolean exhausted) {
        public static final Codec<Follow> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("token").forGetter(Follow::token),
            Codec.STRING.fieldOf("name").forGetter(Follow::name),
            Codec.LONG.fieldOf("ts").forGetter(Follow::ts),
            Codec.BOOL.optionalFieldOf("exhausted", false).forGetter(Follow::exhausted)
        ).apply(i, Follow::new));
    }

    public static final Codec<AuthorFollows> CODEC = Follow.CODEC.listOf()
        .xmap(AuthorFollows::new, AuthorFollows::follows);

    public AuthorFollows {
        List<Follow> sorted = new ArrayList<>(follows);
        sorted.sort(Comparator.comparingLong(Follow::ts).reversed());
        follows = List.copyOf(sorted);
    }

    public static AuthorFollows empty() {
        return new AuthorFollows(List.of());
    }

    public boolean isEmpty() {
        return follows.isEmpty();
    }

    /** Whether {@code token} is followed at all (active or not). */
    public boolean follows(String token) {
        return token != null && follows.stream().anyMatch(f -> f.token().equals(token));
    }

    /** The tokens of the follows active at {@code rank}. */
    public List<String> activeTokens(int rank) {
        return FavouriteAuthor.active(follows, rank).stream().map(Follow::token).toList();
    }

    /** Active follows that still have something unread to find. */
    public List<String> findableTokens(int rank) {
        return FavouriteAuthor.active(follows, rank).stream().filter(f -> !f.exhausted()).map(Follow::token).toList();
    }

    /** This list with {@code follow} added or replaced (by token). */
    public AuthorFollows with(Follow follow) {
        List<Follow> out = new ArrayList<>();
        for (Follow f : follows) {
            if (!f.token().equals(follow.token())) out.add(f);
        }
        out.add(follow);
        return new AuthorFollows(out);
    }

    /** This list without {@code token}. */
    public AuthorFollows without(String token) {
        return new AuthorFollows(follows.stream().filter(f -> !f.token().equals(token)).toList());
    }
}
