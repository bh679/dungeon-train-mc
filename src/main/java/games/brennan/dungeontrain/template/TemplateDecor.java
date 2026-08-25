package games.brennan.dungeontrain.template;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The hung decoration a builder puts on a template's walls — item frames, glow item frames and
 * paintings — captured into the template's NBT and put back on every stamp.
 *
 * <h2>Why this exists</h2>
 * A picture is an <b>entity</b>, not a block, and every editor's capture used to call
 * {@code fillFromWorld} with its {@code includeEntities} flag set false. So a builder who
 * decorated a carriage, a tunnel or a dimensional carriage saved the room and lost the decoration —
 * and symmetrically, every stamp path sets {@code setIgnoreEntities(true)}, so a template that did
 * carry them would put nothing back. Carriage <i>contents</i> templates were the one exception
 * ({@code CarriageContentsPlacer.captureTemplate}); this is that behaviour, made available to every
 * other template kind.
 *
 * <h2>Only three types</h2>
 * {@link #DECOR_TYPES} is deliberately narrow. Mobs already have their own per-cell variant sidecar
 * that rolls and spawns them, so capturing a mob here would spawn it twice; armor stands and end
 * crystals are the contents pass's business. What is left is exactly the inert, wall-hung
 * decoration that nothing else in the mod ever puts back.
 *
 * <h2>Two coordinate frames</h2>
 * {@link #spawn} places at {@code origin + local}, which is right wherever the blocks it decorates
 * stay put — an editor plot, a tunnel, a portal room. A <b>carriage</b> is different: its blocks are
 * stamped in the world only to be lifted into a Sable sub-level the same tick, so decor spawned at
 * stamp time would be left standing on the tracks. There the caller passes the carriage's shipyard
 * origin from the deferred entity pass instead, exactly as the contents pass does.
 *
 * <h2>Ownership marks</h2>
 * {@code mark} is how a caller claims what it spawned. It is not optional bookkeeping: a carriage's
 * decor must carry {@code CarriageContentsPlacer.contentsTagFor} or
 * {@code TrainStaticContentsCarrier} will not move it with the train, and a portal room's must carry
 * {@code PortalRoomMobs}' pair/tile mark or the tiling window's reap cannot tell one copy's frames
 * from its neighbour's.
 */
public final class TemplateDecor {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The entity types a template carries. See the class javadoc — this is decoration nothing else
     * in the mod re-places, not "every entity in the box".
     */
    public static final Set<String> DECOR_TYPES = Set.of(
        "minecraft:item_frame",
        "minecraft:glow_item_frame",
        "minecraft:painting");

    private TemplateDecor() {}

    // ---- capture ----

    /**
     * {@link StructureTemplate#fillFromWorld} with the decoration kept.
     *
     * <p>Drop-in for the {@code includeEntities = false} call every editor used to make: entities are
     * pulled in, then everything that is not {@link #DECOR_TYPES} is filtered back out, so a villager
     * pacing an editor plot is not baked into the saved template.</p>
     *
     * @param voidBlock the block {@code fillFromWorld} treats as "not part of this template"
     *                  ({@code STRUCTURE_VOID} or {@code AIR}, per the caller's existing choice)
     */
    public static StructureTemplate capture(ServerLevel level, BlockPos origin, Vec3i size,
                                            @Nullable Block voidBlock) {
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, origin, size, /*includeEntities*/ true, voidBlock);
        return keepOnlyDecor(level, template);
    }

    /**
     * Strip every entity that is not decoration out of {@code template}.
     *
     * <p>Through an NBT round-trip rather than the private {@code entities} field — the same idiom
     * {@code StageBlockReplacer} uses to rewrite a palette. Returns {@code template} itself when its
     * entity list is already clean, so the common case pays nothing.</p>
     */
    public static StructureTemplate keepOnlyDecor(ServerLevel level, StructureTemplate template) {
        CompoundTag tag = template.save(new CompoundTag());
        if (!filterEntities(tag)) return template;

        HolderGetter<Block> blocks = level.holderLookup(Registries.BLOCK);
        StructureTemplate filtered = new StructureTemplate();
        filtered.load(blocks, tag);
        return filtered;
    }

    /**
     * Remove non-decor entries from a saved template's {@code entities} list, in place.
     *
     * @return whether anything was removed — i.e. whether {@code tag} needs reloading
     */
    static boolean filterEntities(CompoundTag tag) {
        if (!tag.contains("entities", Tag.TAG_LIST)) return false;
        ListTag entities = tag.getList("entities", Tag.TAG_COMPOUND);
        ListTag kept = new ListTag();
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag entry = entities.getCompound(i);
            if (isDecor(entry)) kept.add(entry);
        }
        if (kept.size() == entities.size()) return false;
        tag.put("entities", kept);
        return true;
    }

    /** Whether one saved-template {@code entities} entry is decoration this class owns. */
    static boolean isDecor(CompoundTag entry) {
        return DECOR_TYPES.contains(entry.getCompound("nbt").getString("id"));
    }

    // ---- spawn ----

    /**
     * Put a template's decoration back at {@code origin}, with no rotation or mirror.
     *
     * @param mark applied to each spawned entity before it is added — the caller's ownership claim
     * @return how many were spawned
     */
    public static int spawn(ServerLevelAccessor level, BlockPos origin, StructureTemplate template,
                            @Nullable Consumer<Entity> mark) {
        return spawn(level, origin, template, null, mark);
    }

    /**
     * Put a template's decoration back, at {@code origin + local}, transformed by {@code settings}.
     *
     * <p>Hand-rolled rather than handed to {@code placeInWorld} by clearing
     * {@code setIgnoreEntities}, for the reason {@code CarriageContentsPlacer} documents: vanilla's
     * entity pass proved unreliable at shipyard coordinates, and every stamp site in this mod already
     * relies on {@code setIgnoreEntities(true)} to keep its own processor chain in charge. The
     * transform itself is vanilla's, step for step ({@code StructureTemplate.placeEntities}) — a
     * carriage part stamped {@code Mirror.FRONT_BACK} onto the other side of the cart must take its
     * pictures across with it, facing the way the wall now faces.</p>
     *
     * @param settings the placement's rotation/mirror, or null for an untransformed stamp
     * @param mark     applied to each spawned entity before it is added — the caller's ownership claim
     * @return how many were spawned
     */
    public static int spawn(ServerLevelAccessor level, BlockPos origin, StructureTemplate template,
                            @Nullable StructurePlaceSettings settings, @Nullable Consumer<Entity> mark) {
        CompoundTag saved = template.save(new CompoundTag());
        if (!saved.contains("entities", Tag.TAG_LIST)) return 0;
        ListTag entries = saved.getList("entities", Tag.TAG_COMPOUND);
        if (entries.isEmpty()) return 0;

        Mirror mirror = settings == null ? Mirror.NONE : settings.getMirror();
        Rotation rotation = settings == null ? Rotation.NONE : settings.getRotation();
        BlockPos pivot = settings == null ? BlockPos.ZERO : settings.getRotationPivot();
        // Vanilla clips entities to the same box it clips blocks to. A pillar's stairs template is
        // stamped through a terrain-clipped box, and a picture on the half that was cut away would
        // otherwise be left hanging in open air.
        BoundingBox clip = settings == null ? null : settings.getBoundingBox();

        int spawned = 0;
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!isDecor(entry)) continue;
            try {
                if (spawnOne(level, origin, entry, mirror, rotation, pivot, clip, mark)) spawned++;
            } catch (Throwable t) {
                LOGGER.warn("[DungeonTrain] template decor: spawn threw for id={} at origin={}: {}",
                    entry.getCompound("nbt").getString("id"), origin, t.toString());
            }
        }
        return spawned;
    }

    private static boolean spawnOne(ServerLevelAccessor level, BlockPos origin, CompoundTag entry,
                                    Mirror mirror, Rotation rotation, BlockPos pivot,
                                    @Nullable BoundingBox clip, @Nullable Consumer<Entity> mark) {
        if (!entry.contains("nbt", Tag.TAG_COMPOUND) || !entry.contains("pos", Tag.TAG_LIST)) return false;
        ListTag local = entry.getList("pos", Tag.TAG_DOUBLE);
        if (local.size() != 3) return false;

        Vec3 at = StructureTemplate
            .transform(new Vec3(local.getDouble(0), local.getDouble(1), local.getDouble(2)),
                mirror, rotation, pivot)
            .add(origin.getX(), origin.getY(), origin.getZ());
        BlockPos anchor = anchorOf(entry, mirror, rotation, pivot, origin);
        if (clip != null && !clip.isInside(anchor == null ? BlockPos.containing(at) : anchor)) return false;

        CompoundTag nbt = rebase(entry, at, anchor);
        Optional<Entity> created = EntityType.create(nbt, level.getLevel());
        if (created.isEmpty()) {
            LOGGER.debug("[DungeonTrain] template decor: could not create id={}", nbt.getString("id"));
            return false;
        }
        Entity entity = created.get();
        // Vanilla's own order in StructureTemplate.placeEntities: rotate, then fold the mirror's yaw
        // delta in, then move. A hanging entity reads its facing off its own rotate/mirror overrides,
        // so doing this before the move is what turns a picture to face the mirrored wall.
        float yaw = entity.rotate(rotation);
        yaw += entity.mirror(mirror) - entity.getYRot();
        entity.moveTo(at.x, at.y, at.z, yaw, entity.getXRot());
        if (mark != null) mark.accept(entity);
        if (!level.addFreshEntity(entity)) {
            LOGGER.debug("[DungeonTrain] template decor: level rejected {} at {}",
                nbt.getString("id"), at);
            return false;
        }
        return true;
    }

    /**
     * The world block a hanging entry is nailed to, or null when the entry records no anchor.
     *
     * <p>Two position fields, not one. {@code Pos} is where the entity stands; {@code TileX/Y/Z} is
     * the block a hanging entity is nailed to, and leaving that at the authoring world's coordinates
     * is how an item frame ends up invisible or popping off on the first block update.</p>
     */
    @Nullable
    static BlockPos anchorOf(CompoundTag entry, Mirror mirror, Rotation rotation,
                             BlockPos pivot, BlockPos origin) {
        // A LIST of three ints, not an int array: StructureTemplate.save writes blockPos through its
        // own newIntegerList, the same shape it uses for pos. Reading it as TAG_INT_ARRAY silently
        // matched nothing, so every anchor came back null and rebase skipped TileX/Y/Z below —
        // leaving each picture nailed to the coordinates it was authored at, which only shows once a
        // template is stamped somewhere other than where it was captured.
        if (!entry.contains("blockPos", Tag.TAG_LIST)) return null;
        ListTag local = entry.getList("blockPos", Tag.TAG_INT);
        if (local.size() != 3) return null;
        return StructureTemplate
            .transform(new BlockPos(local.getInt(0), local.getInt(1), local.getInt(2)),
                mirror, rotation, pivot)
            .offset(origin);
    }

    /**
     * One entry's entity NBT, moved to {@code at} / {@code anchor} and given a fresh identity.
     *
     * <p>A template stamped at N sites cannot hand every copy the same UUID — MC silently drops the
     * duplicates, so only the first stamp would show its decor.</p>
     */
    static CompoundTag rebase(CompoundTag entry, Vec3 at, @Nullable BlockPos anchor) {
        CompoundTag nbt = entry.getCompound("nbt").copy();

        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(at.x));
        pos.add(DoubleTag.valueOf(at.y));
        pos.add(DoubleTag.valueOf(at.z));
        nbt.put("Pos", pos);
        // Stale motion would make a freshly stamped frame lurch; there is no meaningful velocity to
        // carry across a save/load anyway.
        nbt.remove("Motion");
        nbt.putUUID("UUID", UUID.randomUUID());

        if (anchor != null && nbt.contains("TileX", Tag.TAG_INT)) {
            nbt.putInt("TileX", anchor.getX());
            nbt.putInt("TileY", anchor.getY());
            nbt.putInt("TileZ", anchor.getZ());
        }
        return nbt;
    }

    // ---- discard ----

    /**
     * Take the decoration standing in a stamp's footprint away and hang the template's own in its
     * place — the pairing every stamp site wants, kept together so no caller can do one half.
     *
     * <p>The discard is not optional. A stamp is not a move: a plot restamped, or a portal copy
     * re-entering the tiling window, would otherwise hang a second frame through the first, once per
     * pass, for as long as anyone keeps walking.</p>
     *
     * @return how many were spawned
     */
    public static int replace(ServerLevelAccessor level, BlockPos origin, StructureTemplate template,
                              @Nullable StructurePlaceSettings settings, @Nullable Consumer<Entity> mark) {
        discard(level, origin, template, settings);
        return spawn(level, origin, template, settings, mark);
    }

    /**
     * Take away the decoration standing in the footprint {@code template} is about to be stamped
     * into at {@code origin}, under {@code settings}' own mirror and rotation.
     *
     * <p>The transform matters: {@code Mirror.FRONT_BACK} around the default zero pivot puts the
     * stamp's blocks at <b>negative</b> local X, so a box measured forwards from {@code origin} would
     * clear the wrong side of the stamp entirely.</p>
     *
     * <p>Scoped to {@link #DECOR_TYPES}, so a player's pet, a dropped item or an authored mob
     * standing in the same box is left alone — the callers that want those gone have their own,
     * wider sweeps ({@code EditorPlotEntityClearer}, {@code clearIntruders}).</p>
     *
     * @return how many were removed
     */
    public static int discard(ServerLevelAccessor level, BlockPos origin, StructureTemplate template,
                              @Nullable StructurePlaceSettings settings) {
        Vec3i size = template.getSize();
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) return 0;
        Mirror mirror = settings == null ? Mirror.NONE : settings.getMirror();
        Rotation rotation = settings == null ? Rotation.NONE : settings.getRotation();
        BlockPos pivot = settings == null ? BlockPos.ZERO : settings.getRotationPivot();

        BlockPos a = StructureTemplate.transform(BlockPos.ZERO, mirror, rotation, pivot).offset(origin);
        BlockPos b = StructureTemplate
            .transform(new BlockPos(size.getX() - 1, size.getY() - 1, size.getZ() - 1),
                mirror, rotation, pivot)
            .offset(origin);
        AABB box = new AABB(
            Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
            Math.max(a.getX(), b.getX()) + 1,
            Math.max(a.getY(), b.getY()) + 1,
            Math.max(a.getZ(), b.getZ()) + 1);
        // A template being stamped into a box smaller than itself — which is what a portal room's
        // resize looks like before the author saves again — must not clear the plot next door.
        BoundingBox clip = settings == null ? null : settings.getBoundingBox();
        if (clip != null) {
            box = box.intersect(new AABB(
                clip.minX(), clip.minY(), clip.minZ(),
                clip.maxX() + 1, clip.maxY() + 1, clip.maxZ() + 1));
            if (box.getXsize() <= 0 || box.getYsize() <= 0 || box.getZsize() <= 0) return 0;
        }

        List<Entity> doomed = level.getEntities((Entity) null, box, TemplateDecor::isDecor);
        for (Entity entity : doomed) entity.discard();
        return doomed.size();
    }

    /** Whether a live entity is one of the decoration types this class owns. */
    public static boolean isDecor(Entity entity) {
        return entity != null
            && DECOR_TYPES.contains(EntityType.getKey(entity.getType()).toString());
    }
}
