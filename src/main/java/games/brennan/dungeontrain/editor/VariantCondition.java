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
 * An optional per-entry gate: "only place this entry if the target block is one of these".
 *
 * <p>The <b>target</b> is either one touching block ({@link #direction}, in the template's authored
 * frame) or a lock group anywhere in the template ({@link #groupId} &gt; 0). The <b>values</b> are
 * block ids, OR'd together, and match on block type alone — a rolled rotation, half or open flag on
 * the target can never fail the condition. {@code minecraft:air} stands for "empty" and also matches
 * the variant placeholder.</p>
 *
 * <p>A condition with no values is no condition ({@link #isDefault}); the target is kept anyway so an
 * author can pick it before adding the first value. A failed condition drops its entry from the roll
 * — see {@link VariantConditions#eligible}.</p>
 *
 * <p>Sidecar token: {@code "down=minecraft:oak_planks,minecraft:stone"} or {@code "group3=…"}.
 * Omitted from JSON when default.</p>
 */
public record VariantCondition(@Nullable Direction direction, int groupId, List<ResourceLocation> blocks) {

    /** Most-asked-for target ("only on top of …"), and where a fresh condition starts. */
    public static final Direction DEFAULT_DIRECTION = Direction.DOWN;
    public static final VariantCondition NONE = new VariantCondition(DEFAULT_DIRECTION, 0, List.of());
    /** Keeps the token well under the edit packet's 256-char string cap and the menu row legible. */
    public static final int MAX_VALUES = 8;

    private static final ResourceLocation AIR_ID = ResourceLocation.withDefaultNamespace("air");
    private static final String GROUP_PREFIX = "group";

    public VariantCondition {
        if (groupId < 0) groupId = 0;
        if (groupId > 0) direction = null;
        else if (direction == null) direction = DEFAULT_DIRECTION;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    public boolean isDefault() {
        return blocks.isEmpty();
    }

    public boolean isGroupTarget() {
        return groupId > 0;
    }

    /** True when {@code state} — the block that ends up at the target — is one of the values. */
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

    public VariantCondition withDirection(Direction dir) {
        return new VariantCondition(dir, 0, blocks);
    }

    public VariantCondition withGroup(int lockId) {
        return new VariantCondition(null, lockId, blocks);
    }

    /** Copy with {@code id} appended; unchanged if already present or the list is full. */
    public VariantCondition withValueAdded(ResourceLocation id) {
        if (id == null || blocks.contains(id) || blocks.size() >= MAX_VALUES) return this;
        List<ResourceLocation> out = new ArrayList<>(blocks);
        out.add(id);
        return new VariantCondition(direction, groupId, out);
    }

    /** Copy without the value at {@code index}; unchanged when out of range. */
    public VariantCondition withValueRemoved(int index) {
        if (index < 0 || index >= blocks.size()) return this;
        List<ResourceLocation> out = new ArrayList<>(blocks);
        out.remove(index);
        return new VariantCondition(direction, groupId, out);
    }

    /** Turn a touching target with a plot rotation. Group targets have no geometry. */
    public VariantCondition rotated(Rotation rotation) {
        if (isDefault() || isGroupTarget() || rotation == Rotation.NONE) return this;
        return new VariantCondition(rotation.rotate(direction), 0, blocks);
    }

    /** Reflect a touching target across the flipped axes. Group targets have no geometry. */
    public VariantCondition reflected(boolean flipX, boolean flipY, boolean flipZ) {
        if (isDefault() || isGroupTarget()) return this;
        Direction d = direction;
        if (flipX && d.getAxis() == Direction.Axis.X) d = d.getOpposite();
        if (flipY && d.getAxis() == Direction.Axis.Y) d = d.getOpposite();
        if (flipZ && d.getAxis() == Direction.Axis.Z) d = d.getOpposite();
        return d == direction ? this : new VariantCondition(d, 0, blocks);
    }

    /** Short target label for the editor: {@code N S E W U D} or {@code G3}. */
    public String targetLabel() {
        if (isGroupTarget()) return "G" + groupId;
        return switch (direction) {
            case NORTH -> "N";
            case SOUTH -> "S";
            case EAST -> "E";
            case WEST -> "W";
            case UP -> "U";
            case DOWN -> "D";
        };
    }

    /** The target half of the token: a direction name or {@code group<N>}. */
    public String targetToken() {
        return isGroupTarget() ? GROUP_PREFIX + groupId : direction.getSerializedName();
    }

    /** Sidecar / NBT / packet token. Empty string for {@link #NONE}-equivalent default. */
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
            ResourceLocation id = ResourceLocation.tryParse(part.trim());
            if (id == null || part.isBlank()) continue;
            if (!id.equals(AIR_ID) && !BuiltInRegistries.BLOCK.containsKey(id)) continue;
            out = out.withValueAdded(id);
        }
        return out;
    }

    /** Parse just a target token ({@code down}, {@code group3}); {@code null} when malformed. */
    @Nullable
    public static VariantCondition parseTarget(String target) {
        if (target.startsWith(GROUP_PREFIX)) {
            try {
                int id = Integer.parseInt(target.substring(GROUP_PREFIX.length()));
                return id > 0 ? new VariantCondition(null, id, List.of()) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Direction d = Direction.byName(target);
        return d == null ? null : new VariantCondition(d, 0, List.of());
    }
}
