package games.brennan.dungeontrain.template;

import java.util.Locale;

/**
 * Which axes a contents template is allowed to be <b>randomly flipped</b> along when it is stamped,
 * plus whether that roll also applies when the same template furnishes a portal room.
 *
 * <p>An enabled axis is not a flip — it is permission for one. The stamp rolls each enabled axis
 * independently (see {@code ContentsFlip.roll}), so a template with X enabled reads authored on
 * roughly half the carriages it lands in and mirrored on the other half.</p>
 *
 * <p>{@link #DEFAULT} is <b>X on, Y and Z off, rooms off</b> — the behaviour every template gets
 * when its {@code weights.json} entry carries no {@code flip} block, which is every template that
 * shipped before this option existed. X is the carriage's length axis: mirroring end-to-end is the
 * flip an interior is most likely to survive intact. Y (vertical) is best-effort — Minecraft has no
 * vertical block mirror and {@code EditorMirror.verticalFlip} can only toggle the common orientation
 * properties, so doors, beds and tall plants break — hence off by default.</p>
 *
 * <p>{@link #rooms()} is a scope flag, not an axis: portal rooms tile the same furnishing template
 * many times inside one room, so flipping there is a louder change than a per-carriage roll and is
 * opted into per template.</p>
 */
public record FlipOptions(boolean x, boolean y, boolean z, boolean rooms) {

    /** X enabled, everything else off — what a template with no authored {@code flip} block gets. */
    public static final FlipOptions DEFAULT = new FlipOptions(true, false, false, false);

    /** Nothing may flip anywhere. */
    public static final FlipOptions NONE = new FlipOptions(false, false, false, false);

    /** True when this is exactly {@link #DEFAULT} — the codec omits the {@code flip} block then. */
    public boolean isDefault() {
        return DEFAULT.equals(this);
    }

    /** True when no axis is enabled, so a roll can never produce a flip (the {@code rooms} flag is moot). */
    public boolean noAxes() {
        return !x && !y && !z;
    }

    /**
     * Copy with one named field replaced. {@code field} is one of {@code x}, {@code y}, {@code z}
     * (axes) or {@code rooms} (scope), case-insensitively; any other name returns {@code this}
     * unchanged so a mistyped command can't silently rewrite a different flag.
     */
    public FlipOptions with(String field, boolean value) {
        return switch (field == null ? "" : field.trim().toLowerCase(Locale.ROOT)) {
            case "x" -> new FlipOptions(value, y, z, rooms);
            case "y" -> new FlipOptions(x, value, z, rooms);
            case "z" -> new FlipOptions(x, y, value, rooms);
            case "rooms" -> new FlipOptions(x, y, z, value);
            default -> this;
        };
    }

    /** The current value of one named field, {@code false} for an unknown name. Inverse of {@link #with}. */
    public boolean get(String field) {
        return switch (field == null ? "" : field.trim().toLowerCase(Locale.ROOT)) {
            case "x" -> x;
            case "y" -> y;
            case "z" -> z;
            case "rooms" -> rooms;
            default -> false;
        };
    }

    /** True when {@code field} names one of the four flags {@link #with} understands. */
    public static boolean isField(String field) {
        return switch (field == null ? "" : field.trim().toLowerCase(Locale.ROOT)) {
            case "x", "y", "z", "rooms" -> true;
            default -> false;
        };
    }
}
