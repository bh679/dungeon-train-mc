package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorModeSettingsPacket;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The running world's per-mode editor Settings ({@link EditorModeSettings}), held where every gate
 * can read them for one volatile read — the fluid and redstone gates run on hot paths in play worlds
 * too. Changes go through {@link #set}, which persists them, swaps the copy, and tells every client.
 */
public final class EditorModeSettingsService {

    private static volatile EditorModeSettings current = EditorModeSettings.DEFAULTS;

    private EditorModeSettingsService() {}

    /** The settings now — {@link EditorModeSettings#DEFAULTS} while no world is running. */
    public static EditorModeSettings current() {
        return current;
    }

    /** Set {@code setting} in {@code mode} only: persist, swap the gates' copy, tell every client. */
    public static void set(MinecraftServer server, EditorSettingMode mode, EditorModeSetting setting, boolean value) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
        EditorModeSettings before = data.editorModeSettings();
        EditorModeSettings next = before.with(mode, setting, value);
        data.setEditorModeSettings(next);
        current = next;
        // Fluid held still has used its tick; flow switched back on has to give it another.
        if (setting == EditorModeSetting.FLUID_FLOW && value && !before.get(mode, setting)) {
            EditorFluids.wake(server);
        }
        EditorModeSettingsPacket packet = EditorModeSettingsPacket.of(next);
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            DungeonTrainNet.sendTo(online, packet);
        }
    }

    /** Tell one player (login). */
    public static void sendTo(ServerPlayer player) {
        DungeonTrainNet.sendTo(player, EditorModeSettingsPacket.of(current));
    }

    /** Re-read the settings from the world that just started. */
    static void sync(MinecraftServer server) {
        current = DungeonTrainWorldData.get(server.overworld()).editorModeSettings();
    }

    /** No world running: the defaults. */
    static void reset() {
        current = EditorModeSettings.DEFAULTS;
    }
}
