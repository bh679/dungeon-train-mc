package games.brennan.dungeontrain.client.builder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which relay build pictures to ask for next, and in what order.
 *
 * <p>Every ask is a relay round trip the server makes on the player's behalf, and only a few may be
 * out at once, so the order is the whole point:</p>
 * <ol>
 *   <li>{@link Tier#SELECTED} — the build the player has picked: as it is now, the version they
 *       have paged to, its version index, then the rest of its history newest first.</li>
 *   <li>{@link Tier#NEWEST} — every build on screen as it is now, in grid order.</li>
 *   <li>{@link Tier#HISTORY} — only once every build on screen has its current picture: each one's
 *       version index, then one past version per build per round, newest first.</li>
 * </ol>
 *
 * <p>Pure: no Minecraft, no clock — {@link State} answers for the cache, so the order can be tested
 * on its own.</p>
 */
public final class RelayPreviewQueue {

    /** {@code seq} of the version-index ask: the index plus the newest recorded frame. */
    public static final int INDEX = -1;

    /** Why an ask was chosen; the earlier, the more it matters. */
    public enum Tier { SELECTED, NEWEST, HISTORY }

    /** One ask to send. */
    public record Ask(int relayId, int seq, Tier tier) {}

    /** What the cache already knows. */
    public interface State {
        /** An answer for this build at this seq is held (a picture, or "no picture"). */
        boolean has(int relayId, int seq);

        /** Asked for and not yet answered. */
        boolean inFlight(int relayId, int seq);

        /** Failed recently for a reason that may pass; not to be asked again yet. */
        boolean backingOff(int relayId, int seq);

        /** Every seq this build's history holds, oldest first, or null until its index answered. */
        int[] versions(int relayId);
    }

    private RelayPreviewQueue() {}

    /**
     * The next asks, at most {@code slots} of them.
     *
     * @param visible      builds on screen, in grid order
     * @param selectedId   the picked build, or {@code <= 0} for none
     * @param selectedSeq  the version the picked build is paged to; 0 for as it is now
     * @param historyRoom  whether background history may still be fetched (the version budget)
     */
    public static List<Ask> next(List<Integer> visible, int selectedId, int selectedSeq,
                                 State state, int slots, boolean historyRoom) {
        List<Ask> out = new ArrayList<>();
        if (slots <= 0) return out;
        Set<Long> chosen = new HashSet<>();
        Picker pick = new Picker(state, slots, out, chosen);

        if (selectedId > 0) {
            pick.offer(selectedId, 0, Tier.SELECTED);
            if (selectedSeq > 0) pick.offer(selectedId, selectedSeq, Tier.SELECTED);
            pick.offer(selectedId, INDEX, Tier.SELECTED);
            int[] seqs = state.versions(selectedId);
            if (seqs != null) {
                for (int i = seqs.length - 1; i >= 0 && !pick.full(); i--) {
                    pick.offer(selectedId, seqs[i], Tier.SELECTED);
                }
            }
        }

        for (int id : visible) {
            if (pick.full()) return out;
            pick.offer(id, 0, Tier.NEWEST);
        }

        if (!historyRoom || !allCurrent(visible, state)) return out;

        for (int id : visible) {
            if (pick.full()) return out;
            pick.offer(id, INDEX, Tier.HISTORY);
        }
        // Round-robin: the newest past version of every build, then the next newest of every build…
        for (int round = 0; !pick.full(); round++) {
            boolean any = false;
            for (int id : visible) {
                if (pick.full()) return out;
                int[] seqs = state.versions(id);
                if (seqs == null || round >= seqs.length) continue;
                any = true;
                pick.offer(id, seqs[seqs.length - 1 - round], Tier.HISTORY);
            }
            if (!any) break;
        }
        return out;
    }

    /** Every build on screen has an answer for how it is now — the gate history waits on. */
    static boolean allCurrent(List<Integer> visible, State state) {
        for (int id : visible) {
            if (!state.has(id, 0)) return false;
        }
        return true;
    }

    /** Collects asks that are still worth sending, once each, up to the slots free. */
    private static final class Picker {
        private final State state;
        private final int slots;
        private final List<Ask> out;
        private final Set<Long> chosen;

        Picker(State state, int slots, List<Ask> out, Set<Long> chosen) {
            this.state = state;
            this.slots = slots;
            this.out = out;
            this.chosen = chosen;
        }

        boolean full() {
            return out.size() >= slots;
        }

        void offer(int relayId, int seq, Tier tier) {
            if (full() || relayId <= 0 || !wanted(relayId, seq)) return;
            long key = ((long) relayId << 32) | (seq & 0xFFFFFFFFL);
            if (!chosen.add(key)) return;
            out.add(new Ask(relayId, seq, tier));
        }

        private boolean wanted(int relayId, int seq) {
            if (state.inFlight(relayId, seq) || state.backingOff(relayId, seq)) return false;
            // The index is answered once VERSIONS knows the build; its frame is cached at its own seq.
            if (seq == INDEX) return state.versions(relayId) == null;
            return !state.has(relayId, seq);
        }
    }
}
