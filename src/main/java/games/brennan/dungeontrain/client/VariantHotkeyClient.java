package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.InputConstants;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.VariantHotkeyPacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side keymap + tick watcher for the "variant place" key. Defaults to
 * {@code Z}; rebindable from the vanilla Controls menu under category
 * {@code Dungeon Train}. Each tick, we compare the current
 * {@link KeyMapping#isDown()} state to the previous tick's value and send a
 * {@link VariantHotkeyPacket} on transition. Server-side state lives in
 * {@code VariantHotkeyState} and is consulted by
 * {@code VariantBlockInteractions} to gate variant authoring.
 *
 * <p>Why not a one-shot consume on key press: the server-side handler reads
 * the held flag at right-click time, which can land a frame or two after the
 * press. A press-only signal would race the right-click event; the held-state
 * model is more forgiving and matches the original sneak-and-right-click UX.</p>
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class VariantHotkeyClient {

    public static final String CATEGORY = "key.categories." + DungeonTrain.MOD_ID;
    public static final String NAME = "key." + DungeonTrain.MOD_ID + ".variant_place";

    private static final KeyMapping KEY = new KeyMapping(
        NAME,
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_Z,
        CATEGORY
    );

    private static boolean lastSentHeld = false;

    private VariantHotkeyClient() {}

    @SubscribeEvent
    public static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(KEY);
    }

    /**
     * Forge bus listener — subscribed via the FORGE bus separately so it ticks
     * during the client game loop (not just during mod init). Sends a packet
     * to the server whenever the key state changes.
     */
    @Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class TickWatcher {
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            // Avoid sending packets before we're connected to a server (main
            // menu, mod init, world unload) — getConnection() is null offline.
            if (Minecraft.getInstance().getConnection() == null) {
                lastSentHeld = false;
                return;
            }
            boolean held = KEY.isDown();
            if (held == lastSentHeld) return;
            DungeonTrainNet.sendToServer(new VariantHotkeyPacket(held));
            lastSentHeld = held;
        }
    }
}
