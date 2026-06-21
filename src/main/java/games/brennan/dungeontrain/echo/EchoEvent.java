package games.brennan.dungeontrain.echo;

/**
 * One beat in a player's encounter with a remote echo, logged in order into an
 * {@link EchoEncounter} and later woven into the Discord story by {@link EchoEncounterFormat}.
 *
 * <p>Each beat is recorded at most once per encounter (first occurrence wins) so the story reads
 * as a clean sequence of distinct moments rather than a tally. Terminal outcomes (who killed whom,
 * or the echo being left behind) are carried separately as an {@link EndReason}.</p>
 */
public enum EchoEvent {
    /** The remote echo materialised on the train. Always the first beat. */
    SPAWNED,
    /** The player came within meeting range of the echo. */
    MET,
    /** The player and echo were within range and had line of sight — eye contact. */
    EYE_CONTACT,
    /** The player crouched near the echo (a deliberate greeting/emote). */
    PLAYER_CROUCHED,
    /** The echo crouched near the player. */
    ECHO_CROUCHED,
    /** The player struck the echo. */
    PLAYER_STRUCK_ECHO,
    /** The echo struck the player. */
    ECHO_STRUCK_PLAYER,
    /** The player gave the echo an item. */
    GAVE_GIFT,
    /** The echo gave the player an item. */
    RECEIVED_GIFT,
    /** The echo left the carriage deck shortly after the player struck it — a deliberate shove. */
    PUSHED_OFF_TRAIN,
    /** The echo left the carriage deck on its own (a fall / a gap), with no recent player strike. */
    FELL_OFF_TRAIN
}
