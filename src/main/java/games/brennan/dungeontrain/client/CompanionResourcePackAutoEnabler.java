package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.packs.repository.PackRepository;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * One-shot: the first time the DT modpack's companion Chinese resource pack
 * ({@code DungeonTrain-zh_cn-compat.zip}, shipped in the pack's
 * {@code overrides/resourcepacks/}) is present but not yet selected, enable it and save — so the
 * companion-mod (Jade, Tectonic, Distant Horizons, Controlling, ModernFix, CreativeCore, Sable,
 * DiscordPresence) zh_cn translations apply automatically alongside DT's own jar-bundled lang.
 *
 * <p>Deliberately a client-side hook rather than a shipped {@code options.txt}: a bundled
 * {@code options.txt} is copied wholesale by launchers and would reset the player's OTHER options
 * (keybinds, video, audio) on a pack update. This only ever touches the resource-pack selection —
 * a true merge — and only <b>once</b>: a marker file is written after the first attempt succeeds,
 * so a player who later disables the pack is never overridden. If the pack isn't installed (the
 * standalone mod, or dev), nothing happens and no marker is written, so a later install still gets
 * picked up.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CompanionResourcePackAutoEnabler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Substring match against a pack id ({@code file/<name>}) — resilient to the {@code .zip} id format. */
    private static final String PACK_ID_MATCH = "DungeonTrain-zh_cn-compat";
    private static final Path MARKER =
        FMLPaths.CONFIGDIR.get().resolve(DungeonTrain.MOD_ID).resolve("zh_cn_compat_autoenabled.marker");

    /** Guards against re-running every time the title screen re-inits within a session. */
    private static boolean checkedThisSession = false;

    private CompanionResourcePackAutoEnabler() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (checkedThisSession) return;
        if (!(event.getScreen() instanceof TitleScreen)) return;
        checkedThisSession = true;
        try {
            maybeEnable();
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Companion zh_cn pack auto-enable failed — leaving pack selection untouched", t);
        }
    }

    private static void maybeEnable() throws Exception {
        if (Files.exists(MARKER)) return; // already handled once — never fight the user afterwards

        Minecraft mc = Minecraft.getInstance();
        PackRepository repo = mc.getResourcePackRepository();
        repo.reload();

        Optional<String> packId = repo.getAvailableIds().stream()
            .filter(id -> id.contains(PACK_ID_MATCH))
            .findFirst();
        if (packId.isEmpty()) {
            return; // not installed (standalone mod / dev) — retry on a future launch, no marker yet
        }

        String id = packId.get();
        boolean changed = false;
        if (!mc.options.resourcePacks.contains(id)) {
            mc.options.resourcePacks.add(id);
            changed = true;
        }

        // Mark handled BEFORE reloading so we only ever auto-enable once, even across restarts.
        Files.createDirectories(MARKER.getParent());
        Files.writeString(MARKER, "Companion zh_cn resource pack auto-enabled once. Delete to re-arm.\n");

        if (changed) {
            mc.options.save();
            mc.reloadResourcePacks();
            LOGGER.info("[DungeonTrain] Auto-enabled companion zh_cn resource pack '{}'", id);
        }
    }
}
