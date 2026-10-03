package games.brennan.dungeontrain.discord;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.CompletionistAdvancement;
import games.brennan.dungeontrain.advancement.StartAgainAdvancement;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.compat.DiscordAdvancementSuffix;
import games.brennan.dungeontrain.net.CaptureAdvancementPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Set;

/**
 * Announces the rarest advancements in the public passenger log — and pings the maintainer.
 *
 * <p>Three earns are worth hearing about the moment they happen: the "Everything Burrito"
 * capstone ({@link CompletionistAdvancement}), "It's Not That Simple" — the one after it
 * ({@link StartAgainAdvancement}) — and "The Long Run", 250,000 m in a single life. Each posts a
 * top-level report to the same public feed as the death manifest and remote-echo stories
 * ({@link DungeonTrain#manifestWebhookOverride()}), carrying an @-mention of
 * {@link DungeonTrain#BRENNAN_DISCORD_ID} through Discord Presence's trusted allow-list.</p>
 *
 * <p>Free Play never posts: a run that {@link RunIntegrity#isCheated} says is tainted — creative,
 * cheats, an unapproved mod, a granted advancement — is not an earn. Cross-world replays are kept
 * out by the caller ({@code AchievementEvents.onAdvancementEarn} only calls this on a genuine
 * earn). Best-effort, like every other reporter here: Discord can never disturb the earn.</p>
 */
public final class MilestoneAdvancementReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 250,000 m aboard in one life — {@code data/dungeontrain/advancement/dungeon_train/the_long_run.json}. */
    public static final ResourceLocation THE_LONG_RUN_ID =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/the_long_run");

    /** The advancements the passenger log hears about. */
    static final Set<ResourceLocation> MILESTONES = Set.of(
            CompletionistAdvancement.ID, StartAgainAdvancement.ID, THE_LONG_RUN_ID);

    /** Gold, like a challenge frame — distinct from the death-report red and the echo story's blue. */
    static final int EMBED_COLOR = 0xFFC107;

    private MilestoneAdvancementReporter() {}

    /** Is {@code id} one of the three announced advancements? */
    static boolean isMilestone(ResourceLocation id) {
        return MILESTONES.contains(id);
    }

    /**
     * Post the announcement if {@code advancement} is a milestone and the run is clean. Server thread,
     * on a genuine (non-replay) earn.
     */
    public static void maybePost(ServerPlayer player, AdvancementHolder advancement) {
        if (!isMilestone(advancement.id())) return;
        try {
            if (RunIntegrity.isCheated(player)) {
                LOGGER.info("[DungeonTrain] {} earned {} in Free Play — not announced.",
                        player.getGameProfile().getName(), advancement.id());
                return;
            }
            String name = player.getGameProfile().getName();
            DisplayInfo display = advancement.value().display().orElse(null);
            String advancementTitle = display == null ? advancement.id().getPath()
                    : display.getTitle().getString();
            String title = title(name, advancementTitle);
            // The hint, never the description: the description is a secret other players are meant
            // to find for themselves; the hint is what the advancements screen already shows everyone.
            String description = description(hint(advancement.id()),
                    DiscordAdvancementSuffix.forPlayer(player.getUUID()));
            LOGGER.info("[DungeonTrain] {} earned {} — announcing in the passenger log.", name, advancement.id());
            MilestonePostBuffer.await(player, advancement.id(), title, description,
                    DungeonTrain.manifestWebhookOverride());
            DungeonTrainNet.sendTo(player, new CaptureAdvancementPacket(advancement.id()));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] milestone advancement announcement failed: {}", t.toString());
        }
    }

    /** The lang key of an advancement's hint — {@code dungeon_train/the_long_run} → {@code advancements.dungeontrain.dungeon_train.the_long_run.hint}. */
    static String hintKey(ResourceLocation id) {
        return "advancements." + id.getNamespace() + "." + id.getPath().replace('/', '.') + ".hint";
    }

    /** The hint as the server renders it, or {@code ""} when the advancement has none. */
    static String hint(ResourceLocation id) {
        String key = hintKey(id);
        String text = Component.translatable(key).getString();
        return key.equals(text) ? "" : text;
    }

    /** {@code "🏆 Steve earned Everything Burrito"}. */
    static String title(String playerName, String advancementTitle) {
        return "🏆 " + playerName + " earned " + advancementTitle;
    }

    /** The advancement's own description, then the carriage/difficulty line on its own line when there is one. */
    static String description(String advancementDescription, String gameStateLine) {
        String body = advancementDescription == null ? "" : advancementDescription.strip();
        String state = gameStateLine == null ? "" : gameStateLine.strip();
        if (state.isEmpty()) return body;
        return body.isEmpty() ? state : body + "\n\n" + state;
    }
}
