package games.brennan.dungeontrain.worldgen;

/**
 * What the Lost City template pre-load has been asked for, and whether a pre-load task is still running —
 * the bookkeeping {@code event/LostCityTemplatePreloadEvents} evicts by.
 *
 * <p>Running is a <b>count</b> of live tasks, not a flag: a server stop or {@code /reload} re-arms the pre-load
 * while an earlier task may still be loading, and a second task can be queued behind it. With a flag the first
 * task's exit cleared it and eviction ran under the second; a count only reaches zero when the last one ends.
 * A task of an older {@link #reset epoch} still counts until it ends — it stops early ({@link #current}), but
 * it may be mid-template.</p>
 *
 * <p>Every transition takes this object's monitor, so a pre-load started from a worldgen thread
 * ({@link LostCityTemplateDemand}) cannot interleave with an eviction on the server thread. {@link #scope()}
 * is a lock-free read for the worldgen hot path.</p>
 */
public final class LostCityPreloadState {

    /** How much of the template set has been asked for since the last eviction or reset. */
    public enum Scope {
        /** Nothing: the cache is cold, or holds only what worldgen loaded on demand. */
        NONE,
        /** This world's WWOO foretaste pick. */
        FORETASTE,
        /** Every placeable template. */
        FULL
    }

    /** A started pre-load task: the epoch it belongs to. */
    public record Ticket(int epoch) {}

    private int epoch;
    private int running;
    private volatile Scope scope = Scope.NONE;

    /**
     * Claims a pre-load of {@code wanted}: a ticket if nothing that wide has been asked for yet, else
     * {@code null}. The caller owes one {@link #end} per ticket.
     */
    public synchronized Ticket tryBegin(Scope wanted) {
        if (wanted.ordinal() <= scope.ordinal()) return null;
        scope = wanted;
        running++;
        return new Ticket(epoch);
    }

    /** A task claimed by {@code ticket} has finished, failed or was never scheduled. */
    public synchronized void end(Ticket ticket) {
        if (running > 0) running--;
    }

    /** Whether {@code ticket}'s task still loads into the live cache — false once a reset has passed it. */
    public synchronized boolean current(Ticket ticket) {
        return ticket.epoch() == epoch;
    }

    /** Server stop or datapack reload: the cache was replaced, so every earlier task is stale and the pre-load re-arms. */
    public synchronized void reset() {
        epoch++;
        scope = Scope.NONE;
    }

    /**
     * Runs {@code removal} and re-arms the pre-load, unless a task is still running. The monitor is held
     * across the removal so a pre-load cannot start between the two. If {@code removal} throws, nothing is re-armed.
     *
     * @return whether the removal ran
     */
    public synchronized boolean evict(Runnable removal) {
        if (running > 0) return false;
        removal.run();
        scope = Scope.NONE;
        return true;
    }

    public synchronized boolean running() {
        return running > 0;
    }

    public Scope scope() {
        return scope;
    }
}
