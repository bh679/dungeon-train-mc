package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.template.TestGate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each author's test settings — the stage, Diff-Level and band their tests stand up at — held
 * between tests so a second test of the same build is seen the same way. In memory only, like
 * {@link EditorStageSelection}: they describe how the author is looking, not what they built, so
 * nothing is written to the template or the world.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TestOverrides {

    private static final Map<UUID, TestGate> BY_PLAYER = new ConcurrentHashMap<>();
    /**
     * The settings of the dimensional-carriage test last stamped. Its room keeps growing tiles on
     * later ticks, outside any command, and every test shares one pair key — so the tiles read the
     * settings from here rather than from a player.
     */
    private static volatile TestGate portalStamp = TestGate.AUTO;

    private TestOverrides() {}

    public static TestGate get(UUID player) {
        return BY_PLAYER.getOrDefault(player, TestGate.AUTO);
    }

    public static void set(UUID player, TestGate gate) {
        if (gate == null || gate.isAuto()) BY_PLAYER.remove(player);
        else BY_PLAYER.put(player, gate);
    }

    public static TestGate portalStamp() { return portalStamp; }

    public static void setPortalStamp(TestGate gate) {
        portalStamp = gate == null ? TestGate.AUTO : gate;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        BY_PLAYER.clear();
        portalStamp = TestGate.AUTO;
    }
}
