package games.brennan.dungeontrain.client.modcheck;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.ApprovedModList;
import games.brennan.dungeontrain.cheat.ApprovedModListFetcher;
import games.brennan.dungeontrain.cheat.UnapprovedModIntegrity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Opens the {@link UnsupportedModsScreen} once per launch, from the title screen, when the player has
 * mods installed that are neither approved nor known cheat mods — and enforcement is on, so those
 * mods really do put the game in Free Play.
 *
 * <p><b>Timing.</b> Armed by the first title screen of the launch. It then waits for the approved-mod
 * fetch to settle (at most {@link #FETCH_WAIT_TICKS}), so a mod approved on the relay since the last
 * launch isn't listed, plus a short settle delay so the title menu is visible first. If another
 * screen is up at that point (a changelog, a backup prompt) it simply waits until the title screen is
 * back; it never stacks on top of something else.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class UnsupportedModsPopupHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** ~5 s for the relay fetch; after that the baked list plus the last cached relay list decide. */
    static final int FETCH_WAIT_TICKS = 100;
    /** ~1 s on the title screen before opening, so the menu has visibly loaded. */
    static final int SETTLE_TICKS = 20;

    private static boolean armed;
    private static boolean done;
    private static int waited;
    private static int onTitle;

    private UnsupportedModsPopupHandler() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (done || armed || !(event.getScreen() instanceof TitleScreen)) return;
        armed = true;
        ApprovedModListFetcher.ensureFetched();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!armed || done) return;
        Minecraft mc = Minecraft.getInstance();
        waited++;
        if (!(mc.screen instanceof TitleScreen title)) {
            onTitle = 0;
            return;
        }
        onTitle++;
        if (onTitle < SETTLE_TICKS) return;
        if (!ApprovedModListFetcher.isSettled() && waited < FETCH_WAIT_TICKS) return;

        armed = false;
        done = true;
        if (!ApprovedModList.enforce()) {
            LOGGER.info("[DungeonTrain] Unsupported Mods popup: whitelist enforcement is off — not shown");
            return;
        }
        List<UnsupportedModsScreen.UnsupportedMod> mods = unsupported();
        if (mods.isEmpty()) return;
        LOGGER.info("[DungeonTrain] Unsupported Mods popup: {} mod(s) — {}", mods.size(),
            mods.stream().map(UnsupportedModsScreen.UnsupportedMod::modId).toList());
        mc.setScreen(new UnsupportedModsScreen(title, mods));
    }

    /** Installed mods that are neither approved nor on the cheat blacklist, by display name. */
    private static List<UnsupportedModsScreen.UnsupportedMod> unsupported() {
        List<UnsupportedModsScreen.UnsupportedMod> out = new ArrayList<>();
        try {
            for (var info : ModList.get().getMods()) {
                if (!UnapprovedModIntegrity.isUnsupported(info.getModId(), info.getVersion().toString())) continue;
                String name = info.getDisplayName();
                out.add(new UnsupportedModsScreen.UnsupportedMod(info.getModId(),
                    name == null || name.isBlank() ? info.getModId() : name));
            }
        } catch (Throwable t) {
            // Same posture as the server-side scan: can't read the list ⇒ show nothing.
            LOGGER.warn("[DungeonTrain] Unsupported Mods popup: could not read the mod list: {}", t.toString());
            return List.of();
        }
        out.sort(Comparator.comparing(m -> m.displayName().toLowerCase(java.util.Locale.ROOT)));
        return List.copyOf(out);
    }
}
