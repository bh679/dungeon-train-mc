package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.EditorMobGhostsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * World-space overlay that stands a mob on every variant cell whose pool can roll one — the
 * client half of the editor's <b>Mobs | Blocks</b> setting ({@code FrozenMobs}).
 *
 * <p>An egg placed under Blocks leaves a frozen mob standing on its cell; a mob authored as a
 * <i>variant</i> leaves nothing visible at all, because the cell holds the empty-placeholder sentinel
 * (or the base block) and the mob exists only in the sidecar. This draws that mob the same way the
 * frozen one looks — the real model, standing still, facing {@code -Z} — so the two authoring paths
 * read alike in the plot.</p>
 *
 * <p><b>A dummy entity, not a spawned one.</b> The mob is created client-side from its id (and the
 * entry's NBT, so a named or armoured entry shows as authored), never added to the level, and pushed
 * through {@link EntityRenderDispatcher#render} the way the spawner block renders its occupant. It
 * cannot be hit or targeted; breaking the cell is what removes the variant, and the server's next
 * snapshot removes the ghost. Dummies are cached per {@code id + nbt} so a floor of forty zombies is
 * one entity rendered forty times.</p>
 *
 * <p>The snapshot arrives from {@code VariantOverlayRenderer.pushMobGhostsSnapshot}; an empty packet
 * (setting Live, no plot, no mob entries) clears it. Distance-culled like the door ghosts.</p>
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class EditorMobGhostRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How far a ghost is drawn from the camera, in chunks. Matches the door ghosts' cull. */
    private static final int MAX_DISTANCE_CHUNKS = 4;
    private static final double MAX_DISTANCE_SQ =
        (MAX_DISTANCE_CHUNKS * 16.0) * (MAX_DISTANCE_CHUNKS * 16.0);

    /** Dummy-entity cache bound; a plot rarely uses more than a handful of distinct mobs. */
    private static final int MAX_CACHED_DUMMIES = 64;

    /** Most recent snapshot from the server. Empty → no-op. */
    private static final List<EditorMobGhostsPacket.Ghost> CACHE = new ArrayList<>();

    /** Dummy entities by {@code entityId + '|' + nbt}; null values remember an id that failed to create. */
    private static final Map<String, Optional<Entity>> DUMMIES = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Optional<Entity>> eldest) {
            return size() > MAX_CACHED_DUMMIES;
        }
    };

    private EditorMobGhostRenderer() {}

    /** Called from the packet handler on the client thread. */
    public static synchronized void applySnapshot(EditorMobGhostsPacket packet) {
        CACHE.clear();
        if (packet.isEmpty()) return;
        CACHE.addAll(packet.ghosts());
    }

    /** Drop the snapshot and the dummies on world quit — a dummy holds the old {@link ClientLevel}. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        synchronized (EditorMobGhostRenderer.class) {
            CACHE.clear();
            DUMMIES.clear();
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        List<EditorMobGhostsPacket.Ghost> snapshot;
        synchronized (EditorMobGhostRenderer.class) {
            if (CACHE.isEmpty()) return;
            snapshot = new ArrayList<>(CACHE);
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        dispatcher.setRenderShadow(false);
        try {
            for (EditorMobGhostsPacket.Ghost ghost : snapshot) {
                BlockPos cell = ghost.cell();
                if (cell.distToCenterSqr(cam.x, cam.y, cam.z) > MAX_DISTANCE_SQ) continue;
                Entity dummy = dummyFor(level, ghost.entityId(), ghost.nbt());
                if (dummy == null) continue;
                int light = LevelRenderer.getLightColor(level, cell);
                try {
                    dispatcher.render(dummy, cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5,
                        0.0F, partialTick, ps, buffer, light);
                } catch (Throwable t) {
                    // One renderer that cannot cope with a level-less dummy must not take the frame
                    // down; remember the failure so it is not retried every frame.
                    LOGGER.warn("[DungeonTrain] mob ghost: renderer threw for {}: {}", ghost.entityId(), t.toString());
                    synchronized (EditorMobGhostRenderer.class) {
                        DUMMIES.put(key(ghost.entityId(), ghost.nbt()), Optional.empty());
                    }
                }
            }
        } finally {
            dispatcher.setRenderShadow(true);
            buffer.endBatch();
            ps.popPose();
        }
    }

    /** The cached dummy for {@code id + nbt}, created on first use; null when the id cannot be built. */
    private static @Nullable Entity dummyFor(ClientLevel level, String entityId, @Nullable CompoundTag nbt) {
        String key = key(entityId, nbt);
        synchronized (EditorMobGhostRenderer.class) {
            Optional<Entity> cached = DUMMIES.get(key);
            if (cached != null) {
                // A dummy from an earlier world would render against a dead level — rebuild it.
                if (cached.isPresent() && cached.get().level() == level) return cached.get();
                if (cached.isEmpty()) return null;
            }
            Entity created = create(level, entityId, nbt);
            DUMMIES.put(key, Optional.ofNullable(created));
            return created;
        }
    }

    private static @Nullable Entity create(ClientLevel level, String entityId, @Nullable CompoundTag nbt) {
        ResourceLocation id = ResourceLocation.tryParse(entityId);
        if (id == null) return null;
        Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
        if (type.isEmpty()) return null;
        try {
            Entity entity = type.get().create(level);
            if (entity == null) return null;
            if (nbt != null && !nbt.isEmpty()) {
                CompoundTag tag = nbt.copy();
                tag.remove("Pos");
                tag.remove("Motion");
                tag.remove("Rotation");
                tag.remove("UUID");
                entity.load(tag);
            }
            entity.setPos(0, 0, 0);
            entity.setYRot(0.0F);
            entity.setXRot(0.0F);
            if (entity instanceof LivingEntity living) {
                living.setYBodyRot(0.0F);
                living.setYHeadRot(0.0F);
                living.yBodyRotO = 0.0F;
                living.yHeadRotO = 0.0F;
            }
            entity.yRotO = 0.0F;
            entity.xRotO = 0.0F;
            return entity;
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] mob ghost: could not build dummy {}: {}", entityId, t.toString());
            return null;
        }
    }

    private static String key(String entityId, @Nullable CompoundTag nbt) {
        return nbt == null ? entityId : entityId + "|" + nbt;
    }
}
