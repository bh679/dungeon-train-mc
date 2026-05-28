package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.worldgen.PendingStartingDimension;
import games.brennan.dungeontrain.mixin.CreateWorldScreenAccessor;
import games.brennan.dungeontrain.world.StartingDimension;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Per-frame mirror of the World-Type cycle button into {@link PendingStartingDimension}.
 *
 * <p>{@code CreateWorldScreenMixin.dungeontrain$syncVisibilityAndPreset} publishes
 * the implied {@link StartingDimension} only at {@code init} TAIL. The NeoForge
 * 1.21.1 migration removed the per-tick injection that previously kept this in
 * sync as the user cycled the World Type button (see comment in that mixin),
 * which meant cycling onto "Dungeon Train (Nether)" or "Dungeon Train (End)"
 * after the screen opened was silently ignored — {@code WorldLifecycleEvents}
 * still committed OVERWORLD and the player spawned in the overworld.</p>
 *
 * <p>This handler restores the live sync by subscribing to
 * {@link ScreenEvent.Render.Pre} on {@link CreateWorldScreen} (the canonical
 * NeoForge per-frame screen hook, already used elsewhere in this mod). Each
 * frame it reads the currently-selected preset via the existing
 * {@link CreateWorldScreenAccessor} and republishes the matching dimension.
 * Cheap — the only work is a registry-key lookup and a volatile write.</p>
 *
 * <p>Defense-in-depth only: the init-TAIL publication inside the mixin remains
 * and still runs. This handler simply makes sure any later cycling overrides
 * that initial value before the user clicks Create World.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PendingStartingDimensionSyncHandler {

    private PendingStartingDimensionSyncHandler() {}

    @SubscribeEvent
    public static void onRenderPre(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof CreateWorldScreen screen)) return;
        WorldCreationUiState uiState = ((CreateWorldScreenAccessor) screen).dungeontrain$getUiState();
        if (uiState == null) return;
        Holder<WorldPreset> presetHolder = uiState.getWorldType().preset();
        ResourceKey<WorldPreset> curr = presetHolder.unwrapKey().orElse(null);
        boolean isDT = curr != null && DungeonTrain.MOD_ID.equals(curr.location().getNamespace());
        StartingDimension dim = isDT
            ? StartingDimension.fromPresetPath(curr.location().getPath())
            : StartingDimension.OVERWORLD;
        PendingStartingDimension.set(dim);
    }
}
