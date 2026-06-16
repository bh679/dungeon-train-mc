package games.brennan.dungeontrain;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.discordpresence.config.DiscordCredentials;
import games.brennan.discordpresence.config.DiscordCredentialsProvider;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.compat.DiscordAdvancementSuffix;
import games.brennan.dungeontrain.compat.PlayerMobSocialBridge;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.registry.ModBlocks;
import games.brennan.dungeontrain.registry.ModCreativeTabs;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.registry.ModItems;
import games.brennan.dungeontrain.registry.ModMobEffects;
import games.brennan.dungeontrain.registry.ModSounds;
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
                return "https://brennan.games/api/dp-relay/adc3dc432f437e9401092c143dec86767dd06c2a5d94f48f";
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
            // Append a Dungeon-Train game-state line below each advancement announcement (its own line,
            // outside the embed): the carriage # the player earned it in + their difficulty level — the
            // same values the in-game HUD shows. Computed on the server thread via the compat helper.
            @Override public String advancementMessageSuffix(UUID playerId, String advancementId) {
                return DiscordAdvancementSuffix.forPlayer(playerId);
            }
        });

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
        }
    }
}
