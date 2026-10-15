package games.brennan.dungeontrain.client.promote;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.AdvancementPromotion;
import games.brennan.dungeontrain.net.relay.AdvancementPromoteClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Opens {@link AdvancementPromoteScreen} from the title screen when the developer has sent this player
 * a one-time advancement promote on the relay.
 *
 * <p><b>Timing.</b> Asks the relay each time the title screen comes up — at launch, and on every
 * return from a world, so a promote sent mid-session is offered at the next title — at most once per
 * {@link #RECHECK_MS}. A promote that arrives opens once the title screen has settled, and never on
 * top of another screen (it waits, like {@code UnsupportedModsPopupHandler}). One the player put off
 * with Not Now isn't offered again until the next launch.</p>
 *
 * <p>Only with network consent, the same gate as the rest of the relay traffic.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class AdvancementPromotePopupHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final long RECHECK_MS = 5 * 60 * 1000L;
    /** ~1.5 s on the title screen before opening, so the menu (and any launch popup) shows first. */
    static final int SETTLE_TICKS = 30;

    private static long lastFetchMs;
    /** Set from the HTTP thread, consumed on the render thread. */
    private static volatile AdvancementPromoteClient.Promote arrived;
    /** Promote ids already offered this launch — Not Now means "not this launch". */
    private static final Set<String> offered = new HashSet<>();
    private static int onTitle;

    private AdvancementPromotePopupHandler() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) return;
        long now = System.currentTimeMillis();
        if (now - lastFetchMs < RECHECK_MS || !consented()) return;
        UUID player = Minecraft.getInstance().getUser().getProfileId();
        if (player == null) return;
        lastFetchMs = now;
        AdvancementPromoteClient.fetch(player, promote -> arrived = promote);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        AdvancementPromoteClient.Promote promote = arrived;
        if (promote == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen title)) {
            onTitle = 0;
            return;
        }
        if (++onTitle < SETTLE_TICKS) return;
        arrived = null;
        if (!offered.add(promote.id())) return;

        Path dir = AdvancementPromotion.liveDir();
        if (AdvancementPromotion.isUsed(dir, promote.id())) {
            // Answered here before but the ack never landed — RelayOutbox is still retrying it.
            LOGGER.info("[DungeonTrain] Advancement promote {}: already answered on this install", promote.id());
            return;
        }
        UUID player = mc.getUser().getProfileId();
        List<ResourceLocation> candidates = AdvancementPromotion.candidates(dir, player, promote.target());
        if (candidates.isEmpty()) {
            // Left pending on the relay: the developer sees it was never answered.
            LOGGER.info("[DungeonTrain] Advancement promote {}: nothing to promote to {}", promote.id(), promote.target());
            return;
        }
        LOGGER.info("[DungeonTrain] Advancement promote {}: offering {} advancement(s) for {} ({})",
            promote.id(), candidates.size(), promote.target(), promote.mode());
        mc.setScreen(new AdvancementPromoteScreen(title, player, promote, candidates));
    }

    private static boolean consented() {
        try {
            return DiscordPresenceClientConfig.isGranted();
        } catch (Throwable t) {
            return false;
        }
    }
}
