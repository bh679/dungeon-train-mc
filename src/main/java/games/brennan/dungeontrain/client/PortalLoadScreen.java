package games.brennan.dungeontrain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.LevelLoadStatusManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The vanilla Nether-portal "Loading terrain" screen for a same-dimension portal trip
 * ({@code net/PortalLoadScreenPacket}). Mirrors {@code ClientPacketListener.startWaitingForNewLevel}: a
 * {@link LevelLoadStatusManager} decides readiness (the section under the player compiled, or the
 * player outside build height / spectator / dead), and {@link ReceivingLevelScreen} polls it.
 *
 * <p>The packet arrives a moment <em>before</em> the teleport, when the chunk under the player's old
 * position is of course already rendered — so readiness is only consulted once the player has actually
 * moved far from where they stood ({@link #MOVED_DISTANCE}); if no teleport follows within
 * {@link #ARM_TIMEOUT_TICKS} the screen simply closes. The screen also has vanilla's own 30 s cap.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PortalLoadScreen {

    /** Blocks the player must have moved before the screen starts waiting on terrain. */
    private static final double MOVED_DISTANCE = 64.0;

    /** Client ticks to wait for the teleport before giving up on the screen. */
    private static final int ARM_TIMEOUT_TICKS = 60;

    private PortalLoadScreen() {}

    /** Put up the loading screen now; it closes once the terrain at the destination has rendered. */
    public static void show() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        LevelLoadStatusManager status = new LevelLoadStatusManager(player, mc.level, mc.levelRenderer);
        status.loadingPacketsReceived();
        Vec3 origin = player.position();
        int[] ticks = {0};

        mc.setScreen(new ReceivingLevelScreen(() -> {
            LocalPlayer now = mc.player;
            if (now == null || mc.level == null) return true;
            ticks[0]++;
            if (now.position().distanceTo(origin) < MOVED_DISTANCE) return ticks[0] > ARM_TIMEOUT_TICKS;
            status.tick();
            return status.levelReady();
        }, ReceivingLevelScreen.Reason.NETHER_PORTAL));
    }
}
