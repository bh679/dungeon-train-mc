package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * One-shot: the first time <b>Sable: Ragdolls</b> is present, clear its "Ragdoll" key (default H),
 * which lets a player ragdoll themself. DT ships it unbound; a player who wants it can bind it in
 * Controls and it works as the mod intends. The bodies DT spawns on death
 * ({@code ship.sable.DeathBodyRagdoll}) do not use the key and are unaffected.
 *
 * <p>Found by mapping name, not by class, through {@link OneShotKeybindUnbind}, which writes a
 * marker so a later rebind is never overridden.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class RagdollKeybindDefault {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The mapping name Sable: Ragdolls registers in {@code RagdollKeybinds}. */
    static final String RAGDOLL_KEY_NAME = "key.sable_player_ragdoll.ragdoll";

    private static final Path MARKER =
        FMLPaths.CONFIGDIR.get().resolve(DungeonTrain.MOD_ID).resolve("ragdoll_keybind_defaulted.marker");

    /** Guards against re-running every time the title screen re-inits within a session. */
    private static boolean checkedThisSession = false;

    private RagdollKeybindDefault() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (checkedThisSession) return;
        if (!(event.getScreen() instanceof TitleScreen)) return;
        checkedThisSession = true;
        try {
            OneShotKeybindUnbind.unbindOnce(OneShotKeybindUnbind.exactly(RAGDOLL_KEY_NAME), MARKER, "Sable: Ragdolls");
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Ragdoll keybind default failed, leaving keybinds untouched", t);
        }
    }
}
