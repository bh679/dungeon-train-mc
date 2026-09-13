package games.brennan.dungeontrain.client.version.compare;

import games.brennan.dungeontrain.client.version.LauncherDetector;

/**
 * The two launchers the Dungeon Train modpack is published to. Each one has its own release
 * cadence — Modrinth receives every release including the auto-release cascade ticks, CurseForge
 * only operator releases and only once its review queue clears — so "latest" is a per-platform
 * question, which is the whole reason the Versions page exists.
 *
 * <p>Display names are brand names and are deliberately not translated.</p>
 */
public enum Platform {
    MODRINTH("Modrinth"),
    CURSEFORGE("CurseForge");

    private final String displayName;

    Platform(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public Platform other() {
        return this == MODRINTH ? CURSEFORGE : MODRINTH;
    }

    /**
     * The platform the player is running under. Anything that is not CurseForge (Prism, MultiMC,
     * a bare install) is treated as Modrinth, which is also the recommended download source.
     */
    public static Platform current() {
        return LauncherDetector.source() == LauncherDetector.Source.CURSEFORGE ? CURSEFORGE : MODRINTH;
    }
}
