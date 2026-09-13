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

    static final Logger LOGGER = LogUtils.getLogger();

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
     * Ticks a server pose is reached over, once it arrives. Vanilla's own number: every entity a
     * client tracks is moved with {@code lerpTo(..., 3)}, so this is what makes a puppet cross the
     * room at the same pace, with the same lag, as the entity it stands for.
     */
    private static final int LERP_STEPS = 3;

    /**
     * One puppet: the model to draw, and the states to interpolate between.
     *
     * <p><b>A packet sets a target; a tick moves.</b> The server's pose is not applied when it
     * arrives — that happens at some arbitrary point inside a client tick, and interpolation
     * measures from the tick's start. Shifting the endpoints mid-tick made the puppet leap forward
     * by the fraction of the tick already elapsed and then, when the tick rolled over and the
     * fraction reset, leap back to its previous position before covering the same ground again:
     * a stutter on every moving puppet, and none on a still one. So a packet only records where
     * the puppet should be, and {@link #tick} — once per client tick, when the fraction resets —
     * moves the previous and current positions the way a vanilla entity's own tick does, spread
     * over {@link #LERP_STEPS} ticks the way vanilla spreads every tracked entity's moves. The
     * clone then lands on each tick's position at the same instant the original does.</p>
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

        private double prevX, prevY, prevZ, curX, curY, curZ;
        private float prevYaw, prevHeadYaw, prevPitch, curYaw, curHeadYaw, curPitch;
        /** Ticks left to reach {@link #entry}'s pose; zero when standing on it. */
        private int lerpSteps;

        private Puppet(Entity model, PortalPuppetsPacket.Entry entry) {
            this.model = model;
            this.entry = entry;
            this.prevX = this.curX = entry.x();
            this.prevY = this.curY = entry.y();
            this.prevZ = this.curZ = entry.z();
            this.prevYaw = this.curYaw = entry.yaw();
            this.prevHeadYaw = this.curHeadYaw = entry.headYaw();
            this.prevPitch = this.curPitch = entry.pitch();
        }

        public Entity model() {
            return model;
        }

        public PortalPuppetsPacket.Entry entry() {
            return entry;
        }

        public double lerpX(float partialTick) {
            return Mth.lerp(partialTick, prevX, curX);
        }

        public double lerpY(float partialTick) {
            return Mth.lerp(partialTick, prevY, curY);
        }

        public double lerpZ(float partialTick) {
            return Mth.lerp(partialTick, prevZ, curZ);
        }

        public float lerpYaw(float partialTick) {
            return Mth.rotLerp(partialTick, prevYaw, curYaw);
        }

        /**
         * Take a new server state as the target to move towards.
         *
         * <p>{@code next} may be any shape. The entry held here is always a full one — a pose entry
         * is folded onto it, a held entry leaves it as it was — so the renderer and the model always
         * have the complete picture whatever the wire carried this tick. Only a full entry re-dresses
         * the model: the five equipment slots and the synched-data copy are the costly part of a
         * snapshot, and a pose or held entry is the server saying they have not changed. Nothing
         * here moves the puppet; see {@link #tick}.</p>
         */
        private void update(PortalPuppetsPacket.Entry next) {
            if (next.isFull()) {
                this.entry = next;
                applyAppearance();
            } else if (next.hasPose()) {
                this.entry = entry.withPose(next);
            }
            if (next.hasPose()) this.lerpSteps = LERP_STEPS;
        }

        /**
         * One client tick: the previous state becomes the current one, and the current one moves a
         * step towards the target — {@code LivingEntity.tick}'s own arithmetic.
         */
        private void tick() {
            this.prevX = curX;
            this.prevY = curY;
            this.prevZ = curZ;
            this.prevYaw = curYaw;
            this.prevHeadYaw = curHeadYaw;
            this.prevPitch = curPitch;

            if (lerpSteps > 0) {
                double t = 1.0 / lerpSteps;
                this.curX = Mth.lerp(t, curX, entry.x());
                this.curY = Mth.lerp(t, curY, entry.y());
                this.curZ = Mth.lerp(t, curZ, entry.z());
                this.curYaw = Mth.rotLerp((float) t, curYaw, entry.yaw());
                this.curHeadYaw = Mth.rotLerp((float) t, curHeadYaw, entry.headYaw());
                this.curPitch = Mth.lerp((float) t, curPitch, entry.pitch());
                this.lerpSteps--;
            }

            applyPose();
        }

        /**
         * Push the current state onto the model.
         *
         * <p>Both the current and previous rotation fields are set, because the entity renderer
         * interpolates between them itself — leaving the previous ones behind would make a puppet's
         * head snap round on the frame after a tick and drift back over the next.</p>
         */
        private void applyPose() {
            model.setYRot(curYaw);
            model.yRotO = prevYaw;
            model.setXRot(curPitch);
            model.xRotO = prevPitch;

            if (model instanceof LivingEntity living) {
                living.yBodyRot = curYaw;
                living.yBodyRotO = prevYaw;
                living.yHeadRot = curHeadYaw;
                living.yHeadRotO = prevHeadYaw;

                // Limb swing, driven from how far the puppet moved this tick. Nothing ticks this
                // entity, so the animation state that a normal entity accumulates in its own tick
                // has to be advanced here or the puppet slides about with its legs together.
                float moved = (float) Math.sqrt(sqr(curX - prevX) + sqr(curZ - prevZ));
                living.walkAnimation.update(Math.min(moved * 4.0F, 1.0F), 0.4F);
            }

            model.tickCount++;
        }

        /** Dress the model: equipment and synched data, the parts a full entry carries. */
        private void applyAppearance() {
            if (model instanceof LivingEntity living) {
                living.setItemSlot(EquipmentSlot.MAINHAND, entry.mainHand());
                living.setItemSlot(EquipmentSlot.HEAD, entry.head());
                living.setItemSlot(EquipmentSlot.CHEST, entry.chest());
                living.setItemSlot(EquipmentSlot.LEGS, entry.legs());
                living.setItemSlot(EquipmentSlot.FEET, entry.feet());

                // The server owns both countdowns and sends every step of them, so nothing here
                // decrements: the renderer reads them for the red overlay and the fall-over.
                living.hurtTime = entry.hurtTime();
                living.deathTime = entry.deathTime();
            }
            applyData();
        }

        /**
         * Copy the source's synched data onto the model — its appearance, wholesale.
         *
         * <p>This is what makes the puppet the same creature and not just the same species: a snow
         * villager's biome type, a sheep's colour, a baby zombie, a charged creeper, a named mob's
         * nameplate, a crouching player's pose. All of it is synched data, so copying the lot is
         * both more faithful and simpler than mirroring the handful of fields anyone thought of —
         * and it works for entity types this code has never heard of.</p>
         *
         * <p>Guarded because the data is applied to an entity built from a type id off the wire.
         * A mismatched or truncated field would otherwise throw inside the render loop; a puppet
         * that looks wrong is a far better outcome than a client that stops drawing the world.</p>
         */
        private void applyData() {
            if (entry.data().isEmpty()) return;
            try {
                model.getEntityData().assignValues(entry.data());
            } catch (RuntimeException e) {
                LOGGER.warn("[DungeonTrain] Portal puppet data rejected for {} (key={}): {}",
                    entry.typeId(), entry.key(), e.toString());
            }
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

            // A pose or held entry for a puppet this client does not hold: the server's memory of
            // us is ahead of us — most likely we dropped everything on the staleness timeout during
            // a lag spike. Nothing can be built from it. The server re-describes every puppet on a
            // refresh period matched to that timeout, so the full entry is at most two seconds out.
            // Counted as present above so the gap does not also discard anything else.
            if (!entry.isFull()) {
                LOGGER.debug("[DungeonTrain] Portal puppet {} arrived before its description — waiting",
                    entry.key());
                continue;
            }

            Entity model = createModel(level, entry);
            if (model == null) continue;

            Puppet puppet = new Puppet(model, entry);
            puppet.applyPose();
            puppet.applyAppearance();
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
        return model;
    }

    /**
     * Move every puppet one tick towards its target, and clear if the server has gone quiet.
     *
     * <p>This is where a puppet actually moves — see {@link Puppet}. Cheap when idle: with nothing
     * being shown, the counter is not even advanced.</p>
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (PUPPETS.isEmpty()) return;
        if (++ticksSinceSnapshot > STALE_TICKS) {
            discardAll("no snapshot for " + STALE_TICKS + " ticks");
            return;
        }
        for (Puppet puppet : PUPPETS.values()) puppet.tick();
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
