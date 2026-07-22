package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.narrative.NarrativeBookTag;
import games.brennan.dungeontrain.narrative.RandomBookTag;
import games.brennan.dungeontrain.narrative.SharedBookReadTag;
import games.brennan.dungeontrain.narrative.StartingBookTag;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * The resolved identity of a DT book stack — the shared {@code (bookType, bookId)} vocabulary used by
 * read telemetry ({@link BookReadClientEvents}), the vote page ({@code BookVoteClientEvents}) and the
 * server-side {@code BookVotePacket} validation. Extracted from the resolver that previously lived
 * inline in {@link BookReadClientEvents} so every consumer agrees on precedence and id shapes:
 *
 * <ul>
 *   <li>{@code random} — book basename (+ 1-based {@code variantIndex})</li>
 *   <li>{@code narrative} — {@code storyBasename#letterIndex}</li>
 *   <li>{@code shared} — relay pool id as a decimal string</li>
 *   <li>{@code starting} — welcome/lightning book basename (+ {@code variantIndex})</li>
 * </ul>
 *
 * <p>Precedence random → narrative → shared → starting is arbitrary but the four tag sets never
 * co-occur. {@code story}/{@code letter} are only meaningful for {@code narrative} (else {@code ""}/
 * {@code 0}); {@code variantIndex} is {@code -1} when not applicable. Despite the package, this class
 * touches only ItemStack + tag helpers — it is safe on the dedicated server (the packet handler
 * resolves the held stack with it).</p>
 */
public record BookIdentity(String bookType, String bookId, String story, int letter, int variantIndex) {

    /** Resolve {@code stack}'s DT book identity, or empty for non-DT books (vanilla, foreign, unsigned). */
    public static Optional<BookIdentity> resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        Optional<RandomBookTag.RandomBookIdentity> rnd = RandomBookTag.read(stack);
        if (rnd.isPresent()) {
            return Optional.of(new BookIdentity("random", rnd.get().basename(), "", 0, rnd.get().variantIndex()));
        }
        Optional<NarrativeBookTag.NarrativeIdentity> nar = NarrativeBookTag.read(stack);
        if (nar.isPresent()) {
            String story = nar.get().storyBasename();
            int letter = nar.get().letterIndex();
            return Optional.of(new BookIdentity("narrative", story + "#" + letter, story, letter, -1));
        }
        OptionalInt shared = SharedBookReadTag.readId(stack);
        if (shared.isPresent()) {
            return Optional.of(new BookIdentity("shared", Integer.toString(shared.getAsInt()), "", 0, -1));
        }
        Optional<StartingBookTag.StartingBookIdentity> starting = StartingBookTag.read(stack);
        if (starting.isPresent()) {
            return Optional.of(new BookIdentity("starting", starting.get().basename(), "", 0, starting.get().variantIndex()));
        }
        return Optional.empty();
    }
}
