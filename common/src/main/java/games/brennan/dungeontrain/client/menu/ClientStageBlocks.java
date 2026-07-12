package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.net.StageBlockStripsPacket;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Client-side cache of the most recent {@link StageBlockStripsPacket} — the per-stage block icon
 * strips the Stages panel rows render. Fed on its own channel (mirrors
 * {@link games.brennan.dungeontrain.client.PackageListClient}): the server pushes only when the
 * stage-blocks index changes, and an empty packet (editor exit) clears the cache.
 */
public final class ClientStageBlocks {

    private static final StageBlockStripsPacket.Strip EMPTY_STRIP =
        new StageBlockStripsPacket.Strip("", java.util.List.of(), 0);

    private static volatile Map<String, StageBlockStripsPacket.Strip> BY_STAGE = Map.of();

    private ClientStageBlocks() {}

    /** Replace the cache with the packet's strips (empty packet clears). */
    public static void apply(StageBlockStripsPacket packet) {
        if (packet.strips().isEmpty()) {
            BY_STAGE = Map.of();
            return;
        }
        Map<String, StageBlockStripsPacket.Strip> next = new LinkedHashMap<>(packet.strips().size());
        for (StageBlockStripsPacket.Strip s : packet.strips()) {
            next.put(s.stageId().toLowerCase(Locale.ROOT), s);
        }
        BY_STAGE = Map.copyOf(next);
    }

    /** The strip for {@code stageId} — never null (empty strip when unknown). */
    public static StageBlockStripsPacket.Strip stripFor(String stageId) {
        if (stageId == null) return EMPTY_STRIP;
        StageBlockStripsPacket.Strip s = BY_STAGE.get(stageId.toLowerCase(Locale.ROOT));
        return s == null ? EMPTY_STRIP : s;
    }

    public static void clear() {
        BY_STAGE = Map.of();
    }

    public static void onLoggingOut() {
        clear();
    }
}
