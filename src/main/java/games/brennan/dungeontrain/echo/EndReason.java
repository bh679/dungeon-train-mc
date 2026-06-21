package games.brennan.dungeontrain.echo;

/**
 * How a {@link EchoEncounter} concluded — drives the closing line of the Discord story and is the
 * trigger for posting it. Exactly one reason ends each encounter.
 */
public enum EndReason {
    /** The primary player killed the echo. */
    ECHO_SLAIN_BY_YOU,
    /** The echo died to something or someone other than the primary player. */
    ECHO_SLAIN,
    /** The echo killed the primary player. */
    YOU_SLAIN_BY_ECHO,
    /** The primary player died to something other than this echo while the encounter was open. */
    YOU_DIED,
    /** The echo passed out of the world (the train moved on / it unloaded) — left behind. */
    LEFT_BEHIND
}
