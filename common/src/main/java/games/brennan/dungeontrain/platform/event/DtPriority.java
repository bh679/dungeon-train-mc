package games.brennan.dungeontrain.platform.event;

/**
 * Loader-neutral mirror of NeoForge's {@code EventPriority}, used by
 * {@link DtEvent} to preserve <b>cross-mod</b> listener ordering when a Dungeon
 * Train event category is taken off the NeoForge bus.
 *
 * <p>On the NeoForge bus, each DT handler's {@code EventPriority} orders it not
 * only against DT's own handlers but against <em>other mods'</em> listeners
 * (Sable, DiscordPresence, PlayerMob, vanilla). A single default-priority bridge
 * would collapse every DT handler onto one priority and silently reorder them
 * relative to those mods. To avoid that, a bridge subscribes the real NeoForge
 * event <b>once per priority tier that DT actually uses</b> — each subscription
 * annotated with the matching {@code EventPriority} — and fires only that tier's
 * bucket ({@link DtEvent#listeners(DtPriority)}). Other mods' listeners then
 * interleave between DT's tiers exactly as before.</p>
 *
 * <p>Declaration order here is high-to-low, matching NeoForge's firing order
 * (HIGHEST first). Within a single tier, DT's own handlers fire in registration
 * order (see {@link DtEvent}).</p>
 */
public enum DtPriority {
    HIGHEST,
    HIGH,
    NORMAL,
    LOW,
    LOWEST;
}
