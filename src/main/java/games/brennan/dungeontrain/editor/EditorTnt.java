package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.vehicle.MinecartTNT;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * The editor's <b>TNT</b> setting (Settings → TNT | On | Off): while it is Off, TNT set off inside
 * an editor plot — a lit TNT block or a TNT minecart — fizzles instead of exploding, so nothing in
 * the plot is broken. The TNT itself is still used up; stopping the priming would mean patching
 * every path that lights it, and the explosion is the only part that does harm.
 *
 * <p>A world flag ({@link DungeonTrainWorldData#isEditorTntOn}), scoped by position exactly as
 * {@link EditorObservers} is. Consumed by {@link EditorSettingEvents}.</p>
 */
public final class EditorTnt {

    private EditorTnt() {}

    /** True for the entities whose explosion this setting owns. */
    public static boolean isTnt(@Nullable Entity source) {
        return source instanceof PrimedTnt || source instanceof MinecartTNT;
    }

    /** True when TNT going off at {@code pos} must not explode under the setting. Client levels: never. */
    public static boolean isMuted(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(serverLevel.getServer().overworld());
        if (data.isEditorTntOn()) return false;
        if (EditorWorldLayout.isEditorWorld(serverLevel)) return true;
        return EditorCategory.locateAt(pos, data.dims()).isPresent();
    }
}
