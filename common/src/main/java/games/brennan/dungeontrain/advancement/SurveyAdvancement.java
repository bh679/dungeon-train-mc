package games.brennan.dungeontrain.advancement;
import games.brennan.dungeontrain.platform.DtPlatform;
import games.brennan.dungeontrain.DtCore;

import com.mojang.logging.LogUtils;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * "The Great Beyond" — granted when a player completes the death-screen feedback
 * survey (answers every outstanding question). The survey itself lives in the
 * bundled Discord Presence mod, which calls back via its
 * {@code DiscordCredentialsProvider.onSurveyCompleted} seam (wired in
 * {@link DungeonTrain}); this class turns that signal into the advancement.
 *
 * <p>The advancement JSON
 * ({@code data/dungeontrain/advancement/dungeon_train/the_great_beyond.json})
 * carries a single {@code minecraft:impossible} criterion, so it never fires on
 * its own — we award it directly here, exactly like {@link CompletionistAdvancement}.
 * Direct award (rather than a custom trigger) is deliberate: completing a feedback
 * survey is a meta action, so it should count in any game mode. Like every
 * other advancement, whether it survives to the cross-world profile is still
 * decided by
 * {@link games.brennan.dungeontrain.cheat.RunIntegrity#persistsAdvancement} in
 * {@code AchievementEvents}.</p>
 */
public final class SurveyAdvancement {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Stable id of the survey-completion advancement. */
    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath(DtCore.MOD_ID, "dungeon_train/the_great_beyond");

    private SurveyAdvancement() {}

    /**
     * Resolve the player by id on the current server and grant them the advancement.
     * Called from Discord Presence's {@code onSurveyCompleted} seam on the server
     * thread; a no-op when the server is unavailable or the player has since logged
     * out (e.g. they quit between the final answer and this callback).
     */
    public static void onSurveyCompleted(UUID playerId) {
        MinecraftServer server = DtPlatform.get().getCurrentServer();
        if (server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        grant(player);
    }

    /**
     * Grant "The Great Beyond" to {@code player}. Idempotent: returns early when the
     * advancement data isn't loaded or it's already earned, then awards each criterion
     * key (just the single {@code impossible} criterion).
     */
    public static void grant(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerAdvancementManager mgr = server.getAdvancements();
        AdvancementHolder self = mgr.get(ID);
        if (self == null) return; // advancement data not loaded (e.g. datapack stripped)
        if (player.getAdvancements().getOrStartProgress(self).isDone()) return; // already earned

        boolean granted = false;
        for (String key : self.value().criteria().keySet()) {
            if (player.getAdvancements().award(self, key)) granted = true;
        }
        if (granted) {
            LOGGER.info("[DungeonTrain] Granted 'The Great Beyond' (feedback survey completed) to {}",
                player.getName().getString());
        }
    }
}
