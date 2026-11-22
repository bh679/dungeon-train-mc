package games.brennan.dungeontrain.client.sound;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.perks.PerksScreen;
import games.brennan.dungeontrain.client.tributeroom.TributeTvVisit;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The world goes quiet while the perks menu opened on a death is on the window: no mobs, blocks,
 * weather or wind behind it, while its own UI sounds and the music play on. It covers the menu and
 * the wait while the tribute room is stood up behind it ({@link TributeTvVisit#menuOnWindow()}), and
 * fades back once the menu dissolves into the room's TV, so the room has its sound. The perks menu
 * opened in play is untouched.
 *
 * <p>The volume itself is scaled in {@code SoundEngineDeathHushMixin}; this ticks the fade and, while
 * it moves, re-applies a category volume so channels already playing pick up the new gain (the same
 * re-apply {@code VoidSkyEvents} does for music).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DeathMenuHush {

    private static volatile float factor = 1.0F;

    private DeathMenuHush() {}

    /** The current hush factor: 1 when nothing is hushed, down to {@link DeathMenuHushFade#FLOOR}. */
    public static float factor() {
        return factor;
    }

    static boolean hushed(Minecraft mc) {
        if (mc.player == null) return false;
        if (mc.screen instanceof PerksScreen perks && perks.isDeathMenu()) return true;
        return TributeTvVisit.menuOnWindow();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        float before = factor;
        float after = DeathMenuHushFade.step(before, hushed(mc));
        if (after == before) return;
        factor = after;
        // Any non-master category re-applies every playing channel's gain, whatever its own category.
        SoundSource any = SoundSource.AMBIENT;
        mc.getSoundManager().updateSourceVolume(any, mc.options.getSoundSourceVolume(any));
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        factor = 1.0F;
    }
}
