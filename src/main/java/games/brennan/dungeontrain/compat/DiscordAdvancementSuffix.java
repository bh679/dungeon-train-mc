package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.difficulty.DifficultyProgression;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.UUID;

/**
 * Builds the plain-text game-state line Dungeon Train appends below each Discord advancement
 * announcement (on its own line, outside the embed) through Discord Presence's
 * {@code DiscordCredentialsProvider#advancementMessageSuffix} seam: the earning player's current
 * carriage # and difficulty level. Both come from the exact values the in-game HUD shows, so the
 * Discord line and the HUD never disagree.
 *
 * <p>Invoked on the server thread while DP assembles the announcement — see the provider registration
 * in {@code DungeonTrain.commonSetup}.</p>
 */
public final class DiscordAdvancementSuffix {

    private DiscordAdvancementSuffix() {}

    /**
     * A single line like {@code "Carriage +7 · Difficulty Level 3"} for the player who just earned an
     * advancement, or {@code ""} when the server / player can't be resolved. The carriage half is
     * dropped when the player is off-train (no HUD carriage value); difficulty is always present.
     */
    public static String forPlayer(UUID playerId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return "";
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return "";
        }

        // Difficulty level: the player's personal tier — identical to the HUD difficulty read-out
        // (DifficultyProgression.tierForTravelled of their EFFECTIVE, offset-inclusive counter).
        int tier = DifficultyProgression.tierForTravelled(DifficultyProgression.effectiveTravelled(
                ModDataAttachments.DT_PLAYER_RUN_STATE.get(player).travelledCarriageIndex()));
        String difficulty = "Difficulty Level " + tier;

        // Carriage #: the exact index last shown on this player's HUD ("Carriage: +N"). Null when they
        // aren't tracked near a train right now (e.g. the advancement was earned off-train) — then we
        // emit difficulty only.
        Integer carriage = TrainCarriageAppender.lastCarriageIndex(playerId);
        if (carriage == null) {
            return difficulty;
        }
        return "Carriage " + formatSigned(carriage) + " · " + difficulty;
    }

    /** Sign-prefixed exactly like the HUD's carriage read-out: "+5" / "0" / "-5". */
    private static String formatSigned(int n) {
        return n > 0 ? "+" + n : Integer.toString(n);
    }
}
