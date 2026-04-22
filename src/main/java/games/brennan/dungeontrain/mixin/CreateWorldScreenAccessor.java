package games.brennan.dungeontrain.mixin;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the package-private {@code uiState} field of {@link CreateWorldScreen} so the
 * {@code CreateWorldScreen$WorldTab} mixin — which lives outside the vanilla package —
 * can read and mutate the current world-creation state from its click handlers.
 */
@Mixin(CreateWorldScreen.class)
public interface CreateWorldScreenAccessor {

    @Accessor("uiState")
    WorldCreationUiState dungeontrain$getUiState();
}
