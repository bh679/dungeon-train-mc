package games.brennan.dungeontrain.perks;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

/**
 * Whether perks are switched on. Perks are soft launched: off for everyone, on only in a perks test
 * world ({@link DungeonTrainWorldData#isPerksTest}), which approved testers start from the title
 * screen. Every perk surface asks here: perk effects ({@link Perks#state}), buying, the points chat
 * line, the perks menu and its buttons, the perks death screen, cosmic apples and tribute rooms.
 *
 * <p>Launch day flips {@link #LAUNCHED} to {@code true} and perks are on in every world.</p>
 *
 * <p>The server side mirrors the world flag into a static at overworld load (as
 * {@code KeepInventoryIntegrity} does), so the hot paths never touch SavedData. The client side
 * learns it with every {@link games.brennan.dungeontrain.net.PerkStatePacket}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PerksGate {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Perks are live for everyone. False until the launch. */
    public static final boolean LAUNCHED = false;

    /** Mirror of the loaded world's {@code perksTest} flag (server side). */
    private static volatile boolean testWorld = false;

    /** What the server last told this client (client side). */
    private static volatile boolean clientOn = false;

    private PerksGate() {}

    /** Server side: are perks on in the loaded world? */
    public static boolean isOn() {
        return LAUNCHED || testWorld;
    }

    /** Server side: is the loaded world a perks test world (and so Free Play)? */
    public static boolean isTestWorld() {
        return testWorld;
    }

    /** Are perks on for this player, on whichever side asks? Null reads off. */
    public static boolean isOn(Player player) {
        if (player == null) return false;
        if (player instanceof ServerPlayer) return isOn();
        return isOnClient();
    }

    /** Client side: did the server say perks are on in this world? */
    public static boolean isOnClient() {
        return LAUNCHED || clientOn;
    }

    /** Client side: the server's answer, from a {@code PerkStatePacket}. */
    public static void setClient(boolean on) {
        clientOn = on;
    }

    /** Client side: leaving the world. */
    public static void clearClient() {
        clientOn = false;
    }

    /** Called by {@code WorldLifecycleEvents} once it has made this new world a perks test world. */
    public static void markTestWorld(ServerLevel overworld) {
        DungeonTrainWorldData.get(overworld).markPerksTest();
        testWorld = true;
        LOGGER.info("[DungeonTrain] New perks test world: perks on, Free Play.");
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onOverworldLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel overworld)) return;
        if (!overworld.dimension().equals(Level.OVERWORLD)) return;
        // Never lowers it: WorldLifecycleEvents may have marked this new world first.
        if (DungeonTrainWorldData.get(overworld).isPerksTest()) testWorld = true;
        if (testWorld) LOGGER.info("[DungeonTrain] Perks test world: perks on, Free Play.");
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        testWorld = false;
    }
}
