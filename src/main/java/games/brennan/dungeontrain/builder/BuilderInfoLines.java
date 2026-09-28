package games.brennan.dungeontrain.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * What the builder's info panel says about the current build.
 *
 * <p>Pure: the caller supplies a {@code translate} function, so every combination — draft vs named,
 * a part vs a carriage, a mode that parks no train — is testable without a client. What's shown when
 * is a set of small decisions that are easy to get wrong and impossible to notice in a screenshot
 * (an omitted line looks the same as a line that was never meant to be there).</p>
 */
public final class BuilderInfoLines {

    private static final String KEY_DRAFT = "gui.dungeontrain.builder.info.draft";
    private static final String KEY_SIZE = "gui.dungeontrain.builder.info.size";
    private static final String KEY_WEIGHT = "gui.dungeontrain.builder.info.weight";
    private static final String KEY_STAGE_ANY = "gui.dungeontrain.builder.info.stage_any";
    private static final String KEY_CREATOR = "gui.dungeontrain.builder.info.creator";

    private BuilderInfoLines() {}

    /**
     * Everything the panel needs, as the client already holds it.
     *
     * @param name      what the build saves as; empty for a draft
     * @param dirty     unsaved changes since the last stamp or save
     * @param dims      length, height, width — null when the mode parks no carriage
     * @param weight      how often the template rolls in a run; negative when it doesn't apply
     * @param trackKindId which track-side kind this build is, empty for a carriage build — the
     *                    track modes' counterpart to {@code subType}
     * @param creator     who originally built this template, when it was downloaded from another
     *                    player; empty for the player's own work, which is the ordinary case
     */
    public record Data(String name, boolean dirty, BuilderMode mode,
                       BuilderNewOptions.SubType subType, String partKindId,
                       String stageId, int[] dims, int weight, String trackKindId, String creator) {

        public Data {
            creator = creator == null ? "" : creator;
        }

        /** The nine-arg form, from before a build could say who built it. */
        public Data(String name, boolean dirty, BuilderMode mode,
                    BuilderNewOptions.SubType subType, String partKindId,
                    String stageId, int[] dims, int weight, String trackKindId) {
            this(name, dirty, mode, subType, partKindId, stageId, dims, weight, trackKindId, "");
        }

        /** The eight-arg form, from before the track modes had anything to open. */
        public Data(String name, boolean dirty, BuilderMode mode,
                    BuilderNewOptions.SubType subType, String partKindId,
                    String stageId, int[] dims, int weight) {
            this(name, dirty, mode, subType, partKindId, stageId, dims, weight, "", "");
        }
    }

    /** Separator between the pieces of a one-row readout. */
    public static final String JOIN = "  ·  ";

    /**
     * Identity: the name, then what it is and what it's for.
     *
     * <p>The first entry is always the name — callers colour it differently, and it's the one line
     * that exists for every build even when there is nothing else to say.</p>
     */
    public static List<String> identity(Data data, UnaryOperator<String> translate) {
        List<String> lines = new ArrayList<>(4);
        lines.add(nameLine(data, translate));
        lines.add(typeLine(data, translate));

        // Stage only means something for the modes that stamp a carriage — a track tile isn't
        // dressed by one, so an "Any" there would be answering a question nobody asked.
        if (BuilderNewOptions.hasSubTypes(data.mode())) {
            lines.add(data.stageId() == null || data.stageId().isEmpty()
                    ? translate.apply(KEY_STAGE_ANY)
                    : BuilderLabels.pretty(data.stageId()));
        }

        // Last, and only when there is somebody to name: a build made here says nothing, and a line
        // reading "Built by" with a blank after it would be worse than no line at all.
        if (!data.creator().isEmpty()) {
            lines.add(translate.apply(KEY_CREATOR).replace("%1$s", data.creator())
                    .replace("%s", data.creator()));
        }
        return lines;
    }

    /**
     * The measurements: size and pick weight, or empty when neither applies.
     *
     * <p>Kept apart from {@link #identity} because the two answer different questions and belong in
     * different places — identity is ambient (the HUD, the pause column), measurements are for when
     * you're about to act on the build.</p>
     */
    public static List<String> metrics(Data data, UnaryOperator<String> translate) {
        String line = sizeAndWeight(data, translate);
        return line.isEmpty() ? List.of() : List.of(line);
    }

    /** Identity and measurements together, in reading order. */
    public static List<String> of(Data data, UnaryOperator<String> translate) {
        List<String> lines = new ArrayList<>(identity(data, translate));
        lines.addAll(metrics(data, translate));
        return lines;
    }

    /**
     * The name, with a trailing {@code *} while there's unsaved work.
     *
     * <p>The green Save button says the same thing, deliberately — the panel is where you look to
     * find out what you have, and "what you have" includes whether it's been written down.</p>
     */
    private static String nameLine(Data data, UnaryOperator<String> translate) {
        String base = data.name() == null || data.name().isEmpty()
                ? translate.apply(KEY_DRAFT)
                : BuilderLabels.pretty(data.name());
        return data.dirty() ? base + " *" : base;
    }

    /** What kind of thing it is, and which builder mode it belongs to — {@code Parts — Walls}. */
    private static String typeLine(Data data, UnaryOperator<String> translate) {
        String mode = translate.apply(data.mode().labelKey());
        if (!BuilderNewOptions.hasSubTypes(data.mode())) {
            // The track kind does the qualifying here that a sub type does for a carriage, and it
            // is the one thing worth saying: eight kinds share the name `default`, so the name line
            // above cannot tell you which of them is on the plot.
            String trackKindId = data.trackKindId();
            return trackKindId == null || trackKindId.isEmpty()
                    ? mode
                    : translate.apply("gui.dungeontrain.builder.track_kind." + trackKindId)
                            + " — " + mode;
        }
        String subType = translate.apply(data.subType().labelKey());
        if (data.subType() == BuilderNewOptions.SubType.PARTS
                && data.partKindId() != null && !data.partKindId().isEmpty()) {
            return subType + " — " + BuilderLabels.pretty(data.partKindId());
        }
        return subType + " — " + mode;
    }

    /**
     * Carriage dimensions, and the pick weight when there is one.
     *
     * <p>Size earns its place because carriage dimensions are a <em>world</em> setting: a template
     * authored at one size resolves to nothing when stamped at another (both the carriage and part
     * stores size-filter and fall back to empty), and that failure is otherwise silent. Weight is
     * how often the finished template actually appears in a run — the most consequential thing about
     * it, and otherwise only visible in the technical editor.</p>
     */
    private static String sizeAndWeight(Data data, UnaryOperator<String> translate) {
        int[] dims = data.dims();
        StringBuilder out = new StringBuilder();
        if (dims != null && dims.length == 3 && dims[0] > 0 && dims[1] > 0 && dims[2] > 0) {
            out.append(translate.apply(KEY_SIZE)
                    .replace("%1$s", String.valueOf(dims[0]))
                    .replace("%2$s", String.valueOf(dims[1]))
                    .replace("%3$s", String.valueOf(dims[2])));
        }
        if (data.weight() >= 0) {
            if (out.length() > 0) {
                out.append(" · ");
            }
            out.append(translate.apply(KEY_WEIGHT).replace("%1$s", String.valueOf(data.weight())));
        }
        return out.toString();
    }
}
