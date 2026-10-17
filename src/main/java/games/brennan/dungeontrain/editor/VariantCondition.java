package games.brennan.dungeontrain.editor;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An optional per-entry gate: "only place this entry if the target is one of these blocks".
 *
 * <p>The <b>target</b> is either a set of touching sides ({@link #sideMask}, bit per
 * {@link Direction#get3DDataValue}, in the template's authored frame) — the gate passes when
 * <i>any</i> of them holds a listed block — or a lock group anywhere in the template
 * ({@link #groupId} &gt; 0), which passes when that group rolled a listed block. The <b>values</b>
 * are block ids, OR'd together, and match on block type alone: a rolled rotation, half or open flag
 * can never fail the condition. {@code minecraft:air} stands for "empty" and also matches the
 * variant placeholder.</p>
 *
 * <p>A condition with no values is no condition ({@link #isDefault}); the target is kept anyway so
 * an author can pick it before adding the first value. A failed condition drops its entry from the
 * roll — see {@link VariantConditions#eligible}.</p>
 *
 * <p>Sidecar token: {@code "down+north=minecraft:oak_planks,minecraft:stone"} or {@code "group3=…"}.
 * Omitted from JSON when default.</p>
 */
public record VariantCondition(int sideMask, int groupId, List<ResourceLocation> blocks) {

    /** Most-asked-for target ("only on top of …"), and where a fresh condition starts. */
    public static final Direction DEFAULT_DIRECTION = Direction.DOWN;
    public static final int ALL_SIDES = 0b111111;
    public static final VariantCondition NONE = new VariantCondition(bit(DEFAULT_DIRECTION), 0, List.of());
    /** Keeps the token well under the sync packet's cap and the popup strip legible. */
    public static final int MAX_VALUES = 12;

    private static final ResourceLocation AIR_ID = ResourceLocation.withDefaultNamespace("air");
    private static final String GROUP_PREFIX = "group";

    public VariantCondition {
        if (groupId < 0) groupId = 0;
        sideMask &= ALL_SIDES;
        if (groupId > 0) sideMask = 0;
        else if (sideMask == 0) sideMask = ALL_SIDES;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    public static int bit(Direction d) {
        return 1 << d.get3DDataValue();
    }

    public boolean isDefault() {
        return blocks.isEmpty();
    }

    public boolean isGroupTarget() {
        return groupId > 0;
    }

    public boolean hasSide(Direction d) {
        return (sideMask & bit(d)) != 0;
    }

    /** The selected touching sides, in {@link Direction} order; empty for a group target. */
    public List<Direction> sides() {
        List<Direction> out = new ArrayList<>(6);
        for (Direction d : Direction.values()) if (hasSide(d)) out.add(d);
        return out;
    }

    /** True when {@code state} — the block that ends up at a target — is one of the values. */
    public boolean matches(@Nullable BlockState state) {
        return blocks.contains(idOf(state));
    }

    /** Block id used for matching; the variant placeholder and {@code null} both count as air. */
    public static ResourceLocation idOf(@Nullable BlockState state) {
        if (state == null || CarriageVariantBlocks.isEmptyPlaceholder(state)) return AIR_ID;
        return BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

    /** Value id for a held block (empty hand / air → air). */
    public static ResourceLocation valueFor(@Nullable BlockState held) {
        return held == null ? AIR_ID : idOf(held);
    }

    /** Touching mode with exactly {@code dir} selected. */
    public VariantCondition withDirection(Direction dir) {
        return new VariantCondition(bit(dir), 0, blocks);
    }

    /** Touching mode with {@code mask} selected (empty ⇒ every side). */
    public VariantCondition withSides(int mask) {
        return new VariantCondition(mask, 0, blocks);
    }

    /** Flip one side — the band picker's plain click. From group mode this starts at that side. */
    public VariantCondition toggleSide(Direction dir) {
        return isGroupTarget() ? withDirection(dir) : withSides(sideMask ^ bit(dir));
    }

    /**
     * Flip every side except {@code keep} — the band picker's shift-click, "toggle all but that
     * one". From all sides it solos {@code keep}; again, it restores the rest.
     */
    public VariantCondition toggleOtherSides(Direction keep) {
        if (isGroupTarget()) return withDirection(keep);
        return withSides(sideMask ^ (ALL_SIDES & ~bit(keep)));
    }

    public VariantCondition withGroup(int lockId) {
        return new VariantCondition(0, lockId, blocks);
    }

    /** Copy with {@code id} appended; unchanged if already present or the list is full. */
    public VariantCondition withValueAdded(ResourceLocation id) {
        if (id == null || blocks.contains(id) || blocks.size() >= MAX_VALUES) return this;
        List<ResourceLocation> out = new ArrayList<>(blocks);
        out.add(id);
        return new VariantCondition(sideMask, groupId, out);
    }

    /** Copy without the value at {@code index}; unchanged when out of range. */
    public VariantCondition withValueRemoved(int index) {
        if (index < 0 || index >= blocks.size()) return this;
        List<ResourceLocation> out = new ArrayList<>(blocks);
        out.remove(index);
        return new VariantCondition(sideMask, groupId, out);
    }

    /** Copy with {@code id} removed if present, added otherwise. */
    public VariantCondition withValueToggled(ResourceLocation id) {
        int at = blocks.indexOf(id);
        return at >= 0 ? withValueRemoved(at) : withValueAdded(id);
    }

    /** Turn the touching sides with a plot rotation. Group targets have no geometry. */
    public VariantCondition rotated(Rotation rotation) {
        if (isDefault() || isGroupTarget() || rotation == Rotation.NONE) return this;
        int out = 0;
        for (Direction d : sides()) out |= bit(rotation.rotate(d));
        return new VariantCondition(out, 0, blocks);
    }

    /** Reflect the touching sides across the flipped axes. Group targets have no geometry. */
    public VariantCondition reflected(boolean flipX, boolean flipY, boolean flipZ) {
        if (isDefault() || isGroupTarget()) return this;
        int out = 0;
        for (Direction d : sides()) {
            boolean flip = switch (d.getAxis()) {
                case X -> flipX;
                case Y -> flipY;
                case Z -> flipZ;
            };
            out |= bit(flip ? d.getOpposite() : d);
        }
        return out == sideMask ? this : new VariantCondition(out, 0, blocks);
    }

    /** Short target label for the editor: side letters ({@code ND}, {@code *} for all) or {@code G3}. */
    public String targetLabel() {
        if (isGroupTarget()) return "G" + groupId;
        if (sideMask == ALL_SIDES) return "*";
        StringBuilder sb = new StringBuilder();
        for (Direction d : sides()) sb.append(letter(d));
        return sb.toString();
    }

    /** One-letter label for a side: {@code N S E W U D}. */
    public static String letter(Direction d) {
        return switch (d) {
            case NORTH -> "N";
            case SOUTH -> "S";
            case EAST -> "E";
            case WEST -> "W";
            case UP -> "U";
            case DOWN -> "D";
        };
    }

    /** The target half of the token: side names joined by {@code +}, or {@code group<N>}. */
    public String targetToken() {
        if (isGroupTarget()) return GROUP_PREFIX + groupId;
        StringBuilder sb = new StringBuilder();
        for (Direction d : sides()) {
            if (sb.length() > 0) sb.append('+');
            sb.append(d.getSerializedName());
        }
        return sb.toString();
    }

    /** Sidecar / NBT / packet token. */
    public String id() {
        StringBuilder sb = new StringBuilder(targetToken()).append('=');
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(blocks.get(i));
        }
        return sb.toString();
    }

    /**
     * Parse a token. Malformed targets fall back to {@link #NONE}; unknown block ids are dropped (a
     * modded block that is no longer installed simply stops matching).
     */
    public static VariantCondition parse(@Nullable String token) {
        if (token == null) return NONE;
        String raw = token.trim();
        int eq = raw.indexOf('=');
        String target = (eq < 0 ? raw : raw.substring(0, eq)).toLowerCase(Locale.ROOT);
        VariantCondition base = parseTarget(target);
        if (base == null) return NONE;
        if (eq < 0) return base;
        VariantCondition out = base;
        for (String part : raw.substring(eq + 1).split(",")) {
            if (part.isBlank()) continue;
            ResourceLocation id = ResourceLocation.tryParse(part.trim());
            if (id == null || !isKnownValue(id)) continue;
            out = out.withValueAdded(id);
        }
        return out;
    }

    /** True for air or a registered block — what a value may name. */
    public static boolean isKnownValue(ResourceLocation id) {
        return id.equals(AIR_ID) || BuiltInRegistries.BLOCK.containsKey(id);
    }

    /** Parse just a target token ({@code down}, {@code down+north}, {@code group3}); {@code null} when malformed. */
    @Nullable
    public static VariantCondition parseTarget(String target) {
        if (target.startsWith(GROUP_PREFIX)) {
            try {
                int id = Integer.parseInt(target.substring(GROUP_PREFIX.length()));
                return id > 0 ? new VariantCondition(0, id, List.of()) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        int mask = 0;
        for (String name : target.split("\\+")) {
            Direction d = Direction.byName(name.trim());
            if (d == null) return null;
            mask |= bit(d);
        }
        return mask == 0 ? null : new VariantCondition(mask, 0, List.of());
    }
}
