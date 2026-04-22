package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.worldgen.FloorYState;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Mixes a Floor-Y row into the bottom of the vanilla World tab's grid layout.
 *
 * <p>The World tab is a non-static inner class of {@link CreateWorldScreen} extending
 * {@link GridLayoutTab}. Its constructor populates a 2-column grid with rows for World
 * Type + Customize (row 0), Seed label (row 1), Seed edit (row 2), Generate Structures
 * (row 3), and Bonus Chest (row 4). We append our row at index 5 spanning both columns.
 *
 * <p>The button reference is published to {@link FloorYState#button} so the
 * {@code CreateWorldScreenMixin.tick} handler can toggle its visibility as the user
 * changes the world preset.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
public abstract class CreateWorldScreenWorldTabMixin extends GridLayoutTab {

    @Unique
    private static final int DUNGEONTRAIN$FLOOR_Y_ROW = 5;

    @Unique
    private CreateWorldScreen dungeontrain$screen;

    protected CreateWorldScreenWorldTabMixin(Component title) {
        super(title);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void dungeontrain$appendFloorYRow(CreateWorldScreen screen, CallbackInfo ci) {
        this.dungeontrain$screen = screen;

        List<Integer> values = Arrays.stream(FloorYState.VALUES).boxed().toList();

        CycleButton<Integer> button = CycleButton.<Integer>builder(
                        (Integer y) -> Component.translatable("dungeontrain.floor_y.value", y))
                .withValues(values)
                .withInitialValue(FloorYState.get())
                .withTooltip(y -> Tooltip.create(Component.translatable("dungeontrain.floor_y.tooltip")))
                .create(
                        0,
                        0,
                        150,
                        20,
                        Component.translatable("dungeontrain.floor_y.label"),
                        (btn, newValue) -> dungeontrain$onFloorYChanged(newValue));

        this.layout.addChild(button, DUNGEONTRAIN$FLOOR_Y_ROW, 0, 1, 2,
                this.layout.newCellSettings().alignHorizontallyCenter());
        FloorYState.button = button;
    }

    @Unique
    private void dungeontrain$onFloorYChanged(int y) {
        FloorYState.set(y);
        if (this.dungeontrain$screen == null) {
            return;
        }
        Map<Integer, Holder<WorldPreset>> presets = FloorYState.presets;
        if (presets == null) {
            return;
        }
        Holder<WorldPreset> target = presets.get(y);
        if (target == null) {
            return;
        }
        WorldCreationUiState uiState =
                ((CreateWorldScreenAccessor) this.dungeontrain$screen).dungeontrain$getUiState();
        // Only swap when the current preset is a Dungeon Train variant.
        boolean isDT = uiState.getWorldType().preset().unwrapKey()
                .map(k -> DungeonTrain.MOD_ID.equals(k.location().getNamespace())
                        && (k.location().getPath().equals("dungeon_train")
                        || k.location().getPath().startsWith("dungeon_train_y")))
                .orElse(false);
        if (!isDT) {
            return;
        }
        uiState.setWorldType(new WorldCreationUiState.WorldTypeEntry(target));
    }
}
