package games.brennan.dungeontrain;

import games.brennan.dungeontrain.client.VersionInfo;
import java.util.List;

/**
 * Loader-neutral core constants and Discord-relay routing helpers for Dungeon
 * Train, extracted from the NeoForge {@code @Mod} entry point ({@link DungeonTrain})
 * so {@code :common} game logic (and a future Fabric entry point) can reference
 * them without pulling in the NeoForge loader classes {@code DungeonTrain} needs.
 *
 * <p>{@link DungeonTrain} keeps only the NeoForge wiring — bus/bridge init, config
 * registration, jarJar sibling-mod compat — and reads its constants from here. All
 * members are pure data or pure branch-string mappings (routed off
 * {@link VersionInfo#BRANCH}); nothing here touches a mod loader.</p>
 */
public final class DtCore {

    private DtCore() {}

    /** The mod id. Single source of truth for registries, payload ids, and {@code @EventBusSubscriber}. */
    public static final String MOD_ID = "dungeontrain";

    /**
     * Brennan's Discord user id. Single source of truth for both the chat-tag ping triggers
     * ({@code gameRelayMentions}) and the editor-welcome presence line ({@code presenceTrackUserIds}
     * + {@code EditorWelcome}).
     */
    public static final String BRENNAN_DISCORD_ID = "342110421114945537";

    /**
     * Chat-tag tokens that reach Brennan. Single source of truth for both the DP relay rewrite
     * ({@code gameRelayMentions} maps each to {@code @token=<@id>}, pinging Brennan in the community feed)
     * and the in-game presence reply ({@link games.brennan.dungeontrain.event.MentionPresenceEvents}). DP
     * matches each token as a case-insensitive substring, so the in-game detector mirrors that to fire in
     * lock-step with the real ping.
     */
    public static final List<String> MENTION_TOKENS = List.of("@dev", "@brennanhatton");

    /**
     * Discord relay capability URLs. Release builds (the {@code main} branch) report to the live
     * community feed; every other build (feature branches, worktrees, detached HEAD) reports to a
     * separate dev channel via a distinct capability, so dev/test runs never post to the live feed by
     * accident. Both caps are non-secret + revocable (they ship in the jar like the prod cap always
     * has); the actual dev-channel webhook lives only on the relay (its {@code DEV_WEBHOOK_URL}), never
     * in the jar. The same relay bot serves both channels, so the dev feed has full message parity
     * (threads / chat / presence) — only the destination differs.
     */
    private static final String RELAY_LIVE_BASE_URL =
            "https://brennan.games/api/dp-relay/adc3dc432f437e9401092c143dec86767dd06c2a5d94f48f";
    private static final String RELAY_DEV_BASE_URL =
            "https://brennan.games/api/dp-relay/0e908e2d067e81eb3e31e43e6c4e337182db24232994dc25";
    /**
     * Public death-feed capability. The redesigned "manifest" death report routes here on RELEASE
     * ({@code main}) builds so legit production deaths land in a read-only PUBLIC channel, separate from
     * the live community feed (which keeps the threaded report). Non-secret + revocable like the others;
     * the real public-channel webhook lives only on the relay ({@code PUBLIC_WEBHOOK_URL}).
     */
    private static final String RELAY_PUBLIC_BASE_URL =
            "https://brennan.games/api/dp-relay/55800db451a785a14030978edf0e352179a160287da24981";
    /**
     * Survey-results channel capability. Each feedback-survey answer ALSO posts a copy (the same
     * embed plus a jump-link back to the threaded original) into a dedicated, flat survey-results
     * channel — so all feedback is browsable in one place instead of buried in per-player threads.
     * Routes here on RELEASE ({@code main}) builds; dev/test builds fall through to the build's
     * default cap (the dev channel), keeping the dev preview intact. Non-secret + revocable like the
     * others; the real survey-results webhook lives only on the relay (its {@code SURVEY_WEBHOOK_URL},
     * mapped to this cap in the relay's {@code .env} / {@code CAPS} registry, never in the jar).
     */
    private static final String RELAY_SURVEY_RESULTS_BASE_URL =
            "https://brennan.games/api/dp-relay/425a859527bbab2b6defc48e483abd3b32b277c448e11ddf";

    /**
     * Discord guild (server) ids used to build the survey copy's jump-link back to the threaded
     * original ({@code https://discord.com/channels/{guild}/{thread}/{message}}). DP cannot learn the
     * guild id at runtime in relay-mode, so DT supplies it: the original answer posts into the build's
     * default channel (live community feed on {@code main}, dev channel otherwise), so the link's
     * guild must match that channel's server. Non-secret. Blank → the copy posts without a link.
     *
     * <p>The live community feed and the dev channel both live in the "Dungeon Train MC" server, so
     * both ids are the same value.</p>
     */
    static final String LIVE_GUILD_ID = "680177367381049356";
    static final String DEV_GUILD_ID = "680177367381049356";

    /**
     * True for any non-release build — the same branch-ref dev signal the title screen + version HUD
     * use ({@code !"main".equals(branch)}). Drives dev-vs-live Discord relay routing, and (server-side)
     * lets dev/test builds report Free Play death runs to the dev channel — see
     * {@link games.brennan.dungeontrain.event.RunStatsEvents}. Public so that single dev signal has one
     * source of truth rather than a duplicated branch check.
     */
    public static boolean isDevBuild() {
        return VersionInfo.isDevBuild();
    }

    /** The relay capability this build reports through: the dev channel for dev builds, live on main. */
    public static String discordRelayBaseUrl() {
        return relayBaseUrlForBranch(VersionInfo.BRANCH);
    }

    /**
     * The relay capability base URL this build talks to (dev channel for dev/test builds, live on
     * {@code main}). Public so client-side features with no Minecraft-server connection — e.g. the
     * title-screen chat panel — can reach the relay directly with the same dev/live routing the
     * in-game Discord relay uses.
     */
    public static String relayBaseUrl() {
        // Dev/test override (self-hosting or a local mock relay) — mirrors the relay's own
        // DISCORD_API_BASE test seam. Unset in normal use → the branch-routed dev/live cap.
        String override = System.getenv("DUNGEONTRAIN_RELAY_BASE_URL");
        if (override != null && !override.isBlank()) {
            return override;
        }
        return discordRelayBaseUrl();
    }

    /**
     * Where the redesigned "manifest" public death report should post. On a RELEASE ({@code main}) build
     * it routes to the dedicated PUBLIC channel cap; on a dev/test build it returns {@code null} so the
     * report falls through to the build's default cap (the dev channel) — keeping the dev preview intact.
     * The threaded community-feed report is unaffected either way.
     */
    public static String manifestWebhookOverride() {
        return manifestWebhookOverrideForBranch(VersionInfo.BRANCH);
    }

    /**
     * Pure branch-&gt;manifest-destination mapping (package-private for unit testing). Only a {@code main}
     * build routes the public manifest report to the dedicated public cap; every other branch returns
     * {@code null} → the report posts to the build's default cap (the dev channel).
     */
    static String manifestWebhookOverrideForBranch(String branch) {
        return "main".equals(branch) ? RELAY_PUBLIC_BASE_URL + "/hook" : null;
    }

    /**
     * Where the survey-results copy should post. On a RELEASE ({@code main}) build it routes to the
     * dedicated survey-results channel cap; on a dev/test build it returns {@code null} so the copy
     * falls through to the build's default cap (the dev channel) — the dev preview. The threaded
     * answer (and its maintainer ping) is unaffected either way.
     */
    public static String surveyResultsWebhookOverride() {
        return surveyResultsWebhookOverrideForBranch(VersionInfo.BRANCH);
    }

    /**
     * Pure branch-&gt;survey-results-destination mapping (package-private for unit testing). Only a
     * {@code main} build routes the copy to the dedicated cap; every other branch returns {@code null}
     * → the copy posts to the build's default cap (the dev channel).
     */
    static String surveyResultsWebhookOverrideForBranch(String branch) {
        return "main".equals(branch) ? RELAY_SURVEY_RESULTS_BASE_URL + "/hook" : null;
    }

    /**
     * Guild id for the survey copy's jump-link on this build: the live community server on a
     * {@code main} build, the dev server otherwise — matching the channel the threaded original was
     * posted into. {@code ""} (the unset default) → the copy posts without a link.
     */
    public static String linkGuildIdForBranch(String branch) {
        return "main".equals(branch) ? LIVE_GUILD_ID : DEV_GUILD_ID;
    }

    /**
     * Pure branch-&gt;relay-URL mapping (package-private for unit testing). Only the literal {@code main}
     * branch reports to the live community feed; every other branch value (feature branches, worktrees,
     * detached-HEAD short SHA, the {@code "?"} fallback) reports to the dev channel — fail-safe toward
     * dev so a mis-detected branch can never post to the live feed.
     */
    static String relayBaseUrlForBranch(String branch) {
        return "main".equals(branch) ? RELAY_LIVE_BASE_URL : RELAY_DEV_BASE_URL;
    }
}
