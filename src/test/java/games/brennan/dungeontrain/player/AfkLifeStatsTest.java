package games.brennan.dungeontrain.player;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import games.brennan.dungeontrain.event.PlayerActivityTracker.Reason;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The per-life AFK record the run summary carries: buckets, counts, persistence and reset. */
class AfkLifeStatsTest {

    private static final ResourceLocation ADV = ResourceLocation.fromNamespaceAndPath("dungeontrain", "x");

    @Test
    @DisplayName("stopped time lands in its reason's bucket; tracking and paused add nothing")
    void stoppedBuckets() {
        AfkLifeStats s = new AfkLifeStats();
        s.addStopped(Reason.MOUSE_IDLE, 200L, false);
        s.addStopped(Reason.INPUT_IDLE, 40L, true);
        s.addStopped(Reason.NO_PROGRESS, 60L, false);
        s.addStopped(Reason.TRACKING, 1_000L, true);
        s.addStopped(Reason.PAUSED, 1_000L, true);
        assertEquals(10L, s.stoppedSec(Reason.MOUSE_IDLE));
        assertEquals(2L, s.stoppedSec(Reason.INPUT_IDLE));
        assertEquals(3L, s.stoppedSec(Reason.NO_PROGRESS));
        assertEquals(2L, s.containerSec());
    }

    @Test
    @DisplayName("stops, auto-pauses and refusals count; a refusal counts once per advancement")
    void counts() {
        AfkLifeStats s = new AfkLifeStats();
        s.addStop(Reason.MOUSE_IDLE);
        s.addStop(Reason.MOUSE_IDLE);
        s.addStop(Reason.PAUSED);
        s.addStop(Reason.TRACKING);
        s.addAutoPause(false);
        s.addAutoPause(true);
        s.addRefused(ADV);
        s.addRefused(ADV);
        assertEquals(2, s.stops(Reason.MOUSE_IDLE));
        assertEquals(1, s.stops(Reason.PAUSED));
        assertEquals(1, s.autoPausesWorld());
        assertEquals(1, s.autoPausesScreen());
        assertEquals(1, s.refusedCount());
    }

    @Test
    @DisplayName("survives a save and load")
    void codecRoundTrip() {
        AfkLifeStats s = new AfkLifeStats();
        s.addStopped(Reason.NO_PROGRESS, 400L, true);
        s.addStop(Reason.NO_PROGRESS);
        s.addAutoPause(true);
        s.addRefused(ADV);
        JsonElement saved = AfkLifeStats.CODEC.encodeStart(JsonOps.INSTANCE, s).getOrThrow();
        AfkLifeStats loaded = AfkLifeStats.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(s.toJson(), loaded.toJson());
    }

    @Test
    @DisplayName("clear starts a new life at zero")
    void clearResets() {
        AfkLifeStats s = new AfkLifeStats();
        s.addStopped(Reason.MOUSE_IDLE, 200L, true);
        s.addStop(Reason.MOUSE_IDLE);
        s.addAutoPause(false);
        s.addRefused(ADV);
        s.clear();
        assertEquals(new AfkLifeStats().toJson(), s.toJson());
    }

    @Test
    @DisplayName("the payload object carries every field the relay reads")
    void jsonShape() {
        JsonObject afk = new AfkLifeStats().toJson();
        for (String k : new String[] {"mouseIdle", "inputIdle", "noProgress"}) {
            assertEquals(0L, afk.getAsJsonObject("stoppedSec").get(k).getAsLong());
        }
        for (String k : new String[] {"mouseIdle", "inputIdle", "noProgress", "paused"}) {
            assertEquals(0, afk.getAsJsonObject("stops").get(k).getAsInt());
        }
        assertEquals(0, afk.getAsJsonObject("autoPauses").get("world").getAsInt());
        assertEquals(0, afk.getAsJsonObject("autoPauses").get("screen").getAsInt());
        assertEquals(0L, afk.get("containerSec").getAsLong());
        assertEquals(0, afk.get("refused").getAsInt());
    }
}
