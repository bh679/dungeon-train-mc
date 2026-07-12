package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.DungeonTrainOptionsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/**
 * Adds a "Dungeon Train Options…" button as a new row in the World tab of
 * Minecraft's {@link CreateWorldScreen}, sitting under the Bonus Chest toggle.
 *
 * Targets the package-private {@code CreateWorldScreen$WorldTab} inner class
 * via {@code targets = "..."} (since it can't be referenced as a Class<?>).
 * Captures the outer-grid {@link GridLayout.RowHelper} local that vanilla's
 * constructor builds at the start of init, then appends our button as the
 * next row spanning both columns. {@code arrangeElements} runs automatically
 * via the Tab system's {@code doLayout} call, so we don't trigger it here.
 *
 * Applies regardless of which world preset the user has selected.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
public abstract class CreateWorldScreenMixin {

    @Inject(
        method = "<init>",
        at = @At("TAIL"),
        locals = LocalCapture.CAPTURE_FAILEXCEPTION
    )
    private void dungeontrain$addOptionsRow(
        CreateWorldScreen createWorldScreen,
        CallbackInfo ci,
        GridLayout.RowHelper rowHelper
    ) {
        Button button = Button.builder(
            Component.translatable("gui.dungeontrain.options.button"),
            b -> Minecraft.getInstance().setScreen(new DungeonTrainOptionsScreen(createWorldScreen))
        ).width(308).build();
        rowHelper.addChild(button, 2);
    }
}
