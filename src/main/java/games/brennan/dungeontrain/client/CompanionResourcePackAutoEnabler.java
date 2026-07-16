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
import java.util.List;

/**
 * One-shot: the first time the DT modpack's companion translation resource packs
 * ({@code DungeonTrain-<locale>-compat.zip}, e.g. {@code DungeonTrain-zh_cn-compat},
 * {@code DungeonTrain-es_es-compat}, …, shipped in the pack's {@code overrides/resourcepacks/})
 * are present but not yet selected, enable them and save — so the companion-mod (Jade, Tectonic,
 * Distant Horizons, Controlling, ModernFix, CreativeCore, Sable, DiscordPresence) translations
 * apply automatically alongside DT's own jar-bundled lang. Enabling <b>every</b> installed
 * companion pack is safe: Minecraft only applies the lang files matching the active client
 * language, so the non-matching locale packs contribute nothing at runtime.
 *
 * <p>Deliberately a client-side hook rather than a shipped {@code options.txt}: a bundled
 * {@code options.txt} is copied wholesale by launchers and would reset the player's OTHER options
 * (keybinds, video, audio) on a pack update. This only ever touches the resource-pack selection —
 * a true merge — and only <b>once</b>: a marker file is written after the first attempt succeeds,
 * so a player who later disables a pack is never overridden. If no companion pack is installed (the
 * standalone mod, or dev), nothing happens and no marker is written, so a later install still gets
 * picked up.</p>
 *
 * <p>The marker was historically {@code zh_cn_compat_autoenabled.marker} (single-language era). It
 * is now the locale-agnostic {@code companion_compat_autoenabled.marker}; the legacy marker is still
 * honored so an existing zh_cn install that made its one-shot decision is never fought again for
 * {@code zh_cn} (while still picking up any newly-shipped locale packs on the next launch).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CompanionResourcePackAutoEnabler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Both substrings must appear in a pack id ({@code file/<name>}) for it to be a DT companion pack. */
    private static final String PACK_ID_PREFIX = "DungeonTrain-";
    private static final String PACK_ID_SUFFIX = "-compat";

    private static final Path MARKER =
        FMLPaths.CONFIGDIR.get().resolve(DungeonTrain.MOD_ID).resolve("companion_compat_autoenabled.marker");
    /** Pre-multi-language marker; if present, the {@code zh_cn} pack keeps its prior one-shot decision. */
    private static final Path LEGACY_ZH_MARKER =
        FMLPaths.CONFIGDIR.get().resolve(DungeonTrain.MOD_ID).resolve("zh_cn_compat_autoenabled.marker");
    private static final String ZH_LOCALE_MATCH = "zh_cn";

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
            LOGGER.error("[DungeonTrain] Companion compat pack auto-enable failed — leaving pack selection untouched", t);
        }
    }

    private static void maybeEnable() throws Exception {
        if (Files.exists(MARKER)) return; // already handled once — never fight the user afterwards

        Minecraft mc = Minecraft.getInstance();
        PackRepository repo = mc.getResourcePackRepository();
        repo.reload();

        List<String> companionIds = repo.getAvailableIds().stream()
            .filter(id -> id.contains(PACK_ID_PREFIX) && id.contains(PACK_ID_SUFFIX))
            .toList();
        if (companionIds.isEmpty()) {
            return; // none installed (standalone mod / dev) — retry on a future launch, no marker yet
        }

        // A legacy zh_cn install already made its one-shot decision for that pack — respect it, but
        // still enable any newly-shipped locale packs.
        boolean legacyZhHandled = Files.exists(LEGACY_ZH_MARKER);

        boolean changed = false;
        for (String id : companionIds) {
            if (legacyZhHandled && id.contains(ZH_LOCALE_MATCH)) continue;
            if (!mc.options.resourcePacks.contains(id)) {
                mc.options.resourcePacks.add(id);
                changed = true;
                LOGGER.info("[DungeonTrain] Auto-enabled companion compat resource pack '{}'", id);
            }
        }

        // Mark handled BEFORE reloading so we only ever auto-enable once, even across restarts.
        Files.createDirectories(MARKER.getParent());
        Files.writeString(MARKER, "Companion compat resource packs auto-enabled once. Delete to re-arm.\n");

        if (changed) {
            mc.options.save();
            mc.reloadResourcePacks();
        }
    }
}
