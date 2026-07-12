package games.brennan.dungeontrain.discord;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.net.BugReportLogsPacket.LogBlob;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Server-side sink for a player's bug-report logs (from {@code BugReportLogsPacket}): archives them
 * under {@code logs/<version>/<user>/} via {@link BugReportLogStore}, then posts them as Discord
 * attachments to the feedback feed via Discord Presence
 * ({@link DiscordService#postFeedbackAttachments}). The Discord message names the chosen bug option,
 * lists the attached files, and references the server archive path. For a "Lag" report it also folds
 * the client-collected system-spec summary into the message as an inline code block (allocated game
 * memory, CPU/GPU, OS, launcher) so it's readable at a glance without downloading anything.
 * Best-effort throughout.
 */
public final class BugReportSink {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Keep the spec block comfortably under Discord's 2000-char message limit (the rest is short). */
    private static final int MAX_SPEC_CHARS = 1700;

    private BugReportSink() {}

    public static void accept(ServerPlayer player, String optionLabel, String systemInfo,
                              List<LogBlob> files) {
        boolean hasFiles = files != null && !files.isEmpty();
        boolean hasSpec = systemInfo != null && !systemInfo.isBlank();
        if (!hasFiles && !hasSpec) {
            return;
        }
        String name = player.getGameProfile().getName();

        // 1) Archive the logs on the server (durable copy; useful on dedicated/community servers).
        String pathNote = null;
        if (hasFiles) {
            Path dir = BugReportLogStore.archive(player.getUUID(), name, files);
            pathNote = dir == null ? "(server archive failed)" : BugReportLogStore.displayPath(dir);
        }

        // 2) Post to the feedback feed with the files attached + a reference message.
        List<DiscordService.NamedFile> attachments = new ArrayList<>(hasFiles ? files.size() : 0);
        if (hasFiles) {
            for (LogBlob b : files) {
                attachments.add(new DiscordService.NamedFile(b.filename(), b.bytes()));
            }
        }
        StringBuilder content = new StringBuilder("🐛 Bug report — **")
                .append(sanitizeInline(optionLabel)).append("**");
        if (hasFiles) {
            String filenames = files.stream().map(LogBlob::filename).collect(Collectors.joining(", "));
            content.append("\nAttached logs: ").append(filenames)
                    .append("\nArchived on server: `").append(pathNote).append("`");
        }
        if (hasSpec) {
            content.append("\nSystem info:\n").append(specBlock(systemInfo));
        }
        DiscordService.get().postFeedbackAttachments(player, content.toString(), attachments);

        LOGGER.info("[DungeonTrain] Bug report from {} ({}): {} file(s){} archived to {}",
                name, optionLabel, hasFiles ? files.size() : 0,
                hasSpec ? " + system info" : "", pathNote == null ? "(no archive)" : pathNote);
    }

    /** Wrap the spec in a fenced code block, neutralising stray fences and truncating to fit Discord. */
    private static String specBlock(String spec) {
        String s = spec.replace("```", "ʼʼʼ");
        if (s.length() > MAX_SPEC_CHARS) {
            s = s.substring(0, MAX_SPEC_CHARS) + "\n…";
        }
        return "```\n" + s + "\n```";
    }

    private static String sanitizeInline(String s) {
        if (s == null || s.isBlank()) {
            return "Other";
        }
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
