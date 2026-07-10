package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.network.SurveyQuestionPayload;
import games.brennan.dungeontrain.net.BugReportLogsPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.List;

/**
 * Client-side bug-report log shipping, shared by every place a player can answer the
 * data-driven bug-report survey question ({@link #BUG_REPORT_ID}, {@code dp_surveys/bug_report.json}):
 * the DT death screen ({@link NarrativeDeathScreen}) and the on-demand survey opened via
 * {@code /bug} / {@code /feedback} (DP's {@code SurveyScreen}, routed here through
 * {@code SurveySubmitClientHook}).
 *
 * <p>When the player chose a real-bug option (anything but "No"), recent logs are collected
 * off-thread and shipped to the server, which archives them and posts them to the feedback feed
 * (see {@code BugReportSink}). For a "Lag" answer a short system-spec summary (allocated game
 * memory, CPU/GPU, OS, launcher) is gathered on the render thread and sent alongside so lag
 * reports carry the hardware context needed to diagnose them. Best-effort — a missed/empty
 * collection simply sends nothing.</p>
 */
public final class BugLogReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The data-driven bug-report survey question id (see {@code dp_surveys/bug_report.json}). */
    public static final String BUG_REPORT_ID = "dungeontrain:bug_report";

    private BugLogReporter() {}

    /**
     * If {@code e} is the bug-report question and {@code score} selects a real-bug option (not
     * "No"), collect the player's recent logs and ship them to the server. No-op for any other
     * question or a "No" answer.
     */
    public static void maybeReport(SurveyQuestionPayload.Entry e, int score) {
        if (e == null || !e.id().equals(BUG_REPORT_ID)) return;
        if (score < 0 || score >= e.options().size()) return;
        String label = e.options().get(score);
        if (label.equalsIgnoreCase("No")) return; // not a bug → collect nothing
        // Build the system spec on the render thread (it reads GL/FPS state) — Lag answers only.
        final String spec = label.equalsIgnoreCase("Lag") ? SystemSpecCollector.collect() : "";
        LogCollector.collectAsync().thenAccept(files -> {
            List<BugReportLogsPacket.LogBlob> blobs = files == null ? List.of() : files;
            // Nothing useful to report (no logs and, for non-lag answers, no spec) → send nothing.
            if (blobs.isEmpty() && spec.isEmpty()) return;
            // Hop back to the client thread to send the packet (collection ran on a worker thread).
            Minecraft.getInstance().execute(() -> {
                try {
                    DungeonTrainNet.sendToServer(new BugReportLogsPacket(label, spec, blobs));
                } catch (Exception ex) {
                    LOGGER.warn("[DungeonTrain] Failed to send bug-report logs: {}", ex.toString());
                }
            });
        });
    }
}
