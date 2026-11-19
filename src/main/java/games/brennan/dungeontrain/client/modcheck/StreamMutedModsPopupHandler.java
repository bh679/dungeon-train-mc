package games.brennan.dungeontrain.client.modcheck;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.ApprovedModList;
import games.brennan.dungeontrain.cheat.ModIds;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Opens {@link StreamMutedModsScreen} once per launch, from the title screen, when the player has
 * stream-muted music mods installed ({@link ApprovedModList#isStreamMuted}) they haven't asked not to
 * be told about again. Waits for {@link UnsupportedModsPopupHandler} to finish first and for the title
 * screen to be back up for {@link #SETTLE_TICKS}, so the two notices never stack.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class StreamMutedModsPopupHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** ~1 s on the title screen after the Unsupported Mods popup has had its turn. */
    static final int SETTLE_TICKS = 20;

    private static boolean done;
    private static int onTitle;

    private StreamMutedModsPopupHandler() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (done || !UnsupportedModsPopupHandler.isDone()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen title)) {
            onTitle = 0;
            return;
        }
        if (++onTitle < SETTLE_TICKS) return;
        done = true;
        List<StreamMutedModsScreen.MutedMod> mods = toTell();
        if (mods.isEmpty()) return;
        LOGGER.info("[DungeonTrain] Stream-muted mods notice: {}",
            mods.stream().map(StreamMutedModsScreen.MutedMod::modId).toList());
        mc.setScreen(new StreamMutedModsScreen(title, mods));
    }

    /** Installed, approved, stream-muted mods not yet marked "don't show again". By display name. */
    static List<StreamMutedModsScreen.MutedMod> toTell() {
        List<StreamMutedModsScreen.MutedMod> out = new ArrayList<>();
        try {
            Set<String> seen = LiveFeedClientConfig.streamMutedNoticeSeen();
            for (var info : ModList.get().getMods()) {
                String id = ModIds.normalise(info.getModId());
                if (!ApprovedModList.isStreamMuted(id) || !ApprovedModList.isApproved(id) || seen.contains(id)) continue;
                String name = info.getDisplayName();
                out.add(new StreamMutedModsScreen.MutedMod(id, name == null || name.isBlank() ? id : name));
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Stream-muted mods notice: could not read the mod list: {}", t.toString());
            return List.of();
        }
        out.sort(Comparator.comparing(m -> m.displayName().toLowerCase(java.util.Locale.ROOT)));
        return List.copyOf(out);
    }
}
