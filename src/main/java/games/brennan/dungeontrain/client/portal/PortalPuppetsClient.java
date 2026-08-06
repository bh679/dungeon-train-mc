package games.brennan.dungeontrain.client.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.PortalPuppetsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holds the puppets this client is currently being shown, and keeps one render model per puppet.
 *
 * <p>Everything here is a drawing. A puppet is not in {@code ClientLevel} — not in its entity
 * storage, not in its ticking list, not in {@code level.players()}. It cannot be walked into,
 * targeted, attacked, or found by anything that asks the level what is nearby. The only code that
 * ever sees one is {@link PortalPuppetRenderer}.</p>
 *
 * <p><b>Models are kept, not rebuilt.</b> Each snapshot updates the existing model for a key rather
 * than making a new one, which is what lets a puppet interpolate between ticks and keep a walk cycle
 * going. Building a fresh entity every tick would also mean re-resolving a skin twenty times a
 * second.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PortalPuppetsClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Drop everything if no snapshot arrives for this long.
     *
     * <p>The server sends an explicit empty snapshot when a corridor empties, and that is the signal
     * that normally clears these. This is for when there is no signal at all — a disconnect mid-walk,
     * a teleport out, a server that stopped talking. Without it a puppet would stand in the corridor
     * for as long as the world stayed loaded. Two seconds is long enough that ordinary lag never
     * trips it and short enough that a stranded puppet is a blip rather than a ghost.</p>
     */
    private static final int STALE_TICKS = 40;

    /** Source entity id → its render model and last two states. Main thread only. */
    private static final Map<Integer, Puppet> PUPPETS = new HashMap<>();

    private static int ticksSinceSnapshot;

    private PortalPuppetsClient() {}

    /**
     * One puppet: the model to draw, and the two states to interpolate between.
     *
     * <p>Positions are held in whatever space the server sent — world for a puppet standing in a
     * twin, shipyard-local for one riding a carriage — and resolved at draw time. Interpolating
     * first and resolving second is what keeps a carriage puppet locked to its floor: the lerp
     * handles the puppet's own movement, and the carriage's own interpolated pose handles the
     * train's.</p>
     */
    public static final class Puppet {

        private final Entity model;
        private PortalPuppetsPacket.Entry entry;

        private double prevX, prevY, prevZ;
        private float prevYaw, prevHeadYaw, prevPitch;

        private Puppet(Entity model, PortalPuppetsPacket.Entry entry) {
            this.model = model;
            this.entry = entry;
            this.prevX = entry.x();
            this.prevY = entry.y();
            this.prevZ = entry.z();
            this.prevYaw = entry.yaw();
            this.prevHeadYaw = entry.headYaw();
            this.prevPitch = entry.pitch();
        }

        public Entity model() {
            return model;
        }

        public PortalPuppetsPacket.Entry entry() {
            return entry;
        }

        public double lerpX(float partialTick) {
            return Mth.lerp(partialTick, prevX, entry.x());
        }

        public double lerpY(float partialTick) {
            return Mth.lerp(partialTick, prevY, entry.y());
        }

        public double lerpZ(float partialTick) {
            return Mth.lerp(partialTick, prevZ, entry.z());
        }

        public float lerpYaw(float partialTick) {
            return Mth.rotLerp(partialTick, prevYaw, entry.yaw());
        }

        /** Advance to a new server state, keeping the old one as the interpolation baseline. */
        private void update(PortalPuppetsPacket.Entry next) {
            this.prevX = entry.x();
            this.prevY = entry.y();
            this.prevZ = entry.z();
            this.prevYaw = entry.yaw();
            this.prevHeadYaw = entry.headYaw();
            this.prevPitch = entry.pitch();
            this.entry = next;

            applyPose();
        }

        /**
         * Push the server's state onto the model.
         *
         * <p>Both the current and previous rotation fields are set, because the entity renderer
         * interpolates between them itself — leaving the previous ones behind would make a puppet's
         * head snap round on the frame after a snapshot and drift back over the next.</p>
         */
        private void applyPose() {
            model.setYRot(entry.yaw());
            model.yRotO = prevYaw;
            model.setXRot(entry.pitch());
            model.xRotO = prevPitch;

            if (model instanceof LivingEntity living) {
                living.yBodyRot = entry.yaw();
                living.yBodyRotO = prevYaw;
                living.yHeadRot = entry.headYaw();
                living.yHeadRotO = prevHeadYaw;

                living.setItemSlot(EquipmentSlot.MAINHAND, entry.mainHand());
                living.setItemSlot(EquipmentSlot.HEAD, entry.head());
                living.setItemSlot(EquipmentSlot.CHEST, entry.chest());
                living.setItemSlot(EquipmentSlot.LEGS, entry.legs());
                living.setItemSlot(EquipmentSlot.FEET, entry.feet());

                // Limb swing, driven from how far the source actually moved. Nothing ticks this
                // entity, so the animation state that a normal entity accumulates in its own tick
                // has to be advanced here or the puppet slides about with its legs together.
                float moved = (float) Math.sqrt(
                    sqr(entry.x() - prevX) + sqr(entry.z() - prevZ));
                living.walkAnimation.update(Math.min(moved * 4.0F, 1.0F), 0.4F);
            }

            // The model reads the pose, not the shift flag — Entity.isCrouching() asks whether the
            // pose is CROUCHING, so setting only the key-down flag leaves a crouching source's
            // puppet standing bolt upright beside them. Both are set: the flag is what some
            // renderers and layers consult, the pose is what bends the model.
            model.setShiftKeyDown(entry.crouching());
            model.setPose(entry.crouching() ? Pose.CROUCHING : Pose.STANDING);
            model.tickCount++;
        }

        private static double sqr(double v) {
            return v * v;
        }
    }

    /** Everything currently being shown, for the renderer to walk. */
    public static Iterable<Puppet> all() {
        return PUPPETS.values();
    }

    public static boolean isEmpty() {
        return PUPPETS.isEmpty();
    }

    /**
     * Take a server snapshot: update the puppets it names, create the ones it introduces, and drop
     * the ones it leaves out.
     *
     * <p>An empty snapshot is meaningful, not a no-op — it is how the server says the corridor has
     * emptied.</p>
     */
    public static void applySnapshot(PortalPuppetsPacket packet) {
        ticksSinceSnapshot = 0;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            discardAll("no level");
            return;
        }

        Set<Integer> present = new HashSet<>();

        for (PortalPuppetsPacket.Entry entry : packet.entries()) {
            present.add(entry.key());

            Puppet existing = PUPPETS.get(entry.key());
            if (existing != null) {
                existing.update(entry);
                continue;
            }

            Entity model = createModel(level, entry);
            if (model == null) continue;

            Puppet puppet = new Puppet(model, entry);
            puppet.applyPose();
            PUPPETS.put(entry.key(), puppet);

            LOGGER.info("[DungeonTrain] Portal puppet model created: key={} {} in {} space",
                entry.key(), describe(entry), entry.isPlotSpace() ? "CARRIAGE/plot" : "TWIN/world");
        }

        // Anything the snapshot did not mention has gone. Collected first — removing while iterating
        // the map the loop is reading would be a concurrent modification.
        List<Integer> gone = new ArrayList<>();
        for (Integer key : PUPPETS.keySet()) {
            if (!present.contains(key)) gone.add(key);
        }
        for (Integer key : gone) {
            Puppet puppet = PUPPETS.remove(key);
            LOGGER.info("[DungeonTrain] Portal puppet model discarded: key={} {}",
                key, describe(puppet.entry()));
        }
    }

    /** The render model for a puppet, or {@code null} if this client cannot build one. */
    private static Entity createModel(ClientLevel level, PortalPuppetsPacket.Entry entry) {
        if (entry.isPlayer()) {
            return new PortalPuppetPlayer(level, entry.sourceId(), entry.name());
        }

        EntityType<?> type = entry.entityType();
        if (type == null) {
            LOGGER.warn("[DungeonTrain] Portal puppet for unknown entity type {} — not drawn",
                entry.typeId());
            return null;
        }

        Entity model = type.create(level);
        if (model == null) return null;

        model.noPhysics = true;
        model.setNoGravity(true);
        model.setSilent(true);
        // Only mobs have a baby form; for anything else the flag has no meaning and is ignored.
        if (model instanceof Mob mob) {
            mob.setBaby(entry.baby());
        }
        return model;
    }

    /**
     * Count ticks since the last snapshot, and clear if the server has gone quiet.
     *
     * <p>Cheap when idle: with nothing being shown, the counter is not even advanced.</p>
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (PUPPETS.isEmpty()) return;
        if (++ticksSinceSnapshot > STALE_TICKS) {
            discardAll("no snapshot for " + STALE_TICKS + " ticks");
        }
    }

    /** Drop everything on disconnect, so ids cannot leak into the next session. */
    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        discardAll("logged out");
    }

    private static void discardAll(String reason) {
        if (PUPPETS.isEmpty()) return;
        LOGGER.info("[DungeonTrain] Portal puppets cleared ({}): {} dropped", reason, PUPPETS.size());
        PUPPETS.clear();
        ticksSinceSnapshot = 0;
    }

    private static String describe(PortalPuppetsPacket.Entry entry) {
        return entry.isPlayer() ? "player " + entry.name() : String.valueOf(entry.typeId());
    }
}
