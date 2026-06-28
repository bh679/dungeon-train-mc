package games.brennan.dungeontrain;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.discordpresence.config.DiscordCredentials;
import games.brennan.discordpresence.config.DiscordCredentialsProvider;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.advancement.SurveyAdvancement;
import games.brennan.dungeontrain.compat.DiscordAdvancementSuffix;
import games.brennan.dungeontrain.compat.EnderChestLockBridge;
import games.brennan.dungeontrain.compat.PlayerMobSocialBridge;
import games.brennan.dungeontrain.compat.DiscordInboundBridge;
import games.brennan.dungeontrain.compat.PlayerMobSpawnBridge;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.discord.WorldJoinReport;
import games.brennan.dungeontrain.registry.ModBlocks;
import games.brennan.dungeontrain.registry.ModCreativeTabs;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.registry.ModItems;
import games.brennan.dungeontrain.registry.ModMobEffects;
import games.brennan.dungeontrain.registry.ModSounds;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.train.TrainMembership;
import games.brennan.dungeontrain.worldgen.feature.ModFeatures;
import java.util.List;
import java.util.UUID;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;

/**
 * Dungeon Train — Minecraft port of the itch.io game.
 * Entry point for the NeoForge mod.
 */
@Mod(DungeonTrain.MOD_ID)
public class DungeonTrain {
    public static final String MOD_ID = "dungeontrain";
    private static final Logger LOGGER = LogUtils.getLogger();

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
        return !"main".equals(VersionInfo.BRANCH);
    }

    /** The relay capability this build reports through: the dev channel for dev builds, live on main. */
    private static String discordRelayBaseUrl() {
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
    static String linkGuildIdForBranch(String branch) {
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

    public DungeonTrain(IEventBus modBus, ModContainer modContainer) {
        modBus.addListener(this::commonSetup);

        // First DeferredRegister in the project — wires the variant
        // clipboard item produced by the block-variant menu's Copy button.
        ModItems.register(modBus);
        // narrative_lectern + its BlockItem — first block-registry in the
        // project. NarrativeLecternHooks (mod-bus) attaches it to vanilla
        // BlockEntityType.LECTERN's valid blocks.
        ModBlocks.register(modBus);

        ModCreativeTabs.register(modBus);
        ModFeatures.register(modBus);
        ModMobEffects.register(modBus);
        ModSounds.register(modBus);

        // Global achievements (advancements) — custom criterion triggers
        // + per-player run-state attachment.
        ModAdvancementTriggers.register(modBus);
        ModDataAttachments.register(modBus);

        modContainer.registerConfig(
                ModConfig.Type.SERVER,
                DungeonTrainConfig.SPEC,
                "dungeontrain-server.toml");

        // Per-client display preferences (HUD / world-space text scale).
        // Lives at <minecraft>/config/dungeontrain-client.toml so it follows
        // the player across worlds rather than per-save like the server config.
        modContainer.registerConfig(
                ModConfig.Type.CLIENT,
                ClientDisplayConfig.SPEC,
                "dungeontrain-client.toml");

        // Common gameplay defaults readable on the title screen (no world) and
        // on a dedicated server. Holds the global DEFAULT PlayerMob spawn rate;
        // per-world overrides live in DungeonTrainWorldData (SavedData).
        modContainer.registerConfig(
                ModConfig.Type.COMMON,
                DungeonTrainCommonConfig.SPEC,
                "dungeontrain-common.toml");

        // Invalidate the memoised WorldGenCycle whenever the COMMON config (re)loads, so the
        // worldgen band classifiers rebuild it from the new values. The base ModConfigEvent
        // listener catches both Loading (clears any pre-load default cycle) and Reloading
        // (config-screen / file-watcher edits); the spec guard ignores SERVER/CLIENT events.
        modBus.addListener((net.neoforged.fml.event.config.ModConfigEvent event) -> {
            if (event.getConfig().getSpec() == DungeonTrainCommonConfig.SPEC) {
                games.brennan.dungeontrain.worldgen.WorldGenCycle.invalidateCache();
            }
        });

        // No NeoForge.EVENT_BUS.register(this) — every game-bus listener in
        // this mod lives in its own @EventBusSubscriber class (event/*.java,
        // editor/*.java, etc.) which the loader auto-registers. NeoForge
        // 1.21.1 rejects register(this) on classes with no @SubscribeEvent
        // methods, so calling it here would crash mod construction.

        // Keeps the `games.brennan.dungeontrain.jitter` namespace at DEBUG
        // so the [baseline] capture line (spawn), [tripwire] WARN (large
        // physics-tick deltas — should never fire in normal play), and the
        // stuck-player diagnostics ([stuck.pIdx], [stuck.window],
        // [stuck.frozen], [panic.canonicalPos]) added by
        // plans/linear-marinating-yao.md stay visible without Forge-wide
        // DEBUG.
        //
        // The chatty per-tick probes ([physics], [pivotMoved], [pIdx],
        // [windowManager], [client]) log at TRACE — set
        // `-Dforge.logging.console.level=trace` or bump this line to
        // {@link Level#TRACE} to re-enable them when diagnosing a
        // regression of the train-hop fix.
        Configurator.setLevel("games.brennan.dungeontrain.jitter", Level.DEBUG);

        LOGGER.info("Dungeon Train constructor — mod loading");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Dungeon Train common setup");

        // Restrict Adventure Item Names' ambient mob naming (villagers + passive
        // animals) to entities on the train: off-train / vanilla-world mobs are
        // no longer auto-named while DT is installed. PlayerMob naming is left
        // untouched (it bypasses the gate inside AIN). AIN is always bundled via
        // jarJar, so NamingConfig is always present — no ModList.isLoaded guard
        // needed. The gate is a cheap tag-prefix scan (TrainMembership).
        NamingConfig.registerMobNameGate(TrainMembership::isOnTrain);

        // Point the bundled Discord Presence at Dungeon Train's central relay feed: every DT install
        // reports joins / deaths / advancements / chat to one community Discord via the relay at
        // brennan.games, carrying only this revocable relay URL — no Discord webhook/token ships in the
        // jar (DP relay-mode, v0.9.0+). PROVIDER_WINS, so this overrides any local discordpresence config.
        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; } // unused in relay-mode
            @Override public String relayBaseUrl() {
                return discordRelayBaseUrl();
            }
            @Override public boolean suppressAutoDeathReport() { return true; } // DT posts its own "Run Ended"
            @Override public boolean suppressAutoDisconnectReport() { return true; } // DT posts its own "left the game"
            // Chat-tag ping triggers: typing @dev or @brennanhatton in relayed chat is rewritten to a real
            // <@id> mention so Brennan is pinged in the community feed (DP's trusted allowed_mentions path).
            @Override public List<String> gameRelayMentions() {
                return MENTION_TOKENS.stream()
                        .map(token -> token + "=<@" + BRENNAN_DISCORD_ID + ">")
                        .toList();
            }
            // Track Brennan's Discord presence so the editor welcome (EditorWelcome) can render a
            // "last seen online …" / "online now" line. Unioned with the admin's presenceTrackUserIds
            // config. This requests the privileged GUILD_PRESENCES intent only in DP's direct-bot mode;
            // on DT's default relay-mode (no local gateway) it's a no-op until the relay serves presence,
            // and the query seam stays absent-safe meanwhile, so the welcome line simply omits.
            @Override public List<String> presenceTrackUserIds() {
                return List.of(BRENNAN_DISCORD_ID);
            }
            // The one-time title-screen "use the internet?" popup (DP's NetworkConsentScreen) lists what
            // the connection is for; DP renders these bullets verbatim on the card. Leaderboard scoring
            // isn't built yet, so it's flagged "(coming soon)"; the dev support chat is the @dev relay above;
            // the reincarnation bullet discloses that a death snapshot (name, skin, gear, traits) can be
            // relayed cross-world so the player may return as a PlayerMob "echo" in another player's world
            // (PlayerMob's external-reincarnation seam, surfaced via DP).
            @Override public List<String> networkConsentFeatures() {
                return List.of(
                        "Leaderboard scoring (coming soon)",
                        "Dev support chat",
                        "Reincarnate as a mob in other players' worlds");
            }
            // A "won't do" line (DP renders these with a red ✗ marker below the bullets above) — a
            // deliberately silly reassurance that sets the positive features apart from the absurd.
            @Override public List<String> networkConsentNonFeatures() {
                return List.of("Harvest your soul");
            }
            // Append a one-time world-info block (DT version + train regeneration data + installed-mods
            // list) under the first player-join message in each world, into the joining player's Discord
            // thread. WorldJoinReport gates it to once per world and is no-throw, so a hiccup can't disrupt
            // the join. Useful for reproducing/debugging a player's run (the train is seed-deterministic).
            @Override public String joinMessageSuffix(UUID playerId, String playerName) {
                return WorldJoinReport.suffixFor(playerId, playerName);
            }
            // Append a Dungeon-Train game-state line below each advancement announcement (its own line,
            // outside the embed): the carriage # the player earned it in + their difficulty level — the
            // same values the in-game HUD shows. Computed on the server thread via the compat helper.
            @Override public String advancementMessageSuffix(UUID playerId, String advancementId) {
                return DiscordAdvancementSuffix.forPlayer(playerId);
            }
            // Award "The Great Beyond" when a player finishes the death-screen feedback survey.
            // DP fires this on the server thread once the player has answered every outstanding
            // question; SurveyAdvancement resolves the player and grants it directly (no-throw).
            @Override public void onSurveyCompleted(UUID playerId, String playerName) {
                SurveyAdvancement.onSurveyCompleted(playerId);
            }
            // Ping Brennan in the community feed on EVERY submitted feedback-survey answer, so incoming
            // feedback never goes unseen. DP adds the id to the survey post's content + trusted
            // allowed_mentions.users (same notify path as the @dev chat triggers), so it actually
            // notifies. Same BRENNAN_DISCORD_ID used by gameRelayMentions / presenceTrackUserIds.
            @Override public List<String> surveyPingUserIds() {
                return List.of(BRENNAN_DISCORD_ID);
            }
            // Also drop a COPY of every survey answer into a dedicated, flat survey-results channel
            // (on top of the per-player thread), so feedback is browsable in one place. The copy is the
            // same embed plus a jump-link back to the threaded original. On a main build it routes to the
            // survey-results cap; on a dev build the override is null so the copy lands in the dev channel
            // (preview parity). The guild id (needed for the link) follows the same dev-vs-live split.
            @Override public boolean surveyResultsCopyEnabled() { return true; }
            @Override public String surveyResultsWebhookUrl() { return surveyResultsWebhookOverride(); }
            @Override public String surveyResultsLinkGuildId() { return linkGuildIdForBranch(VersionInfo.BRANCH); }
        });

        // One-line dev-vs-live routing signal at startup: states which Discord channel this build
        // reports to (the cap itself is non-secret). Complements DP 0.29.0's "routing" log.
        LOGGER.info("Dungeon Train Discord relay -> {} (build branch '{}')",
                isDevBuild() ? "DEV channel" : "LIVE community feed", VersionInfo.BRANCH);

        // Befriend advancements (A Silent Friend / Friends) observe PlayerMob
        // item gifts. PlayerMob's feeling-tiered gift rewrite removed the
        // giveItemTo method the old mixins hooked, so subscribe to its
        // PlayerMobSocialHooks seam instead (mob->player via tossGift,
        // player->mob via creditGift). Guarded: only when playermob is loaded,
        // and tolerant of a playermob build that predates the seam (e.g. the
        // bundled published version) — then the install no-ops and these
        // advancements simply won't fire, rather than crashing mod load.
        if (ModList.get().isLoaded("playermob")) {
            try {
                PlayerMobSocialBridge.install();
            } catch (Throwable t) {
                LOGGER.warn("PlayerMob present but social-gift seam unavailable ({}); "
                        + "befriend advancements disabled.", t.toString());
            }
            // Remote-echo encounter stories subscribe to PlayerMob's echo-spawn seam
            // (PlayerMobSpawnHooks, playermob 0.46.0+). Independent try so a build predating
            // the seam (e.g. an older bundled version) degrades to "no encounter stories"
            // rather than disabling the gift bridge above too.
            try {
                PlayerMobSpawnBridge.install();
            } catch (Throwable t) {
                LOGGER.warn("PlayerMob present but echo-spawn seam unavailable ({}); "
                        + "remote-echo encounter stories disabled.", t.toString());
            }
        }

        // Lock a Free Play (cheated) run's Ender Chest onto the creative slot via
        // EnderChestPersistence's slot-key provider seam, so cheated items can't
        // reach the player's legit chest. ECP is always bundled (jarJar), but
        // tolerate a build predating the seam (0.1.0): degrade to "no lock".
        if (ModList.get().isLoaded("enderchestpersistence")) {
            try {
                EnderChestLockBridge.install();
            } catch (Throwable t) {
                LOGGER.warn("EnderChestPersistence present but slot-lock seam unavailable ({}); "
                        + "Free Play Ender Chest lock disabled.", t.toString());
            }
        }

        // Feed relayed Discord messages from the dev into the remote-echo chat privacy guard via
        // DiscordPresence's inbound-message seam (InboundDiscordHooks, discordpresence 0.41.0+).
        // Tolerate a DP build predating the seam: degrade to the @-mention guard only.
        if (ModList.get().isLoaded("discordpresence")) {
            try {
                DiscordInboundBridge.install();
            } catch (Throwable t) {
                LOGGER.warn("DiscordPresence present but inbound-message seam unavailable ({}); "
                        + "echo-story dev-chat guard falls back to @-mention only.", t.toString());
            }
        }
    }
}
