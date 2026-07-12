package games.brennan.dungeontrain.net;

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * The server-chosen narrative strings for the paginated death screen, rolled
 * per-death from the {@code death_lore} pool by
 * {@code games.brennan.dungeontrain.narrative.DeathLoreStore}. Carried inside
 * {@link DeathStatsPacket} so the client renders exactly what the server
 * picked (the client lacks the death context + the data pool).
 *
 * <p>Fields are {@code <page><Slot>} for the five narrative pages — fall,
 * deeds, gear, lives, platform. The two survey pages ("the creator's ledger")
 * are NOT here: they come from the bundled Discord Presence survey system.</p>
 *
 * <p>An empty string means "no line for this slot" — the screen simply omits
 * that element. {@link #EMPTY} is the all-empty fallback used when no pool is
 * loaded (e.g. a death before {@code ServerStartingEvent} populated it).</p>
 */
public record DeathNarrative(
        String fallQuestion, String fallNarration,
        String deedsQuestion, String deedsNarration,
        String gearQuestion, String gearNarration,
        String livesQuestion, String livesSubline, String livesNarration,
        String platformQuestion, String platformNarration, String platformEpitaph) {

    public static final DeathNarrative EMPTY =
            new DeathNarrative("", "", "", "", "", "", "", "", "", "", "", "");

    public void encode(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(fallQuestion);
        buf.writeUtf(fallNarration);
        buf.writeUtf(deedsQuestion);
        buf.writeUtf(deedsNarration);
        buf.writeUtf(gearQuestion);
        buf.writeUtf(gearNarration);
        buf.writeUtf(livesQuestion);
        buf.writeUtf(livesSubline);
        buf.writeUtf(livesNarration);
        buf.writeUtf(platformQuestion);
        buf.writeUtf(platformNarration);
        buf.writeUtf(platformEpitaph);
    }

    public static DeathNarrative decode(RegistryFriendlyByteBuf buf) {
        return new DeathNarrative(
                buf.readUtf(), buf.readUtf(),
                buf.readUtf(), buf.readUtf(),
                buf.readUtf(), buf.readUtf(),
                buf.readUtf(), buf.readUtf(), buf.readUtf(),
                buf.readUtf(), buf.readUtf(), buf.readUtf());
    }
}
