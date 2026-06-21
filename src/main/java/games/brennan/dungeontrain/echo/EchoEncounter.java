package games.brennan.dungeontrain.echo;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * The running journal for one player's encounter with one remote echo — the ordered beats observed
 * plus the bookkeeping the periodic scan carries between samples. Created at spawn, finalised once
 * by {@link RemoteEchoEncounters} when the encounter ends, then turned into prose by
 * {@link EchoEncounterFormat}.
 *
 * <p>Mutable, single-thread (server) state; not shared. Beats are deduped to first-occurrence so the
 * story reads as distinct moments.</p>
 */
final class EchoEncounter {

    /** The echo entity's UUID — the journal key. */
    final UUID echoId;
    /** The dimension the echo spawned in (only that level's scan drives this journal). */
    final ResourceKey<Level> dimension;
    /** Who the echo embodies — the cross-world source player. */
    final UUID sourcePlayerId;
    final String sourceName;
    /** The player the story is about (nearest at spawn); the post is addressed to them. */
    final UUID primaryPlayerId;
    /** The carriage depth the echo is themed to (where the source player died); {@code <0} if unknown. */
    final int sourceCarriage;
    final long spawnTick;

    private final List<EchoEvent> beats = new ArrayList<>();
    private final EnumSet<EchoEvent> seen = EnumSet.noneOf(EchoEvent.class);

    /** Most recent tick the primary player struck the echo (for shove-vs-fall classification). */
    long lastStruckByPlayerTick = Long.MIN_VALUE;
    /** Last observed carriage-deck support, or {@code null} until first sampled. */
    Boolean lastOnDeck = null;
    /** Stage C: the captured screenshot of the echo, or {@code null} for the text-only post. */
    byte[] photo = null;

    EchoEncounter(UUID echoId, ResourceKey<Level> dimension, UUID sourcePlayerId, String sourceName,
                  UUID primaryPlayerId, int sourceCarriage, long spawnTick) {
        this.echoId = echoId;
        this.dimension = dimension;
        this.sourcePlayerId = sourcePlayerId;
        this.sourceName = sourceName;
        this.primaryPlayerId = primaryPlayerId;
        this.sourceCarriage = sourceCarriage;
        this.spawnTick = spawnTick;
    }

    /** Record a beat the first time it happens; later repeats are ignored. Returns true if newly logged. */
    boolean log(EchoEvent event) {
        if (!seen.add(event)) {
            return false;
        }
        beats.add(event);
        return true;
    }

    boolean has(EchoEvent event) {
        return seen.contains(event);
    }

    /** The beats in the order they first occurred (excludes the terminal {@link EndReason}). */
    List<EchoEvent> beats() {
        return beats;
    }
}
