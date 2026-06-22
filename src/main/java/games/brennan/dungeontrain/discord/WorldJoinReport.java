package games.brennan.dungeontrain.discord;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Builds the one-time "world info" block Dungeon Train appends to a player-join Discord message
 * through Discord Presence's {@code DiscordCredentialsProvider#joinMessageSuffix} seam (the suffix
 * is added under the join line — see {@code DiscordService.joinMessage}). The block carries enough
 * to reproduce and debug the player's run:
 * <ul>
 *   <li>the Dungeon Train mod version,</li>
 *   <li>the train generation seed plus the inputs needed to regenerate the same train
 *       (mode, group size, carriage dims, train Y, starting dimension), and</li>
 *   <li>a list of installed mods + versions, collapsed behind a Discord spoiler ({@code ||…||}).</li>
 * </ul>
 *
 * <p>Fires <b>once per world</b>: the first join that actually posts to Discord flips a per-world
 * flag in {@link DungeonTrainWorldData} (the data never changes for a world's lifetime, so a single
 * post is enough). Discord Presence only invokes this seam after it has decided to post a join
 * message (its {@code onPlayerJoin} bails early when Discord is disabled / network not consented),
 * so the one-shot flag is never spent on a join that produces no message.</p>
 *
 * <p>Everything is wrapped so a failure here can never disrupt the player join.</p>
 */
public final class WorldJoinReport {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Hard ceiling for the suffix length. Discord caps a message's content at 2000 chars and the
     * join body shares the same message, so we stay well under that and truncate the mod list to fit.
     */
    private static final int MAX_SUFFIX_CHARS = 1800;

    private WorldJoinReport() {}

    /**
     * The world-info block for the joining player, or {@code ""} when disabled, already posted for
     * this world, the server/player can't be resolved, or anything goes wrong. Called on the server
     * thread while Discord Presence assembles the join message.
     */
    public static String suffixFor(UUID playerId, String playerName) {
        try {
            if (!DungeonTrainConfig.isWorldJoinReportToDiscord()) {
                return "";
            }
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return "";
            }
            // Resolve the player only to confirm they're a live server player (mirrors DiscordAdvancementSuffix).
            if (server.getPlayerList().getPlayer(playerId) == null) {
                return "";
            }

            DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
            if (data.joinReportPosted()) {
                return "";
            }
            data.markJoinReportPosted();
            return build(data);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] world-join Discord report failed: {}", t.toString());
            return "";
        }
    }

    private static String build(DungeonTrainWorldData data) {
        // Seed + mode + groupSize as one record; the rest of the regen inputs from the world data.
        var cfg = data.getGenerationConfig();
        var dims = data.dims();

        StringBuilder header = new StringBuilder()
                .append("🚂 **Dungeon Train v").append(modVersion()).append("** — world info\n")
                .append("**Train seed:** `").append(cfg.seed()).append("`")
                .append(" · mode ").append(cfg.mode())
                .append(" · group ").append(cfg.groupSize())
                .append(" · dims ").append(dims.length()).append('×').append(dims.width()).append('×').append(dims.height())
                .append(" · Y ").append(data.getTrainY())
                .append(" · ").append(data.startingDimension())
                .append('\n');

        return header.append(modsSpoiler(header.length())).toString();
    }

    /**
     * The installed-mods line: {@code **Mods (N):** ||modid vX … +K more||}, alphabetised and
     * truncated so the whole suffix stays under {@link #MAX_SUFFIX_CHARS}.
     *
     * @param headerLen chars already consumed by the header, so the mod list fits the remaining budget
     */
    private static String modsSpoiler(int headerLen) {
        List<String> mods = installedMods();
        String label = "**Mods (" + mods.size() + "):** ";
        // Remaining room minus the label, the two `||` spoiler wrappers, and a tail allowance for "+K more".
        int budget = MAX_SUFFIX_CHARS - headerLen - label.length() - 4;
        final int tailReserve = 24;

        StringBuilder list = new StringBuilder();
        int shown = 0;
        for (String mod : mods) {
            String line = (list.length() == 0 ? "" : "\n") + mod;
            if (budget - list.length() - line.length() < tailReserve && shown < mods.size()) {
                break; // out of room — remaining mods summarised below
            }
            list.append(line);
            shown++;
        }
        if (shown < mods.size()) {
            list.append("\n… +").append(mods.size() - shown).append(" more");
        }
        return label + "||" + list + "||";
    }

    private static String modVersion() {
        return ModList.get().getModContainerById(DungeonTrain.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    private static List<String> installedMods() {
        List<String> out = new ArrayList<>();
        for (var info : ModList.get().getMods()) {
            out.add(info.getModId() + " v" + info.getVersion());
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }
}
