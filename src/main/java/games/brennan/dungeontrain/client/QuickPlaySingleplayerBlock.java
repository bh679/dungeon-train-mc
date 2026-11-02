package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * Turns a launcher's "play this world" button into a trip to the title screen. Dungeon Train is a
 * roguelite: a singleplayer world is one run, so a launcher reopening an old one straight from its
 * Worlds list ({@code --quickPlaySingleplayer}: Modrinth's Worlds tab and Home page, CurseForge,
 * Prism, the vanilla launcher) is a way back into a finished run. It also skips the title screen,
 * which is where the network-consent card and the crash-recovery offer live.
 *
 * <p>{@code QuickPlaySingleplayerMixin} asks {@link #shouldBlock()} at the head of vanilla's
 * {@code QuickPlay.joinSingleplayerWorld}; on a block it opens the title screen instead and
 * {@link #onBlocked} leaves a toast to show once the title screen is the open screen (after the
 * consent card, if that is up first). Server and Realms Quick Play are untouched.</p>
 *
 * <p>Dev builds keep Quick Play: the headless harnesses launch with {@code -PquickWorld}. Set
 * {@code -Ddungeontrain.quickPlayBlock=force} to exercise the block on a dev build.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class QuickPlaySingleplayerBlock {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final boolean FORCE = "force".equalsIgnoreCase(System.getProperty("dungeontrain.quickPlayBlock"));

    /** A blocked join is waiting for its toast. */
    private static boolean noticePending;

    private QuickPlaySingleplayerBlock() {}

    /** True when a Quick Play singleplayer join must not open its world. */
    public static boolean shouldBlock() {
        return FORCE || !DungeonTrain.isDevBuild();
    }

    /** Record a blocked join: log it and queue the title-screen toast. */
    public static void onBlocked(String worldName) {
        LOGGER.info("[DungeonTrain] Quick Play into world '{}' blocked: every world is one run.", worldName);
        noticePending = true;
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        if (!noticePending) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
        noticePending = false;
        SystemToast.add(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable("toast.dungeontrain.quick_play_blocked.title"),
                Component.translatable("toast.dungeontrain.quick_play_blocked.body"));
    }
}
