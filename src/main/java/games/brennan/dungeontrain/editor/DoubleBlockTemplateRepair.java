package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Completes two-space blocks — doors, tall plants, beds — that a saved structure template holds
 * only half of, so a stored template always stamps them whole.
 *
 * <p><b>Why this exists.</b> A template is stamped block for block, and a lone door half does not
 * survive: the Sable lift's neighbour cascade ({@code markAndNotifyBlock}) sees a lower half with
 * no matching upper above it and deletes the upper, or finds nothing above and leaves a half door
 * with a hole over it. Templates reach that state easily — the editor used to capture a plot after
 * a variant preview had swapped a door's lower half for another wood, which pops the old upper, and
 * a half-captured door then shipped in every copy ({@code portal_short}, {@code fire}). Rolled
 * variant cells are completed at placement by {@link MultiBlockVariants#expand}; this covers the
 * blocks baked into the template itself.</p>
 *
 * <p><b>Rules</b>, per two-space state ({@code half=lower|upper}, or a bed's {@code part=foot|head}
 * with a {@code facing}):</p>
 * <ul>
 *   <li>Partner cell empty (absent from the template, or air) → the partner half is added.</li>
 *   <li>Partner cell holds a two-space half of the right side but a different block (an oxidized
 *       copper lower under a copper upper) → it is rewritten to match. The lower half / bed foot is
 *       the authority, since it is what the player walks through.</li>
 *   <li>Partner cell holds anything else (a variant placeholder, a button) → left alone. That is a
 *       variant cell or a deliberate build, not a capture slip.</li>
 * </ul>
 *
 * <p>Works on the raw structure NBT so it runs before {@code StructureTemplate.load} and needs no
 * registries — the same call serves bundled files, the user tier and imported packs, and unit-tests
 * without a Minecraft bootstrap. The input tag is never mutated; a repaired copy comes back.</p>
 */
public final class DoubleBlockTemplateRepair {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String K_PALETTE = "palette";
    private static final String K_BLOCKS = "blocks";
    private static final String K_SIZE = "size";
    private static final String K_POS = "pos";
    private static final String K_STATE = "state";
    private static final String K_NAME = "Name";
    private static final String K_PROPS = "Properties";

    private static final String HALF = "half";
    private static final String LOWER = "lower";
    private static final String UPPER = "upper";
    private static final String PART = "part";
    private static final String FOOT = "foot";
    private static final String HEAD = "head";
    private static final String FACING = "facing";

    private static final Set<String> EMPTY_BLOCKS = Set.of(
        "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void");

    private DoubleBlockTemplateRepair() {}

    /**
     * @param tag       the repaired copy, or the original tag when nothing needed doing
     * @param added     partner halves that were missing and have been added
     * @param rewritten partner halves of the wrong block that have been made to match
     */
    public record Result(CompoundTag tag, int added, int rewritten) {
        public boolean changed() {
            return added > 0 || rewritten > 0;
        }
    }

    /** One template cell. */
    private record Pos(int x, int y, int z) {
        Pos offset(int[] d) {
            return new Pos(x + d[0], y + d[1], z + d[2]);
        }
    }

    /** {@link #repair(CompoundTag)}, logging one line when a template had to be completed. */
    public static CompoundTag repair(CompoundTag tag, String templateId) {
        Result r = repair(tag);
        if (r.changed()) {
            LOGGER.info("[DungeonTrain] Template {}: completed {} half-placed door/bed/plant cell(s), "
                + "matched {} mismatched half/halves.", templateId, r.added(), r.rewritten());
        }
        return r.tag();
    }

    /** Complete every two-space block {@code tag} holds only half of. Never mutates {@code tag}. */
    public static Result repair(CompoundTag tag) {
        if (tag == null || !tag.contains(K_PALETTE, Tag.TAG_LIST) || !tag.contains(K_BLOCKS, Tag.TAG_LIST)) {
            return new Result(tag, 0, 0);   // multi-palette ("palettes") templates are left as authored
        }
        CompoundTag out = tag.copy();
        ListTag palette = out.getList(K_PALETTE, Tag.TAG_COMPOUND);
        ListTag blocks = out.getList(K_BLOCKS, Tag.TAG_COMPOUND);
        int[] size = sizeOf(out);

        Map<Pos, CompoundTag> cells = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompound(i);
            cells.put(posOf(b), b);
        }

        int added = 0;
        int rewritten = 0;
        int original = blocks.size();
        for (int i = 0; i < original; i++) {
            CompoundTag block = blocks.getCompound(i);
            CompoundTag state = stateOf(palette, block);
            int[] offset = partnerOffset(state);
            if (offset == null) continue;

            Pos partnerPos = posOf(block).offset(offset);
            if (!inside(partnerPos, size)) continue;
            CompoundTag wanted = partnerState(state);
            CompoundTag partner = cells.get(partnerPos);

            if (partner == null || isEmpty(stateOf(palette, partner))) {
                if (partner == null) {
                    CompoundTag fresh = new CompoundTag();
                    fresh.put(K_POS, posTag(partnerPos));
                    fresh.putInt(K_STATE, paletteIndex(palette, wanted));
                    blocks.add(fresh);
                    cells.put(partnerPos, fresh);
                } else {
                    partner.putInt(K_STATE, paletteIndex(palette, wanted));
                }
                added++;
            } else if (isAuthority(state) && isMismatchedPartner(stateOf(palette, partner), wanted)) {
                partner.putInt(K_STATE, paletteIndex(palette, wanted));
                partner.remove("nbt");
                rewritten++;
            }
        }
        return added + rewritten == 0 ? new Result(tag, 0, 0) : new Result(out, added, rewritten);
    }

    /** Offset from a two-space half to its partner, or null when {@code state} is not one. */
    static int[] partnerOffset(CompoundTag state) {
        CompoundTag props = state.getCompound(K_PROPS);
        String half = props.getString(HALF);
        if (LOWER.equals(half)) return new int[] {0, 1, 0};
        if (UPPER.equals(half)) return new int[] {0, -1, 0};
        String part = props.getString(PART);
        if (!FOOT.equals(part) && !HEAD.equals(part)) return null;
        int[] toward = facingOffset(props.getString(FACING));
        if (toward == null) return null;
        // A bed's head lies in the direction it faces, from its foot.
        return FOOT.equals(part) ? toward : new int[] {-toward[0], -toward[1], -toward[2]};
    }

    private static int[] facingOffset(String facing) {
        return switch (facing) {
            case "north" -> new int[] {0, 0, -1};
            case "south" -> new int[] {0, 0, 1};
            case "west" -> new int[] {-1, 0, 0};
            case "east" -> new int[] {1, 0, 0};
            default -> null;
        };
    }

    /** The other half of {@code state}: same block and properties, side flipped. */
    static CompoundTag partnerState(CompoundTag state) {
        CompoundTag copy = state.copy();
        CompoundTag props = copy.getCompound(K_PROPS);
        String half = props.getString(HALF);
        if (LOWER.equals(half) || UPPER.equals(half)) {
            props.putString(HALF, LOWER.equals(half) ? UPPER : LOWER);
        } else {
            props.putString(PART, FOOT.equals(props.getString(PART)) ? HEAD : FOOT);
        }
        copy.put(K_PROPS, props);
        return copy;
    }

    /** The lower half / bed foot decides what a mismatched pair becomes. */
    private static boolean isAuthority(CompoundTag state) {
        CompoundTag props = state.getCompound(K_PROPS);
        return LOWER.equals(props.getString(HALF)) || FOOT.equals(props.getString(PART));
    }

    /**
     * True when {@code present} is the right side of a two-space block but not the one {@code wanted}
     * — a door of another wood, say. Anything that is not a two-space half is somebody's build.
     */
    private static boolean isMismatchedPartner(CompoundTag present, CompoundTag wanted) {
        if (present.getString(K_NAME).equals(wanted.getString(K_NAME))) return false;
        CompoundTag have = present.getCompound(K_PROPS);
        CompoundTag want = wanted.getCompound(K_PROPS);
        if (want.contains(HALF)) return have.getString(HALF).equals(want.getString(HALF));
        return have.getString(PART).equals(want.getString(PART));
    }

    private static boolean isEmpty(CompoundTag state) {
        return EMPTY_BLOCKS.contains(state.getString(K_NAME));
    }

    private static CompoundTag stateOf(ListTag palette, CompoundTag block) {
        int idx = block.getInt(K_STATE);
        return idx >= 0 && idx < palette.size() ? palette.getCompound(idx) : new CompoundTag();
    }

    /** Index of {@code state} in {@code palette}, appending it when absent. */
    private static int paletteIndex(ListTag palette, CompoundTag state) {
        for (int i = 0; i < palette.size(); i++) {
            if (palette.getCompound(i).equals(state)) return i;
        }
        palette.add(state);
        return palette.size() - 1;
    }

    private static Pos posOf(CompoundTag block) {
        ListTag p = block.getList(K_POS, Tag.TAG_INT);
        return new Pos(p.getInt(0), p.getInt(1), p.getInt(2));
    }

    private static ListTag posTag(Pos pos) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(pos.x()));
        list.add(IntTag.valueOf(pos.y()));
        list.add(IntTag.valueOf(pos.z()));
        return list;
    }

    /** The template's bounds, or null when it declares none (then nothing is bounds-checked). */
    private static int[] sizeOf(CompoundTag tag) {
        if (!tag.contains(K_SIZE, Tag.TAG_LIST)) return null;
        ListTag s = tag.getList(K_SIZE, Tag.TAG_INT);
        return s.size() == 3 ? new int[] {s.getInt(0), s.getInt(1), s.getInt(2)} : null;
    }

    private static boolean inside(Pos p, int[] size) {
        if (size == null) return true;
        return p.x() >= 0 && p.y() >= 0 && p.z() >= 0
            && p.x() < size[0] && p.y() < size[1] && p.z() < size[2];
    }
}
