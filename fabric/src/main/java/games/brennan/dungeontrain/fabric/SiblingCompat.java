package games.brennan.dungeontrain.fabric;

import com.mojang.logging.LogUtils;
import games.brennan.adventureitemnames.api.NamingConfig;
import games.brennan.discordpresence.config.DiscordCredentials;
import games.brennan.discordpresence.config.DiscordCredentialsProvider;
import games.brennan.dungeontrain.DtCore;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.compat.DiscordAdvancementSuffix;
import games.brennan.dungeontrain.compat.DiscordInboundBridge;
import games.brennan.dungeontrain.compat.EnderChestLockBridge;
import games.brennan.dungeontrain.compat.PlayerMobSocialBridge;
import games.brennan.dungeontrain.compat.PlayerMobSpawnBridge;
import games.brennan.dungeontrain.advancement.SurveyAdvancement;
import games.brennan.dungeontrain.discord.WorldInfoReporter;
import games.brennan.dungeontrain.discord.WorldJoinReport;
import games.brennan.dungeontrain.train.TrainMembership;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;

/**
 * Fabric sibling-mod wiring — the faithful copy of the NeoForge {@code DungeonTrain}
 * {@code commonSetup} sibling blocks. <b>Never referenced on Fabric v1</b>: every method
 * is only invoked from a {@code DtPlatform.isModLoaded(...)}-gated branch in
 * {@link DungeonTrainFabric}, and no sibling ships a Fabric edition yet, so this class
 * (and the sibling API types it references) never classloads at runtime. It exists so the
 * wiring is ready the moment a sibling Fabric port lands (Stage 5+).
 */
final class SiblingCompat {

    private static final Logger LOGGER = LogUtils.getLogger();

    private SiblingCompat() {}

    /** Restrict AIN ambient mob naming to on-train entities (mirrors commonSetup). */
    static void registerNamingGate() {
        NamingConfig.registerMobNameGate(TrainMembership::isOnTrain);
    }

    /** Point DiscordPresence at DT's relay feed (verbatim copy of commonSetup's provider). */
    static void registerDiscord() {
        DiscordCredentials.register(new DiscordCredentialsProvider() {
            @Override public String webhookUrl() { return ""; }
            @Override public String relayBaseUrl() { return DtCore.discordRelayBaseUrl(); }
            @Override public boolean suppressAutoDeathReport() { return true; }
            @Override public boolean suppressAutoDisconnectReport() { return true; }
            @Override public List<String> gameRelayMentions() {
                return DtCore.MENTION_TOKENS.stream()
                        .map(token -> token + "=<@" + DtCore.BRENNAN_DISCORD_ID + ">")
                        .toList();
            }
            @Override public List<String> presenceTrackUserIds() {
                return List.of(DtCore.BRENNAN_DISCORD_ID);
            }
            @Override public List<String> networkConsentFeatures() {
                return List.of(
                        "Leaderboard scoring (coming soon)",
                        "Dev support chat",
                        "Share books you write for others to find",
                        "Reincarnate as a mob in other players' worlds");
            }
            @Override public List<String> networkConsentNonFeatures() {
                return List.of("Harvest your soul");
            }
            @Override public String joinMessageSuffix(UUID playerId, String playerName) {
                WorldInfoReporter.report(playerId, playerName);
                return WorldJoinReport.suffixFor(playerId, playerName);
            }
            @Override public String advancementMessageSuffix(UUID playerId, String advancementId) {
                return DiscordAdvancementSuffix.forPlayer(playerId);
            }
            @Override public void onSurveyCompleted(UUID playerId, String playerName) {
                SurveyAdvancement.onSurveyCompleted(playerId);
            }
            @Override public List<String> surveyPingUserIds() { return List.of(); }
            @Override public boolean surveyResultsCopyEnabled() { return true; }
            @Override public String surveyResultsWebhookUrl() { return DtCore.surveyResultsWebhookOverride(); }
            @Override public String surveyResultsLinkGuildId() { return DtCore.linkGuildIdForBranch(VersionInfo.BRANCH); }
        });

        // Inbound Discord message seam (echo-chat privacy + dev-message consent).
        try {
            DiscordInboundBridge.install();
        } catch (Throwable t) {
            LOGGER.warn("DiscordPresence present but inbound-message seam unavailable ({}).", t.toString());
        }
    }

    /** Befriend advancements + remote-echo encounter stories (mirrors commonSetup). */
    static void installPlayerMob() {
        try {
            PlayerMobSocialBridge.install();
        } catch (Throwable t) {
            LOGGER.warn("PlayerMob present but social-gift seam unavailable ({}).", t.toString());
        }
        try {
            PlayerMobSpawnBridge.install();
        } catch (Throwable t) {
            LOGGER.warn("PlayerMob present but echo-spawn seam unavailable ({}).", t.toString());
        }
    }

    /** Lock a Free Play run's Ender Chest onto the creative slot (mirrors commonSetup). */
    static void installEnderChest() {
        try {
            EnderChestLockBridge.install();
        } catch (Throwable t) {
            LOGGER.warn("EnderChestPersistence present but slot-lock seam unavailable ({}).", t.toString());
        }
    }
}
