package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.EditorMirror;
import games.brennan.dungeontrain.template.FlipOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Random;

/**
 * The per-placement random flip of a carriage-contents template: which axes this particular stamp
 * came out mirrored on, and every transform that decision implies.
 *
 * <p>{@link FlipOptions} (authored per template, persisted in {@code contents/weights.json}) says
 * which axes <em>may</em> flip; {@link #roll} decides which ones actually did for one stamp. The
 * roll is a <b>pure function</b> of {@code (contents id, world seed, carriage index)} because the
 * blocks pass and the entity pass are separate calls made ticks apart
 * ({@code CarriageContentsPlacer.placeBlocksOnly} / {@code placeEntitiesOnly}) and must agree
 * without sharing state.</p>
 *
 * <p><b>How each axis is applied.</b> The horizontal axes ride vanilla: X is
 * {@link Mirror#FRONT_BACK}, Z is {@link Mirror#LEFT_RIGHT}, and both together are
 * {@link Rotation#CLOCKWISE_180} (the composition of the two mirrors is exactly that rotation), so
 * {@code placeInWorld} does the palette, block-entity and state work itself. Vanilla mirrors about
 * the <em>origin</em> — {@code StructureTemplate.transform} maps {@code x → -x} with a zero pivot,
 * and an integer pivot could never centre an even-length box anyway — so {@link #originFor} shifts
 * the placement origin by {@code size-1} on each flipped horizontal axis, which lands the mirrored
 * box exactly back on the interior. The vertical axis has no vanilla equivalent at all, so
 * {@link #verticallyFlipped} builds a flipped copy of the template; it is best-effort in the same
 * way {@link EditorMirror#verticalFlip} is (doors, beds and tall plants are positional and break),
 * which is why Y is off by default.</p>
 *
 * <p>Everything keyed by an authored local position — the variant sidecar, the container pools, the
 * template's entity list — moves with the stamp via {@link #mapLocal}. Those passes keep seeding
 * their own rolls on the <em>authored</em> local position, so which block a cell picks does not
 * change with the flip; only where that cell lands does.</p>
 */
public final class ContentsFlip {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ContentsFlip() {}

    /** Which axes one stamp actually came out flipped on. */
    public record Flip(boolean x, boolean y, boolean z) {

        /** The authored orientation — what the editor preview always uses. */
        public static final Flip NONE = new Flip(false, false, false);

        /** True when this stamp is the authored orientation, so every transform here is identity. */
        public boolean isNone() {
            return !x && !y && !z;
        }
    }

    // Salts for the per-axis rolls. The mixing shape (worldSeed ^ index*GOLDEN ^ salt*MIX) is the
    // established one from CarriageVariantBlocks.pickIndexFromLockGroup, so a flip roll decorrelates
    // from the variant rolls that run on the same (seed, index) pair.
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final long MIX = 0xBF58476D1CE4E5B9L;
    private static final int SALT_X = 0x5F1D;
    private static final int SALT_Y = 0x7A31;
    private static final int SALT_Z = 0x2C6B;

    /**
     * Roll the flip for one stamp: each <b>enabled</b> axis independently at 50/50, each disabled
     * axis never. Pure — same arguments always give the same result, which is what lets the blocks
     * pass and the deferred entity pass transform identically without sharing state.
     */
    public static Flip roll(String contentsId, FlipOptions opts, long worldSeed, int carriageIndex) {
        FlipOptions o = opts == null ? FlipOptions.DEFAULT : opts;
        if (o.noAxes()) return Flip.NONE;
        int idHash = contentsId == null ? 0 : contentsId.hashCode();
        return new Flip(
            o.x() && axis(idHash, SALT_X, worldSeed, carriageIndex),
            o.y() && axis(idHash, SALT_Y, worldSeed, carriageIndex),
            o.z() && axis(idHash, SALT_Z, worldSeed, carriageIndex));
    }

    private static boolean axis(int idHash, int salt, long worldSeed, int carriageIndex) {
        long seed = worldSeed
            ^ ((long) carriageIndex * GOLDEN)
            ^ (((long) idHash + salt) * MIX);
        return new Random(seed).nextBoolean();
    }

    /** What {@link #label} reports when nothing flipped — the authored orientation. */
    public static final String LABEL_NONE = "none";

    /**
     * How a flip reads on the F3+4 debug panel: {@link #LABEL_NONE} when the stamp came out
     * authored, else the flipped axes joined ({@code X}, {@code Z}, {@code X+Z}, {@code X+Y+Z}).
     * Here rather than at the panel so the recorded label and the roll can never drift apart.
     */
    public static String label(Flip flip) {
        if (flip == null || flip.isNone()) return LABEL_NONE;
        StringBuilder out = new StringBuilder(5);
        if (flip.x()) out.append("X");
        if (flip.y()) out.append(out.isEmpty() ? "Y" : "+Y");
        if (flip.z()) out.append(out.isEmpty() ? "Z" : "+Z");
        return out.toString();
    }

    /**
     * Where an authored cell ends up inside the same box once {@code flip} is applied:
     * {@code c → size-1-c} on each flipped axis. Its own inverse.
     */
    public static BlockPos mapLocal(BlockPos local, Vec3i size, Flip flip) {
        if (flip == null || flip.isNone()) return local;
        return new BlockPos(
            flip.x() ? size.getX() - 1 - local.getX() : local.getX(),
            flip.y() ? size.getY() - 1 - local.getY() : local.getY(),
            flip.z() ? size.getZ() - 1 - local.getZ() : local.getZ());
    }

    /**
     * The continuous-space counterpart of {@link #mapLocal} for an entity's local position. A block
     * at integer {@code c} occupies {@code [c, c+1)}, so mirroring that span maps a continuous
     * coordinate {@code c → size - c} (not {@code size - 1 - c}); an entity standing in the middle
     * of a cell stays in the middle of the mirrored cell.
     */
    public static double mapLocalCoord(double c, int size, boolean flipped) {
        return flipped ? size - c : c;
    }

    /**
     * Reflect a block state the same way the stamp did, for the passes that write states themselves
     * (the variant sidecar) rather than going through {@code placeInWorld}. Delegates to
     * {@link EditorMirror#reflect} so there is one reflection implementation in the codebase.
     */
    public static BlockState reflect(BlockState state, Flip flip) {
        if (state == null || flip == null || flip.isNone()) return state;
        return EditorMirror.reflect(state, flip.x(), flip.y(), flip.z());
    }

    /**
     * Mirror an entity's yaw across the flipped horizontal axes, matching {@link #reflect}. Yaw 0 is
     * south (+Z) and 90 is west (−X), so mirroring X (east ↔ west) negates it and mirroring Z
     * (north ↔ south) reflects it about 180°.
     */
    public static float reflectYaw(float yaw, Flip flip) {
        if (flip == null) return yaw;
        float out = yaw;
        if (flip.x()) out = -out;
        if (flip.z()) out = 180.0f - out;
        return Mth.wrapDegrees(out);
    }

    /**
     * The {@link Mirror} / {@link Rotation} that express {@code flip}'s horizontal half. Both axes
     * together are a 180° rotation rather than two mirrors, because {@code StructurePlaceSettings}
     * holds only one mirror and the composition of the two IS that rotation.
     */
    public static void applyHorizontal(StructurePlaceSettings settings, Flip flip) {
        if (flip == null) return;
        if (flip.x() && flip.z()) {
            settings.setRotation(Rotation.CLOCKWISE_180);
        } else if (flip.x()) {
            settings.setMirror(Mirror.FRONT_BACK);
        } else if (flip.z()) {
            settings.setMirror(Mirror.LEFT_RIGHT);
        }
    }

    /**
     * The origin to hand {@code placeInWorld} so a horizontally flipped stamp lands back on the box
     * at {@code origin}. Vanilla maps {@code x → -x} about a zero pivot, so the whole template would
     * otherwise be placed one box to the negative side of the origin.
     */
    public static BlockPos originFor(BlockPos origin, Vec3i size, Flip flip) {
        if (flip == null || (!flip.x() && !flip.z())) return origin;
        return origin.offset(
            flip.x() ? size.getX() - 1 : 0,
            0,
            flip.z() ? size.getZ() - 1 : 0);
    }

    // Vertically flipped copies, keyed by identity on the source template so a re-read of the same
    // id (an editor save, a package switch) can never hand back the previous file's flip. The map is
    // bounded by CarriageContentsStore's own template cache, which clears this one with it.
    private static final Map<StructureTemplate, StructureTemplate> VERTICAL_CACHE = new IdentityHashMap<>();

    /**
     * A vertically flipped copy of {@code template} — Minecraft has no vertical block mirror, so the
     * flip is done on the saved NBT: every block's {@code y} becomes {@code size-1-y}, every palette
     * state goes through {@link EditorMirror#verticalFlip}, and each entity's local position is
     * mirrored in continuous space. Best-effort by nature (two-block structures such as doors and
     * beds cannot survive it), which is why the Y option is off by default.
     *
     * <p>Returns {@code template} unchanged if the transform fails for any reason — a broken flip is
     * never worth failing a carriage spawn over.</p>
     */
    public static synchronized StructureTemplate verticallyFlipped(StructureTemplate template,
                                                                   HolderGetter<Block> blocks) {
        if (template == null) return null;
        StructureTemplate cached = VERTICAL_CACHE.get(template);
        if (cached != null) return cached;
        StructureTemplate flipped = buildVerticallyFlipped(template, blocks);
        VERTICAL_CACHE.put(template, flipped);
        return flipped;
    }

    /** Drop the vertical-flip cache. Called wherever the contents template cache itself is dropped. */
    public static synchronized void clearCache() {
        VERTICAL_CACHE.clear();
    }

    private static StructureTemplate buildVerticallyFlipped(StructureTemplate template,
                                                             HolderGetter<Block> blocks) {
        try {
            int sizeY = template.getSize().getY();
            CompoundTag tag = template.save(new CompoundTag());
            flipPalette(tag, "palette", blocks);
            if (tag.contains("palettes", Tag.TAG_LIST)) {
                ListTag palettes = tag.getList("palettes", Tag.TAG_LIST);
                for (int i = 0; i < palettes.size(); i++) {
                    flipStates(palettes.getList(i), blocks);
                }
            }
            for (Tag t : tag.getList("blocks", Tag.TAG_COMPOUND)) {
                CompoundTag block = (CompoundTag) t;
                flipIntPos(block.getList("pos", Tag.TAG_INT), sizeY);
            }
            for (Tag t : tag.getList("entities", Tag.TAG_COMPOUND)) {
                CompoundTag entity = (CompoundTag) t;
                flipDoublePos(entity.getList("pos", Tag.TAG_DOUBLE), sizeY);
                flipIntPos(entity.getList("blockPos", Tag.TAG_INT), sizeY);
                if (entity.contains("nbt", Tag.TAG_COMPOUND)) {
                    flipDoublePos(entity.getCompound("nbt").getList("Pos", Tag.TAG_DOUBLE), sizeY);
                }
            }
            StructureTemplate out = new StructureTemplate();
            out.load(blocks, tag);
            return out;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Vertical contents flip failed, stamping unflipped: {}", e.toString());
            return template;
        }
    }

    private static void flipPalette(CompoundTag tag, String key, HolderGetter<Block> blocks) {
        if (!tag.contains(key, Tag.TAG_LIST)) return;
        flipStates(tag.getList(key, Tag.TAG_COMPOUND), blocks);
    }

    /** Flip every state in one palette in place. Palettes are shared by every cell that uses them,
     *  which is exactly right for a pure vertical mirror: the same authored state flips the same way
     *  wherever it appears. */
    private static void flipStates(ListTag palette, HolderGetter<Block> blocks) {
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = palette.getCompound(i);
            BlockState state = NbtUtils.readBlockState(blocks, entry);
            BlockState flipped = EditorMirror.verticalFlip(state);
            if (flipped != state) palette.set(i, NbtUtils.writeBlockState(flipped));
        }
    }

    /** {@code y → size-1-y} on a 3-int position list. */
    private static void flipIntPos(ListTag pos, int sizeY) {
        if (pos.size() != 3) return;
        pos.set(1, IntTag.valueOf(sizeY - 1 - pos.getInt(1)));
    }

    /** {@code y → size-y} on a 3-double position list — see {@link #mapLocalCoord}. */
    private static void flipDoublePos(ListTag pos, int sizeY) {
        if (pos.size() != 3) return;
        pos.set(1, DoubleTag.valueOf(sizeY - pos.getDouble(1)));
    }
}
