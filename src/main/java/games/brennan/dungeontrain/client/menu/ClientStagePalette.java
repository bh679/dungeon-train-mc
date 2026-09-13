package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.StageIconPalettePacket;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Client cache of the effective stage's placeholder resolutions ({@link StageIconPalettePacket}),
 * read by the stage-aware item icons. Empty outside the editor.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ClientStagePalette {

    private static volatile String stageId = "";
    private static volatile Map<String, String> byName = Map.of();
    private static volatile java.util.Set<String> repeats = java.util.Set.of();

    private ClientStagePalette() {}

    public static void apply(StageIconPalettePacket packet) {
        Map<String, String> next = new HashMap<>();
        java.util.Set<String> nextRepeats = new java.util.HashSet<>();
        for (StageIconPalettePacket.Entry e : packet.entries()) {
            next.put(e.name(), e.blockId());
            if (e.repeat()) nextRepeats.add(e.name());
        }
        boolean changed = !next.equals(byName);
        byName = Map.copyOf(next);
        repeats = java.util.Set.copyOf(nextRepeats);
        stageId = packet.stageId() == null ? "" : packet.stageId();
        // Placed placeholders blend with their target (StagePlaceholderBakedModel) — the meshes
        // already built for the old target have to be rebuilt. Rare: stage select / override / bake.
        if (changed) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.levelRenderer != null && mc.level != null) mc.levelRenderer.allChanged();
        }
    }

    /** The effective stage id, or {@code ""} when none (outside the editor). */
    public static String stageId() {
        return stageId;
    }

    /** The block id placeholder {@code name} resolves to for the effective stage, or null. */
    public static String resolved(String name) {
        return byName.get(name);
    }

    /** True when placeholder {@code name} only repeats an earlier slot for the effective stage. */
    public static boolean isRepeat(String name) {
        return repeats.contains(name);
    }

    public static void clear() {
        stageId = "";
        byName = Map.of();
        repeats = java.util.Set.of();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }
}
