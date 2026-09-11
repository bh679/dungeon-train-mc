package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.editor.ContainerContentsEntry;
import games.brennan.dungeontrain.editor.ContainerContentsPool;
import games.brennan.dungeontrain.editor.LootPrefabStore;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Two versions of one loot prefab, laid side by side for the player to compare.
 *
 * <p>The comparison the conflict screen draws, worked out here without a screen so it can be
 * tested: each side becomes a column of {@link Line}s — the container block, the fill range, then
 * one line per entry — and every line is marked with how it stands against the other column. An
 * entry is matched across the two sides by its item (and potion, for a potion entry); a match with
 * a different count or weight is {@link State#CHANGED}, an item the other side does not have is
 * {@link State#ONLY_HERE}. Two entries of the same item on one side are compared in order.</p>
 *
 * <p>A side whose text will not parse — a file the wire had to cut, or a prefab from a newer mod —
 * gets a single {@link State#UNREADABLE} line and nothing else; the choice is still offered, the
 * screen just cannot say what is inside.</p>
 */
public final class LootPrefabDiff {

    private LootPrefabDiff() {}

    /** How one line stands against the other column. */
    public enum State { SAME, CHANGED, ONLY_HERE, UNREADABLE }

    /** What kind of thing a line describes — the screen picks the lang key by it. */
    public enum Kind { BLOCK, FILL, ENTRY, UNREADABLE }

    /**
     * One line of one column.
     *
     * @param kind   what it describes
     * @param state  how it compares with the other side
     * @param item   the entry's item id ({@link Kind#ENTRY}), the block id ({@link Kind#BLOCK}), else ""
     * @param count  the entry's count, or the fill minimum
     * @param weight the entry's weight, or the fill maximum
     */
    public record Line(Kind kind, State state, String item, int count, int weight) {}

    /** Both columns of one conflict. */
    public record Columns(List<Line> yours, List<Line> theirs) {}

    /** Lay {@code localText} (yours) and {@code incomingText} (theirs) side by side. */
    public static Columns of(String id, String localText, String incomingText) {
        Optional<LootPrefabStore.Data> local = LootPrefabStore.parse(id, localText);
        Optional<LootPrefabStore.Data> incoming = LootPrefabStore.parse(id, incomingText);
        if (local.isEmpty() || incoming.isEmpty()) {
            return new Columns(
                    local.map(d -> alone(d)).orElse(unreadable()),
                    incoming.map(d -> alone(d)).orElse(unreadable()));
        }
        return new Columns(column(local.get(), incoming.get()), column(incoming.get(), local.get()));
    }

    private static List<Line> unreadable() {
        return List.of(new Line(Kind.UNREADABLE, State.UNREADABLE, "", 0, 0));
    }

    /** One side with nothing to compare against — every line stands alone. */
    private static List<Line> alone(LootPrefabStore.Data d) {
        List<Line> out = new ArrayList<>();
        out.add(new Line(Kind.BLOCK, State.ONLY_HERE, d.sourceBlock().toString(), 0, 0));
        out.add(new Line(Kind.FILL, State.ONLY_HERE, "", d.pool().fillMin(), d.pool().fillMax()));
        for (ContainerContentsEntry e : d.pool().entries()) {
            out.add(new Line(Kind.ENTRY, State.ONLY_HERE, e.itemId().toString(), e.count(), e.weight()));
        }
        return out;
    }

    /** The column for {@code mine}, each line judged against {@code other}. */
    private static List<Line> column(LootPrefabStore.Data mine, LootPrefabStore.Data other) {
        List<Line> out = new ArrayList<>();
        out.add(new Line(Kind.BLOCK,
                mine.sourceBlock().equals(other.sourceBlock()) ? State.SAME : State.CHANGED,
                mine.sourceBlock().toString(), 0, 0));
        ContainerContentsPool a = mine.pool();
        ContainerContentsPool b = other.pool();
        out.add(new Line(Kind.FILL,
                a.fillMin() == b.fillMin() && a.fillMax() == b.fillMax() ? State.SAME : State.CHANGED,
                "", a.fillMin(), a.fillMax()));
        // Entries of the same item on the other side, in order, consumed as they are matched — so two
        // "diamond" entries on each side pair up first-with-first rather than both with the first.
        Map<String, List<ContainerContentsEntry>> theirs = byItem(b.entries());
        for (ContainerContentsEntry e : a.entries()) {
            List<ContainerContentsEntry> candidates = theirs.get(matchKey(e));
            State state;
            if (candidates == null || candidates.isEmpty()) {
                state = State.ONLY_HERE;
            } else {
                ContainerContentsEntry match = candidates.remove(0);
                state = e.equals(match) ? State.SAME : State.CHANGED;
            }
            out.add(new Line(Kind.ENTRY, state, e.itemId().toString(), e.count(), e.weight()));
        }
        return out;
    }

    private static Map<String, List<ContainerContentsEntry>> byItem(List<ContainerContentsEntry> entries) {
        Map<String, List<ContainerContentsEntry>> out = new LinkedHashMap<>();
        for (ContainerContentsEntry e : entries) {
            out.computeIfAbsent(matchKey(e), k -> new ArrayList<>()).add(e);
        }
        return out;
    }

    /** What makes two entries "the same slot" across the sides: the item, and the potion if any. */
    private static String matchKey(ContainerContentsEntry e) {
        ResourceLocation potion = e.potionId();
        return e.itemId() + (potion == null ? "" : "#" + potion);
    }

    /** Whether the whole comparison found nothing different — never true for a conflict, but cheap to ask. */
    public static boolean identical(Columns c) {
        return c.yours().stream().allMatch(l -> l.state() == State.SAME)
                && c.theirs().stream().allMatch(l -> l.state() == State.SAME);
    }
}
