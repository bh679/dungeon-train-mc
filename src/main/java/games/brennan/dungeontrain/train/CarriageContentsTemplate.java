package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriageContentsVariantBlocks;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.train.CarriageContents.ContentsType;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Interior-contents blueprint — stamps the {@link CarriageContents} template
 * into the interior volume of a carriage at a given origin. Parallel to
 * {@link CarriageTemplate} but scoped to the {@code (length-2) × (height-2) × (width-2)}
 * interior region; the shell floor/walls/ceiling are placed separately by
 * {@link CarriageTemplate#placeAt} and are not affected.
 *
 * <p>{@link #placeAt(ServerLevel, BlockPos, CarriageContents, CarriageDims)}
 * first tries an NBT-backed template from {@link CarriageContentsStore}; if
 * none is saved (or the file's footprint doesn't match the world's current
 * interior dims), the {@code default} built-in falls back to a hardcoded
 * generator that places a single stone pressure plate at the interior floor
 * centre, and custom contents place nothing.
 */
public final class CarriageContentsTemplate {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CarriageContentsTemplate() {}

    /**
     * Interior size (x=length-2, y=height-2, z=width-2) as a {@link Vec3i}.
     * Returns a size where any component may be &lt;= 0 when the shell dims are
     * at their minimums; callers should treat a zero-or-negative dimension as
     * "no interior volume" and skip placement.
     */
    public static Vec3i interiorSize(CarriageDims dims) {
        return new Vec3i(
            Math.max(0, dims.length() - 2),
            Math.max(0, dims.height() - 2),
            Math.max(0, dims.width() - 2)
        );
    }

    /**
     * Interior origin = {@code carriageOrigin.offset(1, 1, 1)}. This is the
     * minimum corner of the inside volume — one block in from each perimeter
     * wall and one block above the floor.
     */
    public static BlockPos interiorOrigin(BlockPos carriageOrigin) {
        return carriageOrigin.offset(1, 1, 1);
    }

    /**
     * Stamp the interior contents for {@code contents} at the given carriage
     * {@code carriageOrigin} (shell's min corner). Only places blocks inside
     * the interior volume — the shell's floor/walls/ceiling placed by
     * {@link CarriageTemplate#placeAt} are untouched.
     */
    public static void placeAt(ServerLevel level, BlockPos carriageOrigin, CarriageContents contents, CarriageDims dims) {
        Vec3i size = interiorSize(dims);
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            // Carriage at its minimum dims has zero or negative interior
            // along at least one axis — no room for contents.
            return;
        }
        BlockPos origin = interiorOrigin(carriageOrigin);

        Optional<StructureTemplate> stored = CarriageContentsStore.get(level, contents, size);
        if (stored.isPresent()) {
            stampTemplate(level, origin, stored.get());
            LOGGER.info("[DungeonTrain] Placed contents {} at {} source=stored", contents.id(), origin);
            return;
        }
        if (contents instanceof CarriageContents.Builtin b && b.type() == ContentsType.DEFAULT) {
            legacyPlaceDefault(level, origin, size);
            LOGGER.info("[DungeonTrain] Placed contents {} at {} source=legacy", contents.id(), origin);
            return;
        }
        LOGGER.warn("[DungeonTrain] No contents placed — contents={} origin={} reason=no-nbt-no-fallback. Check {} exists and matches interior size {}x{}x{}.",
            contents.id(), origin, CarriageContentsStore.fileFor(contents),
            size.getX(), size.getY(), size.getZ());
    }

    /**
     * Spawn-time variant-aware overload — stamps the base contents, then
     * overlays the per-position variants picked deterministically from
     * {@code (seed, carriageIndex, localPos)}. Editor calls go through the
     * 4-arg {@link #placeAt(ServerLevel, BlockPos, CarriageContents, CarriageDims)}
     * overload above so the author always sees the deterministic base, never
     * the random-pick view.
     */
    public static void placeAt(ServerLevel level, BlockPos carriageOrigin, CarriageContents contents,
                               CarriageDims dims, long seed, int carriageIndex) {
        placeAt(level, carriageOrigin, contents, dims);
        applyVariantBlocks(level, carriageOrigin, contents, dims, seed, carriageIndex);
    }

    /**
     * Overlay any {@link CarriageContentsVariantBlocks} entries for
     * {@code contents} onto the stamped interior. Each picked
     * {@link VariantState} is silent-placed at {@code interiorOrigin + localPos}
     * with the optional block-entity NBT applied. The empty-placeholder
     * sentinel resolves to AIR so authors can randomise "block or empty"
     * positions inside an interior.
     */
    private static void applyVariantBlocks(ServerLevel level, BlockPos carriageOrigin,
                                            CarriageContents contents, CarriageDims dims,
                                            long seed, int carriageIndex) {
        Vec3i size = interiorSize(dims);
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) return;
        CarriageContentsVariantBlocks sidecar = CarriageContentsVariantBlocks.loadFor(contents, size);
        if (sidecar.isEmpty()) return;

        BlockPos origin = interiorOrigin(carriageOrigin);
        for (var entry : sidecar.entries()) {
            VariantState picked = sidecar.resolve(entry.localPos(), seed, carriageIndex);
            if (picked == null) continue;
            BlockPos world = origin.offset(entry.localPos());
            if (CarriageVariantBlocks.isEmptyPlaceholder(picked.state())) {
                SilentBlockOps.setBlockSilent(level, world, Blocks.AIR.defaultBlockState());
            } else {
                net.minecraft.world.level.block.state.BlockState rotated =
                    games.brennan.dungeontrain.editor.RotationApplier.apply(
                        picked.state(), picked.rotation(),
                        entry.localPos(), seed, carriageIndex,
                        sidecar.lockIdAt(entry.localPos()));
                SilentBlockOps.setBlockSilent(level, world, rotated, picked.blockEntityNbt());
            }
        }
    }

    /**
     * Hardcoded fallback for the {@code default} built-in when no NBT is on
     * disk: a single {@link Blocks#STONE_PRESSURE_PLATE} at the floor centre
     * of the interior volume.
     *
     * <p>Pressure plates need a supporting block below them; the shell floor
     * at {@code interiorOrigin.below()} always provides it (the shell's floor
     * row is stamped by the shell template before contents are placed).</p>
     */
    private static void legacyPlaceDefault(ServerLevel level, BlockPos interiorOrigin, Vec3i interiorSize) {
        BlockState plate = Blocks.STONE_PRESSURE_PLATE.defaultBlockState();
        int dx = interiorSize.getX() / 2;
        int dz = interiorSize.getZ() / 2;
        BlockPos pos = interiorOrigin.offset(dx, 0, dz);
        SilentBlockOps.setBlockSilent(level, pos, plate);
    }

    private static void stampTemplate(ServerLevel level, BlockPos origin, StructureTemplate template) {
        // Place BLOCKS only — StructureTemplate's built-in entity placement
        // was unreliable in shipyard chunks (possibly due to VS's entity
        // section mixin timing), so we parse the entity list ourselves and
        // spawn each entity manually below.
        StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), 3);
        spawnEntitiesFromTemplate(level, origin, template);
    }

    /**
     * Manually iterate the template's saved entity list and spawn each entity
     * at {@code origin + localPos}. Bypasses {@link StructureTemplate}'s
     * built-in entity placement because it proved unreliable at shipyard
     * coordinates. Each entity's UUID is cleared before deserialisation so MC
     * assigns a fresh one — otherwise every carriage would try to spawn the
     * same-UUID armor stand and all but the first would drop silently.
     */
    private static void spawnEntitiesFromTemplate(ServerLevel level, BlockPos origin, StructureTemplate template) {
        CompoundTag saved = template.save(new CompoundTag());
        if (!saved.contains("entities", Tag.TAG_LIST)) {
            return;
        }
        ListTag entries = saved.getList("entities", Tag.TAG_COMPOUND);
        if (entries.isEmpty()) return;
        int spawned = 0;
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.contains("nbt", Tag.TAG_COMPOUND) || !entry.contains("pos", Tag.TAG_LIST)) continue;
            ListTag posList = entry.getList("pos", Tag.TAG_DOUBLE);
            if (posList.size() != 3) continue;
            double localX = posList.getDouble(0);
            double localY = posList.getDouble(1);
            double localZ = posList.getDouble(2);
            double worldX = origin.getX() + localX;
            double worldY = origin.getY() + localY;
            double worldZ = origin.getZ() + localZ;

            CompoundTag entityNbt = entry.getCompound("nbt").copy();
            // Fresh UUID so spawning the same template at N carriages doesn't
            // collide on the UUID index (MC silently drops duplicate UUIDs).
            entityNbt.putUUID("UUID", UUID.randomUUID());
            // Rewrite the Pos field to the new world coordinates. Paintings /
            // item frames also use this field for their hanging position.
            ListTag newPos = new ListTag();
            newPos.add(DoubleTag.valueOf(worldX));
            newPos.add(DoubleTag.valueOf(worldY));
            newPos.add(DoubleTag.valueOf(worldZ));
            entityNbt.put("Pos", newPos);
            // For hanging entities (paintings, item frames) the TileX/Y/Z fields
            // anchor the attached block. Offset those too if present.
            if (entityNbt.contains("TileX", Tag.TAG_INT) && entry.contains("blockPos", Tag.TAG_INT_ARRAY)) {
                int[] localBlock = entry.getIntArray("blockPos");
                if (localBlock.length == 3) {
                    entityNbt.putInt("TileX", origin.getX() + localBlock[0]);
                    entityNbt.putInt("TileY", origin.getY() + localBlock[1]);
                    entityNbt.putInt("TileZ", origin.getZ() + localBlock[2]);
                }
            }

            try {
                Optional<Entity> created = EntityType.create(entityNbt, level);
                if (created.isEmpty()) {
                    LOGGER.warn("[DungeonTrain] Contents: failed to create entity from nbt (id={})",
                        entityNbt.getString("id"));
                    continue;
                }
                Entity entity = created.get();
                entity.moveTo(worldX, worldY, worldZ, entity.getYRot(), entity.getXRot());
                if (level.addFreshEntity(entity)) {
                    spawned++;
                } else {
                    LOGGER.warn("[DungeonTrain] Contents: addFreshEntity rejected {} at ({},{},{})",
                        entity.getType().getDescriptionId(), worldX, worldY, worldZ);
                }
            } catch (Throwable t) {
                LOGGER.warn("[DungeonTrain] Contents: entity spawn threw for id={}: {}",
                    entityNbt.getString("id"), t.toString());
            }
        }
        if (spawned > 0) {
            LOGGER.info("[DungeonTrain] Contents: spawned {} entities at origin={} (template listed {})",
                spawned, origin, entries.size());
        }
    }

    /**
     * Capture the interior volume at {@code carriageOrigin} into a fresh
     * {@link StructureTemplate} — used by the contents editor save flow.
     * Includes entities (armor stands, paintings, item frames, etc.) so a
     * decorated interior round-trips through save/load. Air positions are
     * excluded so the saved NBT only describes placed blocks. An all-air
     * interior with no entities still saves as a zero-size template, which
     * {@link CarriageContentsStore#save} writes so the author can clear
     * contents explicitly.
     *
     * <p>The 4th {@code fillFromWorld} argument is {@code includeEntities}
     * in 1.20.1 — passing {@code true} is what pulls entities in the AABB
     * into the template's {@code StructureEntityInfo} list.</p>
     */
    public static StructureTemplate captureTemplate(ServerLevel level, BlockPos carriageOrigin, CarriageDims dims) {
        Vec3i size = interiorSize(dims);
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, interiorOrigin(carriageOrigin), size, true, Blocks.AIR);
        return template;
    }

    /**
     * Reset the interior volume at {@code carriageOrigin} to air and discard
     * any non-player entities inside it. Used by the editor before re-stamping
     * contents on {@code enter}, so previously placed blocks AND entities
     * don't blend with the newly chosen contents.
     */
    public static void eraseAt(ServerLevel level, BlockPos carriageOrigin, CarriageDims dims) {
        Vec3i size = interiorSize(dims);
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) return;
        BlockPos origin = interiorOrigin(carriageOrigin);
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = 0; dx < size.getX(); dx++) {
            for (int dy = 0; dy < size.getY(); dy++) {
                for (int dz = 0; dz < size.getZ(); dz++) {
                    level.setBlock(origin.offset(dx, dy, dz), air, 3);
                }
            }
        }
        discardEntitiesIn(level, origin, size);
    }

    /**
     * Public entity-only clear for the interior volume at
     * {@code carriageOrigin}. Used by the rolling-window spawn path (before
     * stamping new contents) so leftover armor stands / paintings from a
     * previous carriage at this shipyard position don't accumulate. The
     * block-only erase in {@link CarriageTemplate#eraseAt} already handles
     * stale interior blocks.
     */
    public static void discardEntitiesAt(ServerLevel level, BlockPos carriageOrigin, CarriageDims dims) {
        Vec3i size = interiorSize(dims);
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) return;
        discardEntitiesIn(level, interiorOrigin(carriageOrigin), size);
    }

    /**
     * Remove every non-player entity whose bounding box overlaps the interior
     * volume. Players are spared so an author who slipped through the barrier
     * cage mid-edit doesn't get kicked out of existence. Block-entity data is
     * already handled by the {@code setBlock(air)} pass in
     * {@link #eraseAt}.
     */
    private static void discardEntitiesIn(ServerLevel level, BlockPos interiorOrigin, Vec3i interiorSize) {
        AABB box = new AABB(
            interiorOrigin.getX(), interiorOrigin.getY(), interiorOrigin.getZ(),
            interiorOrigin.getX() + interiorSize.getX(),
            interiorOrigin.getY() + interiorSize.getY(),
            interiorOrigin.getZ() + interiorSize.getZ()
        );
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, box, e -> !(e instanceof Player));
        for (Entity e : entities) {
            e.discard();
        }
    }
}
