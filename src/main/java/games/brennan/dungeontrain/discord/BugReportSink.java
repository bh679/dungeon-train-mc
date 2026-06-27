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
 * lists the attached files, and references the server archive path. Best-effort throughout.
 */
public final class BugReportSink {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BugReportSink() {}

    public static void accept(ServerPlayer player, String optionLabel, List<LogBlob> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        String name = player.getGameProfile().getName();

        // 1) Archive on the server (durable copy; useful on dedicated/community servers).
        Path dir = BugReportLogStore.archive(player.getUUID(), name, files);
        String pathNote = dir == null ? "(server archive failed)" : BugReportLogStore.displayPath(dir);

        // 2) Post to the feedback feed with the files attached + a reference message.
        List<DiscordService.NamedFile> attachments = new ArrayList<>(files.size());
        for (LogBlob b : files) {
            attachments.add(new DiscordService.NamedFile(b.filename(), b.bytes()));
        }
        String filenames = files.stream().map(LogBlob::filename).collect(Collectors.joining(", "));
        String content = "🐛 Bug report — **" + sanitizeInline(optionLabel) + "**\n"
                + "Attached logs: " + filenames + "\n"
                + "Archived on server: `" + pathNote + "`";
        DiscordService.get().postFeedbackAttachments(player, content, attachments);

        LOGGER.info("[DungeonTrain] Bug report from {} ({}): {} file(s) archived to {}",
                name, optionLabel, files.size(), pathNote);
    }

    private static String sanitizeInline(String s) {
        if (s == null || s.isBlank()) {
            return "Other";
        }
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
