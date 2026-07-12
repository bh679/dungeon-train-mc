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
import games.brennan.dungeontrain.discord.WorldInfoReporter;
import games.brennan.dungeontrain.discord.WorldJoinReport;
import games.brennan.dungeontrain.logging.SableAabbLogFilter;
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
@Mod(DtCore.MOD_ID)
public class DungeonTrain {
    /**
     * Re-exported so the many {@code @EventBusSubscriber(modid = DungeonTrain.MOD_ID)} and
     * registry callsites that predate the {@link DtCore} split keep compiling; the single
     * source of truth is {@link DtCore#MOD_ID}. New code should reference {@code DtCore.MOD_ID}.
     */
    public static final String MOD_ID = DtCore.MOD_ID;
    private static final Logger LOGGER = LogUtils.getLogger();

    public DungeonTrain(IEventBus modBus, ModContainer modContainer) {
        // Loader-neutral init FIRST — wires converted game logic to DtEvents before any
        // NeoForge*Bridge (auto-registered @EventBusSubscriber) could fire an event. init()
        // is the common seam; NeoForgeServerEvents.register() wires the converted handlers
        // that still live in the root module this Stage (see NeoForgeServerEvents Javadoc).
        DungeonTrainCommon.init();
        games.brennan.dungeontrain.platform.neoforge.NeoForgeServerEvents.register();
        // Client-side converted handlers — gated to the physical client so
        // NeoForgeClientEvents (and every client handler class it method-references)
        // never classloads on a dedicated server. The matching NeoForge*Bridge classes
        // are @EventBusSubscriber(value = Dist.CLIENT) for the same reason.
        if (games.brennan.dungeontrain.platform.DtPlatform.get().isClient()) {
            // Client-side common registrations (migrated, :common-resident handlers)
            // run first, then the root-resident client handlers.
            DungeonTrainCommon.initClient();
            games.brennan.dungeontrain.platform.neoforge.NeoForgeClientEvents.register();
        }

        modBus.addListener(this::commonSetup);

        // First DeferredRegister in the project — wires the variant
        // clipboard item produced by the block-variant menu's Copy button.
        // ModItems / ModBlocks / ModCreativeTabs / ModFeatures / ModSounds / ModAdvancementTriggers
        // now register through the loader-neutral DtRegistrar seam (each entry
        // is created eagerly by the static field initializers .init() forces to
        // run); the underlying DeferredRegisters are attached to modBus in one
        // shot via NeoForgeRegistrar.attachAll(modBus) below — same code
        // position, same timing as the old per-class .register(modBus) calls.
        ModItems.init();
        // narrative_lectern + its BlockItem — first block-registry in the
        // project. NarrativeLecternHooks (mod-bus) attaches it to vanilla
        // BlockEntityType.LECTERN's valid blocks.
        ModBlocks.init();

        ModCreativeTabs.init();
        ModFeatures.init();
        // ModMobEffects stays on DeferredRegister directly: its callers
        // (WarmthOfTheFireTooltip, FreePlayTooltip, CheatDetectionEvents) need
        // DeferredHolder#getId(), which a plain Supplier does not expose.
        ModMobEffects.register(modBus);
        ModSounds.init();

        // Global achievements (advancements) — custom criterion triggers
        // + per-player run-state attachment.
        ModAdvancementTriggers.init();
        games.brennan.dungeontrain.platform.neoforge.NeoForgeRegistrar.attachAll(modBus);
        // ModDataAttachments stays on DeferredRegister/AttachmentType directly:
        // AttachmentType registration is NeoForge-specific API with no vanilla
        // registry key, so it cannot route through DtRegistrar.
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

        // Suppress ONE spammy Sable log line — the per-call stack-trace-capturing "Aborting entity
        // get for abnormally large AABB" ERROR — without touching Sable's log level. It fires on the
        // render thread ~15×/sec when a Vivecraft (VR) player stands on a sub-level (train carriage),
        // hitching frames. Root-caused for Vivecraft by SwingTrackerSubLevelAabbMixin; this is the
        // always-on belt so the storm can't resurface from any other trigger. See SableAabbLogFilter.
        SableAabbLogFilter.install();

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
                return DtCore.discordRelayBaseUrl();
            }
            @Override public boolean suppressAutoDeathReport() { return true; } // DT posts its own "Run Ended"
            @Override public boolean suppressAutoDisconnectReport() { return true; } // DT posts its own "left the game"
            // Chat-tag ping triggers: typing @dev or @brennanhatton in relayed chat is rewritten to a real
            // <@id> mention so Brennan is pinged in the community feed (DP's trusted allowed_mentions path).
            @Override public List<String> gameRelayMentions() {
                return DtCore.MENTION_TOKENS.stream()
                        .map(token -> token + "=<@" + DtCore.BRENNAN_DISCORD_ID + ">")
                        .toList();
            }
            // Track Brennan's Discord presence so the editor welcome (EditorWelcome) can render a
            // "last seen online …" / "online now" line. Unioned with the admin's presenceTrackUserIds
            // config. This requests the privileged GUILD_PRESENCES intent only in DP's direct-bot mode;
            // on DT's default relay-mode (no local gateway) it's a no-op until the relay serves presence,
            // and the query seam stays absent-safe meanwhile, so the welcome line simply omits.
            @Override public List<String> presenceTrackUserIds() {
                return List.of(DtCore.BRENNAN_DISCORD_ID);
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
                        "Share books you write for others to find",
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
                // Fire the structured world-info telemetry POST (populates the data explorer's Mods/Seeds
                // cards) as a no-throw side effect on every join, then return the once-per-world Discord
                // suffix. WorldInfoReporter reports per join (no one-shot); the relay dedupes identical records.
                WorldInfoReporter.report(playerId, playerName);
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
            // No ping on submitted feedback-survey answers — the dedicated survey-results channel
            // copy (surveyResultsCopyEnabled below) already surfaces every answer for browsing,
            // so an @-mention per answer is redundant noise. Matches DP's un-overridden default.
            @Override public List<String> surveyPingUserIds() {
                return List.of();
            }
            // Also drop a COPY of every survey answer into a dedicated, flat survey-results channel
            // (on top of the per-player thread), so feedback is browsable in one place. The copy is the
            // same embed plus a jump-link back to the threaded original. On a main build it routes to the
            // survey-results cap; on a dev build the override is null so the copy lands in the dev channel
            // (preview parity). The guild id (needed for the link) follows the same dev-vs-live split.
            @Override public boolean surveyResultsCopyEnabled() { return true; }
            @Override public String surveyResultsWebhookUrl() { return DtCore.surveyResultsWebhookOverride(); }
            @Override public String surveyResultsLinkGuildId() { return DtCore.linkGuildIdForBranch(VersionInfo.BRANCH); }
        });

        // One-line dev-vs-live routing signal at startup: states which Discord channel this build
        // reports to (the cap itself is non-secret). Complements DP 0.29.0's "routing" log.
        LOGGER.info("Dungeon Train Discord relay -> {} (build branch '{}')",
                DtCore.isDevBuild() ? "DEV channel" : "LIVE community feed", VersionInfo.BRANCH);

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

        // Feed relayed Discord messages from the dev into the remote-echo chat privacy guard and the
        // in-game dev-message consent flow via DiscordPresence's inbound-message seams
        // (InboundDiscordHooks 0.41.0+, DiscordCommandHooks 0.43.0+ — see DiscordInboundBridge).
        // Tolerate a DP build predating either seam: degrade to whichever guard is still available.
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
