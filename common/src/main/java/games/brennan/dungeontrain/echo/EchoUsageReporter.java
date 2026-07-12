package games.brennan.dungeontrain.echo;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import games.brennan.playermob.compat.ReincarnationRecord;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

/**
 * Tells the relay when one of its reincarnation records is actually embodied as a remote echo —
 * {@code POST <relayBase>/reincarnations/used {id}} — so the data explorer can count how many times
 * each death has come back (dp-relay ≥ 0.15.0 increments the record's {@code uses} counter; older
 * relays 404 the route, which is harmlessly ignored here).
 *
 * <p>Only relay-sourced lives report: a record whose {@code sourceId} is {@code "discordpresence"}
 * carries the relay's own record id in {@code key} (see DP's {@code ReincarnationRecordData}).
 * PlayerMob's local death log ({@code "playermob"}) and the dev test command ({@code "dttest"})
 * never touched the relay, so there is nothing to count. That origin check is also the consent
 * story: a relay record can only exist client-side because DP's consented reincarnation client
 * fetched it, so reporting its use stays inside the already-granted network feature.</p>
 *
 * <p>Handed to the durable {@link RelayOutbox}: persisted and delivered at-least-once on the next
 * flush (surviving a relay outage / offline launch), never blocking or costing a tick.</p>
 */
public final class EchoUsageReporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** DP's source id for lives imported from the relay ({@code ReincarnationRecordData} contract). */
    static final String RELAY_SOURCE_ID = "discordpresence";

    /** How far to look for the player the echo spawned for — mirrors the journal's audience radius. */
    private static final double AUDIENCE_RADIUS = 128.0;

    private EchoUsageReporter() {}

    /**
     * Report {@code record} as used, attributing it to the nearest player when one is in range.
     * No-op for non-relay records or an unparseable relay id.
     */
    public static void report(ReincarnationRecord record, PlayerMobEntity mob) {
        if (record == null || mob == null) {
            return;
        }
        Integer id = relayRecordId(record.sourceId(), record.key());
        if (id == null) {
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("id", id);
        if (mob.level() instanceof ServerLevel level) {
            Player audience = level.getNearestPlayer(mob, AUDIENCE_RADIUS);
            if (audience != null) {
                body.addProperty("byUuid", audience.getUUID().toString().replace("-", ""));
                body.addProperty("byPlayer", audience.getGameProfile().getName());
            }
        }
        post(id, body.toString());
    }

    /**
     * The relay record id carried by a DP-imported life, or {@code null} when this record never came
     * from the relay (other source, or a key that isn't a positive integer). Pure — unit-tested.
     */
    static Integer relayRecordId(String sourceId, String key) {
        if (!RELAY_SOURCE_ID.equals(sourceId) || key == null) {
            return null;
        }
        try {
            int id = Integer.parseInt(key.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void post(int id, String json) {
        RelayOutbox.get().enqueue("/reincarnations/used", json);
        LOGGER.debug("[DungeonTrain] Echo-usage report for relay record {} queued to the relay outbox.", id);
    }
}
