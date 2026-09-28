package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;

/**
 * The editor's <b>Observers</b> setting (Settings → Observers | On | Off): while it is Off, an
 * observer inside an editor plot never pulses, whatever changed in front of it — a hand-placed
 * block included. That is the point: an author wiring a contraption wants to place and break
 * around it without setting it off, and the write-site guard ({@code CarriageStampGuard}) only
 * covers DT's own rewrites, never a player's edit.
 *
 * <p>A world flag ({@link DungeonTrainWorldData#isEditorObserversOn}), not a per-player one:
 * an observer is world state, and the flag is persisted so the choice survives a relaunch.
 * Scoped by <i>position</i> — a plot located by {@link EditorCategory#locateAt}, or anywhere in
 * the Train Editor void world — so a play world's train, whose observers sit in no plot, is never
 * touched even if the flag were somehow Off there. Consumed by {@code ObserverBlockStampMixin}.</p>
 */
public final class EditorObservers {

    private EditorObservers() {}

    /** True when the observer at {@code pos} must stay quiet under the setting. Client levels: never. */
    public static boolean isMuted(LevelAccessor level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(serverLevel);
        if (data.isEditorObserversOn()) return false;
        if (EditorWorldLayout.isEditorWorld(serverLevel)) return true;
        return EditorCategory.locateAt(pos, data.dims()).isPresent();
    }
}
