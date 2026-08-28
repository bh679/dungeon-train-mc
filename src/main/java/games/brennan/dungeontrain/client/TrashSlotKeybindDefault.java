package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One-shot: the first time the modpack's bundled <b>TrashSlot</b> is present, clear its
 * "Show/Hide TrashSlot" keybinding so the pack ships with no key summoning a trash slot. Paired
 * with {@code overrides/TrashSlotSaveState.default.json}, which seeds the slot itself hidden on the
 * two screens TrashSlot registers as enabled-by-default, the mod is installed and loaded but
 * completely silent until a player asks for it.
 *
 * <p>Deliberately a client-side hook rather than a shipped {@code options.txt}, for the same reason
 * {@link CompanionResourcePackAutoEnabler} is: a bundled {@code options.txt} is copied wholesale by
 * launchers and would reset the player's OTHER options (keybinds, video, audio) on every pack
 * update. That route is rejected by {@code scripts/modpack/check-overrides.py} and documented in
 * {@code modpack/README.md}. This touches exactly one mapping — a true merge — and only
 * <b>once</b>: a marker file is written after the first attempt, so a player who later binds their
 * own key is never overridden.</p>
 *
 * <p>TrashSlot is found by keybinding <i>name</i>, never by class: DT has no compile-time or
 * {@code neoforge.mods.toml} dependency on it, and behaves identically when it is absent. Balm's
 * Kuma layer builds the vanilla {@link KeyMapping} name as {@code String.format("key.%s.%s",
 * namespace, path)}, giving {@value #TRASHSLOT_TOGGLE_KEY}. If no such mapping exists (standalone
 * mod, dev, or a player who removed TrashSlot) nothing happens and <b>no marker is written</b>, so
 * a later install is still defaulted on its first launch.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TrashSlotKeybindDefault {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Vanilla {@link KeyMapping} name Kuma generates for TrashSlot's show/hide toggle. */
    private static final String TRASHSLOT_TOGGLE_KEY = "key.trashslot.toggle";

    private static final Path MARKER =
        FMLPaths.CONFIGDIR.get().resolve(DungeonTrain.MOD_ID).resolve("trashslot_keybind_defaulted.marker");

    /** Guards against re-running every time the title screen re-inits within a session. */
    private static boolean checkedThisSession = false;

    private TrashSlotKeybindDefault() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (checkedThisSession) return;
        if (!(event.getScreen() instanceof TitleScreen)) return;
        checkedThisSession = true;
        try {
            maybeUnbind();
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] TrashSlot keybind default failed — leaving keybinds untouched", t);
        }
    }

    private static void maybeUnbind() throws Exception {
        if (Files.exists(MARKER)) return; // already handled once — never fight the user afterwards

        Minecraft mc = Minecraft.getInstance();
        KeyMapping toggle = null;
        for (KeyMapping mapping : mc.options.keyMappings) {
            if (TRASHSLOT_TOGGLE_KEY.equals(mapping.getName())) {
                toggle = mapping;
                break;
            }
        }
        if (toggle == null) {
            return; // TrashSlot not installed (standalone mod / dev) — retry on a future launch, no marker yet
        }

        boolean changed = !toggle.isUnbound();
        if (changed) {
            toggle.setKey(InputConstants.UNKNOWN);
            KeyMapping.resetMapping();
            LOGGER.info("[DungeonTrain] Cleared the TrashSlot toggle keybinding — bind one in Options > Controls to use it");
        }

        // Mark handled BEFORE saving so we only ever default this once, even across restarts.
        Files.createDirectories(MARKER.getParent());
        Files.writeString(MARKER, "TrashSlot toggle keybinding defaulted to unbound once. Delete to re-arm.\n");

        if (changed) {
            mc.options.save();
        }
    }
}
