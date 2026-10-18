package games.brennan.dungeontrain.client.sound;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.client.ClientAdvancementsAccessor;
import games.brennan.dungeontrain.net.ClientActionPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.Util;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.ResourceLocation;

/**
 * Tells the server the player just changed the train engine volume, so it can
 * award "Sound Check". The setting is client-side config
 * ({@link games.brennan.dungeontrain.config.ClientDisplayConfig#setTrainEngineVolume})
 * with no server-visible effect, so the client has to say so.
 *
 * <p>Called from the setter itself rather than from each UI surface — the DT
 * options stepper, the sound-screen slider and the pause-menu slider all funnel
 * through it, and it already skips unchanged writes, so "the setter actually
 * changed the value" is exactly the signal wanted. It keeps reporting until the
 * client's own advancement list shows "Sound Check" earned: the server can refuse
 * a grant (the AFK rule, e.g. a change made long into the pause menu), and a
 * report sent only once would then be lost for the whole connection. At most one
 * report a second, so dragging a slider does not flood the server.</p>
 */
public final class TrainVolumeAdvancement {

    private static final ResourceLocation SOUND_CHECK =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/changed_engine_volume");

    private static final long REPORT_COOLDOWN_MS = 1000L;

    private static long lastReportMs = Long.MIN_VALUE / 2;

    private TrainVolumeAdvancement() {}

    /** Report a real volume change until Sound Check is earned. Silent in the main menu. */
    public static void onVolumeChanged() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null || isEarned(connection)) return;
        long now = Util.getMillis();
        if (now - lastReportMs < REPORT_COOLDOWN_MS) return;
        lastReportMs = now;
        DungeonTrainNet.sendToServer(new ClientActionPacket("changed_engine_volume"));
    }

    private static boolean isEarned(ClientPacketListener connection) {
        ClientAdvancements advancements = connection.getAdvancements();
        AdvancementHolder holder = advancements.get(SOUND_CHECK);
        if (holder == null) return false;
        AdvancementProgress progress = ((ClientAdvancementsAccessor) advancements).dungeontrain$progress().get(holder);
        return progress != null && progress.isDone();
    }
}
