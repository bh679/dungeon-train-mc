package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.NoteKind;
import games.brennan.dungeontrain.narrative.NoteSpokenLines;
import games.brennan.dungeontrain.train.DeathNoteEchoSpawner;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Steers live note echoes toward their target each tick: it marches the echo along the train toward
 * the target's carriage so the two actually meet. What happens on arrival depends on the
 * {@link NoteKind}:
 * <ul>
 *   <li>{@link NoteKind#DEATH} — once in the same/adjacent carriage the target is <b>forced</b>, so
 *       the echo engages even an invulnerable (Creative/Spectator) player: vanilla target-selection
 *       skips those, but the curse should not. Flee-by-trait is preserved:
 *       {@code FleeFromCategoryGoal} (higher priority) is reaction-driven, not {@code getTarget}-driven,
 *       so a fleeing echo still flees despite a forced target.</li>
 *   <li>{@link NoteKind#LOVE} — the march is the whole point (it is walking the train to find them)
 *       and no target is ever forced. Forcing one would make it attack the very player it was sent
 *       to love, overriding the feeling the spawner seeded.</li>
 * </ul>
 *
 * <p>An echo also <b>reads its note aloud</b> once it is alongside: the same carriage-index test
 * that decides a curse may engage decides that either kind may speak, and from there it works
 * through the script stamped on it at spawn ({@code DeathNoteEchoSpawner.KEY_LINES}) — opening by
 * calling the target's name, then one line at a time, the gap between them set by the length of the
 * line just spoken ({@link NoteSpokenLines#delayTicksFor}). The whole server hears it, like the
 * arrival announcement it follows. A note with no approved words simply never speaks: the relay
 * withholds a body its moderation flagged, and that costs the echo its voice, never the curse.</p>
 *
 * <p>Echoes are tracked by UUID (registered at spawn) rather than a spatial scan, because a
 * carriage-bound echo lives in Sable shipyard coordinates far from the player's world position — a
 * world-space AABB around the player would never find it. Steering compares carriage indices
 * ({@link TrainConfinement#carriageIndex}), a frame the echo and the player share.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DeathNoteEchoController {

    private static final int SCAN_PERIOD_TICKS = 10;

    /** One tracked echo: who it was sent to, and which kind of note sent it. */
    private record Steered(UUID targetUuid, NoteKind kind) {}

    /** echo entity UUID → its steering entry. Registered at spawn, cleared on echo death. */
    private static final Map<UUID, Steered> ACTIVE = new ConcurrentHashMap<>();

    private DeathNoteEchoController() {}

    /** Called by {@code DeathNoteEchoSpawner} once the echo is added to the world. */
    public static void register(UUID echoUuid, UUID targetUuid, NoteKind kind) {
        if (echoUuid != null && targetUuid != null) {
            ACTIVE.put(echoUuid, new Steered(targetUuid, kind == null ? NoteKind.DEATH : kind));
        }
    }

    /** Called by {@code DeathNoteEvents} when a note echo dies. */
    public static void unregister(UUID echoUuid) {
        if (echoUuid != null) ACTIVE.remove(echoUuid);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (ACTIVE.isEmpty()) return;
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0) return;
        for (Map.Entry<UUID, Steered> e : ACTIVE.entrySet()) {
            if (!(level.getEntity(e.getKey()) instanceof PlayerMobEntity echo)) continue; // other level / unloaded
            if (!echo.isAlive()) { ACTIVE.remove(e.getKey()); continue; }
            ServerPlayer target = level.getServer().getPlayerList().getPlayer(e.getValue().targetUuid());
            if (target == null) continue;                                    // target offline
            steer(echo, target, e.getValue().kind());
            speak(level, echo, target);
        }
    }

    /**
     * March the echo toward the target's carriage and, for a curse only, force the target once
     * alongside. Uses carriage indices (a frame the echo and the player share), NOT raw world coords
     * — the echo is in shipyard space and the player in world space, so subtracting {@code getX()}
     * would mix frames.
     */
    private static void steer(PlayerMobEntity echo, ServerPlayer target, NoteKind kind) {
        int echoIdx = TrainConfinement.carriageIndex(echo);
        int targetIdx = TrainConfinement.carriageIndex(target);
        if (echoIdx == TrainConfinement.NO_CARRIAGE || targetIdx == TrainConfinement.NO_CARRIAGE) return;
        int dir = Integer.signum(targetIdx - echoIdx);
        if (dir != 0) {
            try {
                TrainConfinement.setMarchDirection(echo, dir);           // close the distance along the train
            } catch (Throwable ignored) {
                // best-effort; PlayerMob's own goals still function once the two are in the same cart
            }
        }
        // A Love Note echo is never given a target — it arrived to greet them, and PlayerMob's
        // social goals take it from here off the feeling the spawner seeded.
        if (kind != NoteKind.LOVE && Math.abs(echoIdx - targetIdx) <= 1) {
            echo.setTarget(target);                                       // engage even an invulnerable target
        }
    }

    /**
     * Read out the next line of the note, if the echo is alongside its target and the previous line
     * has had its say. Both kinds speak — a Love Note echo has come a long way to say something too.
     *
     * <p>Everything the recital needs lives on the entity ({@code KEY_LINES} /
     * {@code KEY_LINE_INDEX} / {@code KEY_NEXT_SPEAK}), so an echo that is saved and reloaded picks
     * up exactly where it stopped rather than starting the note again. Timing is quantised to
     * {@link #SCAN_PERIOD_TICKS} because this rides the same scan as the steering — half a second of
     * slack on a pause of one to ten seconds, which nobody can hear.</p>
     */
    private static void speak(ServerLevel level, PlayerMobEntity echo, ServerPlayer target) {
        CompoundTag data = echo.getPersistentData();
        if (!data.contains(DeathNoteEchoSpawner.KEY_LINES)) return;      // nothing approved to say
        int echoIdx = TrainConfinement.carriageIndex(echo);
        int targetIdx = TrainConfinement.carriageIndex(target);
        if (echoIdx == TrainConfinement.NO_CARRIAGE || targetIdx == TrainConfinement.NO_CARRIAGE) return;
        if (Math.abs(echoIdx - targetIdx) > 1) return;                   // not close enough yet
        ListTag lines = data.getList(DeathNoteEchoSpawner.KEY_LINES, Tag.TAG_STRING);
        int spoken = data.getInt(DeathNoteEchoSpawner.KEY_LINE_INDEX);
        if (spoken > lines.size()) return;                               // the note has been read out
        long now = level.getGameTime();
        if (spoken > 0 && now < data.getLong(DeathNoteEchoSpawner.KEY_NEXT_SPEAK)) return;
        // The opener is the target's name, the way the note itself names them; then the lines below it.
        String text = spoken == 0
                ? "@" + target.getGameProfile().getName()
                : lines.getString(spoken - 1);
        if (text.isBlank()) { data.putInt(DeathNoteEchoSpawner.KEY_LINE_INDEX, spoken + 1); return; }
        level.getServer().getPlayerList().broadcastSystemMessage(spokenLine(echo, text), false);
        data.putInt(DeathNoteEchoSpawner.KEY_LINE_INDEX, spoken + 1);
        // A longer line buys a longer silence after it — the note is being read out, not pasted.
        data.putLong(DeathNoteEchoSpawner.KEY_NEXT_SPEAK, now + NoteSpokenLines.delayTicksFor(text));
    }

    /**
     * One spoken line, rendered as chat — deliberately indistinguishable from a player talking:
     * vanilla's own {@code chat.type.text} ({@code <name> words}), in vanilla's own colour, with no
     * styling of ours on top. An earlier version coloured it by kind (dark red / pink), which read
     * as a system announcement; the note is someone speaking, so it should look like someone
     * speaking. Using the vanilla key rather than one of ours also means every locale already has
     * it, in the phrasing that locale's players already read chat in.
     */
    private static Component spokenLine(PlayerMobEntity echo, String text) {
        return Component.translatable("chat.type.text", echo.getName(), Component.literal(text));
    }
}
