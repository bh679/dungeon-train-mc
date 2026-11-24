package games.brennan.dungeontrain.compat;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import dev.leo.sableplayerragdoll.api.PlayerlessDespawnRule;
import dev.leo.sableplayerragdoll.api.PlayerlessRagdollSession;
import dev.leo.sableplayerragdoll.api.RagdollAPI;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bridge into <b>Sable: Ragdolls</b> ({@code sable_player_ragdoll}), the third-party mod whose
 * physics ragdolls stand in for a dead player's body ({@code ship.sable.DeathBodyRagdoll}).
 *
 * <p>The mod is a required dependency, but DT never trusts a third-party API to keep linking: every
 * call is confined to the nested {@link Api} holder, which is only loaded once the mod list says the
 * mod is there, and the first {@link Error} (a {@link LinkageError}, or a mixin that another mod
 * failed to apply to Ragdolls) switches the bridge off for the rest of the session; {@link #probe()}
 * looks for that at server start. Without it the death scene plays exactly as it did before the
 * body existed.</p>
 */
public final class RagdollBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Mod id Sable: Ragdolls declares in its {@code neoforge.mods.toml}. */
    public static final String MOD_ID = "sable_player_ragdoll";

    /** A part of a fresh body lies at most this far from its torso (blocks). */
    static final double PART_REACH = 3.0;

    private static volatile boolean present = detectPresent();

    private RagdollBridge() {}

    /** Present when the API class loads AND the mod list has the mod (classpath alone outside a mod environment). */
    private static boolean detectPresent() {
        try {
            Class.forName("dev.leo.sableplayerragdoll.api.RagdollAPI", false, RagdollBridge.class.getClassLoader());
        } catch (Throwable t) {
            return false;
        }
        try {
            return ModList.get().isLoaded(MOD_ID);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Is Sable: Ragdolls installed and linking? */
    public static boolean isPresent() {
        return present;
    }

    /**
     * Spawn a playerless ragdoll wearing {@code profile}'s skin and {@code equipmentFrom}'s gear,
     * which nobody can grab and which the mod itself expires after {@code lifetimeTicks} if DT has
     * not released it first. Returns the root sub-level id, or null when the mod is absent, the
     * spawn was refused, or the API failed.
     *
     * @param velocityPerSecond initial linear velocity in blocks per second
     */
    @Nullable
    public static UUID spawnBody(ServerLevel level, Vec3 position, float yawDegrees, GameProfile profile,
                                 Vec3 velocityPerSecond, ServerPlayer equipmentFrom, int lifetimeTicks) {
        if (!present) return null;
        try {
            return Api.spawn(level, position, yawDegrees, profile, velocityPerSecond, equipmentFrom, lifetimeTicks);
        } catch (Error e) {
            disable(e);
            return null;
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Sable: Ragdolls refused the death body: {}", e.toString());
            return null;
        }
    }

    /** The ids of every Sable: Ragdolls sub-level in {@code level} right now; empty when the mod is off. */
    public static Set<UUID> ragdollParts(ServerLevel level) {
        if (!present) return Set.of();
        try {
            return Api.ragdollParts(level);
        } catch (Error e) {
            disable(e);
            return Set.of();
        }
    }

    /**
     * Add {@code velocityPerSecond} to every part of the body {@code bodyId}: its root, its torso, and
     * the ragdoll sub-levels that were not in {@code before} (the parts this spawn made) within
     * {@link #PART_REACH} of the torso. Applied as a velocity change through Sable's rigid-body handle,
     * so every part moves together whatever its mass. Spawn velocity alone barely moves the body.
     *
     * @return how many parts took the push (read back from the physics body); 0 when the body's
     *         physics is not up yet (call again next tick); -1 when the body is gone or the API failed
     */
    public static int throwBody(ServerLevel level, UUID bodyId, Set<UUID> before, Vec3 velocityPerSecond) {
        if (!present || bodyId == null) return -1;
        try {
            return Api.throwBody(level, bodyId, before, velocityPerSecond);
        } catch (Error e) {
            disable(e);
            return -1;
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Could not throw death body {}: {}", bodyId, e.toString());
            return -1;
        }
    }

    /** Release a body spawned by {@link #spawnBody}. Safe when it is already gone or unknown. */
    public static void release(UUID bodyId) {
        if (!present || bodyId == null) return;
        try {
            Api.release(bodyId);
        } catch (Error e) {
            disable(e);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Sable: Ragdolls failed to release body {}: {}", bodyId, e.toString());
        }
    }

    /** Forget every session handle (server stopped). */
    public static void clear() {
        if (!present) return;
        try {
            Api.clear();
        } catch (Error e) {
            disable(e);
        }
    }

    /**
     * Internals of Sable: Ragdolls that DT's calls reach and the mod's own expiry runs through. Other
     * mods mixin into them (Sable: Ragdoll - Patch does), and a mixin applies when its target class
     * first loads, so a patch built for another Ragdolls version only fails at that moment.
     */
    static final List<String> PROBED_CLASSES = List.of(
        "dev.leo.sableplayerragdoll.physics.RagdollExpireHelper",
        "dev.leo.sableplayerragdoll.physics.RagdollBlockOwnership");

    /** What a failure loading or calling into Sable: Ragdolls means for the bridge. */
    enum Verdict {
        /** The class is not there (renamed in another Ragdolls version): nothing to judge. */
        SKIP,
        /** Ragdolls (or a mod patching it) is broken: switch death bodies off. */
        DISABLE,
        /** The JVM itself is in trouble: not ours to swallow. */
        RETHROW
    }

    static Verdict judge(Throwable t) {
        if (t instanceof VirtualMachineError) return Verdict.RETHROW;
        if (t instanceof ClassNotFoundException) return Verdict.SKIP;
        return Verdict.DISABLE;
    }

    /**
     * Load (without initialising) each of {@link #PROBED_CLASSES} up front, so a mod patching Sable:
     * Ragdolls for a different version fails here, at server start, instead of in the tick where a
     * death body is released or expires. The mod expires DT's bodies itself, out of DT's reach, so
     * once a probe fails no body is spawned at all.
     */
    public static void probe() {
        if (!present) return;
        for (String name : PROBED_CLASSES) {
            try {
                Class.forName(name, false, RagdollBridge.class.getClassLoader());
            } catch (Throwable t) {
                switch (judge(t)) {
                    case SKIP -> LOGGER.debug("[DungeonTrain] Sable: Ragdolls has no {}; not probed", name);
                    case RETHROW -> throw (VirtualMachineError) t;
                    case DISABLE -> {
                        present = false;
                        LOGGER.warn("[DungeonTrain] {} failed to load, most likely a mod that patches Sable: Ragdolls "
                            + "(such as Sable: Ragdoll - Patch) does not match the installed Ragdolls version. "
                            + "Death bodies are off for this session: {}", name, t.toString());
                        return;
                    }
                }
            }
        }
    }

    private static void disable(Error e) {
        if (judge(e) == Verdict.RETHROW) throw e;
        present = false;
        LOGGER.warn("[DungeonTrain] Sable: Ragdolls API no longer links; death bodies are off for this session: {}", e.toString());
    }

    /** Hard imports live here only, so the outer class loads without the mod. */
    private static final class Api {

        /** Live sessions by root sub-level id; the mod exposes release only through the session handle. */
        private static final Map<UUID, PlayerlessRagdollSession> SESSIONS = new HashMap<>();

        private Api() {}

        @Nullable
        static UUID spawn(ServerLevel level, Vec3 position, float yawDegrees, GameProfile profile,
                          Vec3 velocityPerSecond, ServerPlayer equipmentFrom, int lifetimeTicks) {
            PlayerlessRagdollSession session = RagdollAPI.spawnPlayerless(level, position, yawDegrees, profile,
                velocityPerSecond, PlayerlessDespawnRule.afterTicks(lifetimeTicks));
            if (session == null) return null;
            UUID id = session.id();
            SESSIONS.put(id, session);
            RagdollAPI.copyEquipmentFrom(level, id, equipmentFrom);
            RagdollAPI.setGrabDisabled(level, id, true);
            return id;
        }

        static Set<UUID> ragdollParts(ServerLevel level) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) return Set.of();
            Set<UUID> ids = new HashSet<>();
            for (SubLevel sub : container.getAllSubLevels()) {
                if (!sub.isRemoved() && RagdollAPI.isRagdollSubLevel(sub)) ids.add(sub.getUniqueId());
            }
            return ids;
        }

        static int throwBody(ServerLevel level, UUID bodyId, Set<UUID> before, Vec3 v) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) return -1;
            SubLevel root = container.getSubLevel(bodyId);
            if (root != null && root.isRemoved()) return -1;
            UUID torsoId = RagdollAPI.torsoSubLevelId(bodyId);
            SubLevel torso = torsoId == null ? null : container.getSubLevel(torsoId);
            SubLevel anchor = torso != null && !torso.isRemoved() ? torso : root;
            if (anchor == null) return 0;
            Vector3dc centre = anchor.logicalPose().position();
            Map<UUID, ServerSubLevel> parts = new LinkedHashMap<>();
            for (SubLevel sub : container.getAllSubLevels()) {
                if (!(sub instanceof ServerSubLevel server) || sub.isRemoved()) continue;
                UUID id = sub.getUniqueId();
                boolean own = id.equals(bodyId) || id.equals(torsoId);
                boolean fresh = !before.contains(id) && RagdollAPI.isRagdollSubLevel(sub)
                    && sub.logicalPose().position().distance(centre) <= PART_REACH;
                if (own || fresh) parts.put(id, server);
            }
            Vector3d dv = new Vector3d(v.x, v.y, v.z);
            Vector3d before0 = new Vector3d();
            Vector3d after = new Vector3d();
            int took = 0;
            for (ServerSubLevel part : parts.values()) {
                RigidBodyHandle handle = RigidBodyHandle.of(part);
                if (handle == null || !handle.isValid()) continue;
                handle.getLinearVelocity(before0);
                handle.addLinearAndAngularVelocity(dv, new Vector3d());
                handle.getLinearVelocity(after);
                if (after.distance(before0) > 0.5 * dv.length()) took++;
            }
            LOGGER.info("[DungeonTrain] Death body {} thrown {}: {}/{} parts took it (last read {} -> {})",
                bodyId, v, took, parts.size(), before0, after);
            return took;
        }

        static void release(UUID id) {
            PlayerlessRagdollSession session = SESSIONS.remove(id);
            if (session != null) session.release();
        }

        static void clear() {
            SESSIONS.clear();
        }
    }
}
