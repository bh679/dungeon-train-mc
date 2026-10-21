package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The editor's <b>Redstone</b> setting (Settings → Redstone | On | Off): while it is Off, nothing
 * inside an editor plot gives a redstone signal — wire, torches, repeaters, levers, buttons and
 * redstone blocks all read as 0 — so lamps, pistons, doors and dispensers never react to power. An
 * author can wire a contraption and walk away from it without it running. A door or trapdoor still
 * opens by hand, because that never asks for a signal.
 *
 * <p>A world flag ({@link DungeonTrainWorldData#isEditorRedstoneOn}), scoped by position exactly as
 * {@link EditorObservers} is: a plot located by {@link EditorCategory#locateAt}, or anywhere in the
 * Train Editor void world. A play world's train sits in no plot, so it is never touched.</p>
 *
 * <p>Consumed by {@code SignalGetterMuteMixin}, which runs on every signal read in the game — so the
 * flag is mirrored in {@link #anyMuted}, and while Redstone is On (the default, and every play world)
 * the check is one volatile read. {@link EditorSettingEvents} keeps the mirror in step with the
 * loaded world; {@link #set} keeps it in step with a change.</p>
 */
public final class EditorRedstone {

    /** Mirror of "Redstone is Off in the running world" — the hot path's only read while On. */
    private static volatile boolean anyMuted = false;

    private EditorRedstone() {}

    /** True when a signal read at {@code pos} must come back 0 under the setting. Client levels: never. */
    public static boolean isMuted(Object level, BlockPos pos) {
        if (!anyMuted) return false;
        if (!(level instanceof ServerLevel serverLevel)) return false;
        if (EditorWorldLayout.isEditorWorld(serverLevel)) return true;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(serverLevel.getServer().overworld());
        return EditorCategory.locateAt(pos, data.dims()).isPresent();
    }

    /** Settings → Redstone On / Off: persist the flag and move the fast path with it. */
    public static void set(MinecraftServer server, boolean on) {
        DungeonTrainWorldData.get(server.overworld()).setEditorRedstoneOn(on);
        anyMuted = !on;
    }

    /** Re-read the flag from the world that just started. */
    static void sync(MinecraftServer server) {
        anyMuted = !DungeonTrainWorldData.get(server.overworld()).isEditorRedstoneOn();
    }

    /** No world running: nothing is muted. */
    static void reset() {
        anyMuted = false;
    }

    /** Test seam: the fast-path mirror. */
    static boolean anyMuted() {
        return anyMuted;
    }
}
