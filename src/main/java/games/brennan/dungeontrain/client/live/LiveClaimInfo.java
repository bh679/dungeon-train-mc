package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.discord.VersionFreshness;

import java.time.LocalDate;

/**
 * What a Live Feed claim tells the relay about the streamer, for the #live-eye Discord post's
 * "🟢 DT 0.1262.0 · 💬 Chat on" line: this mod version, its freshness dot (the same
 * {@link VersionFreshness} a survey post shows) and whether Live Chat is on. Kid mode always has
 * Live Chat off, whatever the switch says.
 */
public record LiveClaimInfo(String version, String versionDot, boolean chat) {

    public static LiveClaimInfo of(String version, String latest, String buildDate, LocalDate today,
                                   boolean liveChatSetting, boolean kid) {
        String dot = VersionFreshness.classify(version, latest, buildDate, today).dot();
        return new LiveClaimInfo(version == null ? "" : version, dot, liveChatSetting && !kid);
    }
}
