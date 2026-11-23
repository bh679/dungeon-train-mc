package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The editor's <b>Redstone</b> setting (Settings → Redstone | On | Off): while it is Off, nothing
 * inside an editor plot gives a redstone signal — wire, torches, repeaters, levers, buttons and
 * redstone blocks all read as 0 — so lamps, pistons, doors and dispensers never react to power. An
 * author can wire a contraption and walk away from it without it running. A door or trapdoor still
 * opens by hand, because that never asks for a signal.
 *
 * <p>A world flag with an editing and a test value ({@link EditorModeSetting#REDSTONE}), scoped by position exactly as
 * {@link EditorObservers} is: a plot located by {@link EditorCategory#locateAt}, or anywhere in the
 * Train Editor void world. A play world's train sits in no plot, so it is never touched.</p>
 *
 * <p>Consumed by {@code SignalGetterMuteMixin}, which runs on every signal read in the game — and while Redstone is On in both modes (the default, and every play world)
 * the check is one volatile read of {@link EditorModeSettingsService#current}, which is kept in
 * step with the loaded world and every change.</p>
 */
public final class EditorRedstone {

    private EditorRedstone() {}

    /** True when a signal read at {@code pos} must come back 0 under the setting. Client levels: never. */
    public static boolean isMuted(Object level, BlockPos pos) {
        EditorModeSettings settings = EditorModeSettingsService.current();
        if (settings.onInBoth(EditorModeSetting.REDSTONE)) return false;
        if (!(level instanceof ServerLevel serverLevel)) return false;
        if (!EditorWorldLayout.isEditorWorld(serverLevel)) {
            DungeonTrainWorldData data = DungeonTrainWorldData.get(serverLevel.getServer().overworld());
            if (EditorCategory.locateAt(pos, data.dims()).isEmpty()) return false;
        }
        return !settings.get(EditorSettingMode.at(serverLevel, pos), EditorModeSetting.REDSTONE);
    }
}
