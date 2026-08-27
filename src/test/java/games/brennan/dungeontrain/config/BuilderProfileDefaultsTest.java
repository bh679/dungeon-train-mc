package games.brennan.dungeontrain.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shipped builder-profile config defaults.
 *
 * <p>The same story as {@link SharedCarriageDefaultsTest}, one schema version later.
 * {@code builderProfileEnabled} is the server-side half of the gate on uploading a Train Builder or
 * Train Editor save ({@code BuilderRelayUpload.canUpload}); it shipped {@code false} and no server
 * ever turned it on, because nothing surfaced the switch anywhere an operator would meet it. The
 * visible symptom was every player opening "My Builds" and being told builder profiles were off on
 * their world — no build was ever uploaded by anyone. This test is the tripwire: if the default
 * drifts back off, it fails here rather than in production silence.</p>
 */
class BuilderProfileDefaultsTest {

    @Test
    @DisplayName("the builder-profile master default is ON")
    void masterDefaultIsOn() {
        assertTrue(DungeonTrainConfig.DEFAULT_BUILDER_PROFILE_ENABLED,
                "builder profiles must ship enabled — with this false no player can upload a build, and "
                        + "the only feedback they get is 'Builder profiles are off on this world'");
    }

    /**
     * A flipped default alone reaches only installs with no config file yet, so it has to be paired
     * with a migration step keyed to a HIGHER version than the one that carried the previous flip.
     * Reusing the existing version would leave every install already stamped at it untouched — the
     * exact silent-no-op this mechanism exists to prevent.
     */
    @Test
    @DisplayName("the builder-profile flip ships its own migration version, above the shared-carriage one")
    void theFlipShipsItsOwnMigrationVersion() {
        assertTrue(DungeonTrainConfig.CURRENT_CONFIG_VERSION >= 2,
                "the builder-profile default flip is delivered by the v1→v2 step in "
                        + "runPendingMigrations(), so CURRENT_CONFIG_VERSION must be at least 2 — at "
                        + "1 every install already stamped v1 skips the step and keeps profiles off "
                        + "forever (found " + DungeonTrainConfig.CURRENT_CONFIG_VERSION + ")");
        assertTrue(DungeonTrainConfig.CURRENT_CONFIG_VERSION <= DungeonTrainConfig.MAX_CONFIG_VERSION,
                "CURRENT_CONFIG_VERSION must stay inside the spec's allowed range, or the value fails "
                        + "validation and NeoForge silently resets it to the default");
    }
}
