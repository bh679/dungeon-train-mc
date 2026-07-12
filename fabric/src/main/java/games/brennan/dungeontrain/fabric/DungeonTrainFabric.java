package games.brennan.dungeontrain.fabric;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrainCommon;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.fabric.event.FabricServerEvents;
import games.brennan.dungeontrain.fabric.net.FabricPayloads;
import games.brennan.dungeontrain.logging.SableAabbLogFilter;
import games.brennan.dungeontrain.platform.DtPlatform;
import games.brennan.dungeontrain.registry.ModBlocks;
import games.brennan.dungeontrain.registry.ModCreativeTabs;
import games.brennan.dungeontrain.registry.ModItems;
import games.brennan.dungeontrain.registry.ModMobEffects;
import games.brennan.dungeontrain.registry.ModSounds;
import games.brennan.dungeontrain.worldgen.feature.ModFeatures;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;

/**
 * Fabric main entrypoint — the Fabric mirror of the NeoForge {@code DungeonTrain}
 * constructor. Runs on both physical sides; the client-only wiring lives in
 * {@link games.brennan.dungeontrain.fabric.client.DungeonTrainFabricClient}.
 *
 * <p>Sequence mirrors the NeoForge constructor: loader-neutral common init FIRST,
 * then the server registration spine ({@link FabricServerEvents}) that wires converted
 * handlers to {@code DtEvents} before any event bridge could fire, then the Fabric
 * event bridges (which subscribe the real Fabric events), then the vanilla-registry
 * registrations (immediate via {@link FabricRegistrar}), network payloads, and config.</p>
 *
 * <p><b>Sibling compat is skipped:</b> AIN / DiscordPresence / PlayerMob /
 * EnderChestPersistence ship no Fabric editions yet, so every sibling wiring the
 * NeoForge {@code commonSetup} does is guarded behind an {@code isModLoaded} check and
 * therefore inert here (their classes never classload). See the guarded block below.</p>
 */
public final class DungeonTrainFabric implements ModInitializer {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        // Loader-neutral common init FIRST — wires :common-resident handlers to DtEvents
        // before any bridge subscribes the real Fabric event.
        DungeonTrainCommon.init();

        // Server-side registration spine (mirrors NeoForgeServerEvents): populate the
        // DtEvents buckets, THEN subscribe the Fabric event bridges that fire them.
        FabricServerEvents.register();
        games.brennan.dungeontrain.fabric.event.FabricServerEventBridges.register();

        // Attachment types (fabric-data-attachment) — must register before any world loads.
        FabricModAttachments.init();

        // Vanilla-registry registrations. Each Mod*.init() forces its static field
        // initializers, whose DtRegistrar.register calls register IMMEDIATELY into the
        // (open, at mod-init) vanilla registries via FabricRegistrar — no attachAll needed.
        ModItems.init();
        ModBlocks.init();
        ModCreativeTabs.init();
        ModFeatures.init();
        ModMobEffects.init();
        ModSounds.init();
        ModAdvancementTriggers.init();

        // Network payloads: codecs (both directions) + C2S server receivers.
        FabricPayloads.registerTypesAndServerReceivers();

        // Config specs via Forge Config API Port (runtime JiJ'd by Sable-Fabric).
        FabricConfig.register();

        // Always-on Sable AABB log-storm filter (loader-neutral log4j filter).
        SableAabbLogFilter.install();

        // Sibling compat — inert on Fabric (no sibling Fabric ports). Each block only
        // runs if the sibling is present, so its classes never classload here.
        wireSiblingCompat();

        LOGGER.info("Dungeon Train (Fabric) initialised");
    }

    /**
     * The Fabric counterpart to the NeoForge {@code commonSetup} sibling wiring. Every
     * call is gated on {@code DtPlatform.isModLoaded(...)}; since no sibling ships a Fabric
     * edition yet, all gates are false and the sibling API classes (NamingConfig,
     * DiscordCredentials, the compat bridges) never classload. On NeoForge the same wiring
     * runs unconditionally because the siblings are always bundled.
     */
    private static void wireSiblingCompat() {
        DtPlatform platform = DtPlatform.get();
        if (platform.isModLoaded("adventureitemnames")) {
            SiblingCompat.registerNamingGate();
        }
        if (platform.isModLoaded("discordpresence")) {
            SiblingCompat.registerDiscord();
        }
        if (platform.isModLoaded("playermob")) {
            SiblingCompat.installPlayerMob();
        }
        if (platform.isModLoaded("enderchestpersistence")) {
            SiblingCompat.installEnderChest();
        }
    }
}
