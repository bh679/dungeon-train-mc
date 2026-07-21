package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.discord.WorldInfoReporter;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Resolves the "world language" — the client locale that scopes which player-written content (shared
 * books + narrative series) this world receives from the relay. Both delivery paths ({@code
 * SharedBookPool.rollShared} for chest loot, {@code BookFactory.buildOrRandomForLectern} for lecterns)
 * are world-scoped and deterministic — neither has per-player context — so language is resolved once,
 * at pool-fetch time, from the HOST / primary player and passed to the relay, which returns only
 * content in that language family (falling back to English when the family has none).
 *
 * <p>Resolution order (host-first, per the feature's chosen multiplayer policy):</p>
 * <ol>
 *   <li>The single-player / LAN-host owner ({@link MinecraftServer#getSingleplayerProfile()}), if that
 *       player is online — the common case, where the world language is simply the owner's.</li>
 *   <li>Else (a dedicated server with no host) the first online player, as a reasonable primary.</li>
 *   <li>Else (nobody online) {@code ""} — the relay then leaves the pool unfiltered / defaults to
 *       English, and the next refresh re-resolves once a player is on.</li>
 * </ol>
 *
 * <p>Re-resolved on every pool refresh (~30 s), so a player changing their Minecraft language mid-world
 * naturally re-scopes the pool on the next tick. Never throws; any failure yields {@code ""}.</p>
 */
public final class WorldLanguage {

    private WorldLanguage() {}

    /**
     * The host / primary player's raw client locale (e.g. {@code "en_us"}), or {@code ""} when it cannot
     * be determined. Reuses {@link WorldInfoReporter#clientLanguage(ServerPlayer)} for the per-player read.
     */
    public static String hostLocale(MinecraftServer server) {
        if (server == null) return "";
        try {
            ServerPlayer host = resolveHost(server);
            if (host == null) return "";
            return WorldInfoReporter.clientLanguage(host); // "" when unavailable — never throws
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * The host / primary player's dash-stripped UUID (e.g. {@code "0123abcd…"}) for personalising the
     * relay pool fetch to that player's global read history (dp-relay {@code &uuid=}), or {@code ""} when
     * it must not be sent.
     *
     * <p>Gated on the host having granted network consent ({@link NetworkConsentMirror#isGranted}): a
     * player who declined telemetry has no reads recorded on the relay (so personalisation is moot anyway),
     * and their UUID must not be sent. {@code ""} when nobody is online or consent is absent — the caller
     * then omits the param and the relay serves an unpersonalised window, exactly as before. Never throws;
     * any failure yields {@code ""}.</p>
     *
     * <p>Same host as {@link #hostLocale}, so the pool is scoped to one consistent primary player.
     * Multiplayer non-goal: the pool is a single shared fetch, so it personalises to the host only; other
     * players fall back to their own client-side read set (see {@code SharedBookReadMirror}).</p>
     */
    public static String hostUuidConsented(MinecraftServer server) {
        if (server == null) return "";
        try {
            ServerPlayer host = resolveHost(server);
            if (host == null) return "";
            if (!NetworkConsentMirror.isGranted(host)) return "";
            return host.getUUID().toString().replace("-", "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** Pick the host player (owner if online), else the first online player, else {@code null}. */
    private static ServerPlayer resolveHost(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return null;
        var ownerProfile = server.getSingleplayerProfile();
        if (ownerProfile != null) {
            ServerPlayer owner = server.getPlayerList().getPlayer(ownerProfile.getId());
            if (owner != null) return owner;
        }
        return players.get(0);
    }
}
