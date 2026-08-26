package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;

/**
 * One resolved <b>New</b> selection, ready to apply to a builder world.
 *
 * <p>A record rather than seven arguments: the pieces are only meaningful together, and three of
 * them are strings that would otherwise be trivially swappable at the call site.</p>
 *
 * @param shell   the carriage the world gets parked with — the build itself for a whole carriage,
 *                and the thing a room or a part is shown on for the other two
 * @param picked  the contents id or part template name the builder chose, empty for a whole
 *                carriage (whose choice is {@code shell})
 * @param partKind which part is being authored; null unless {@code subType} is
 *                {@link BuilderNewOptions.SubType#PARTS}
 * @param name    what the build saves as; empty means an unnamed draft
 * @param stageId the Stage the build is for, empty when none was picked
 * @param wholeCarriageId a saved whole carriage to start the build from, empty when the builder
 *                picked a Stage instead. The two are the two halves of one picker, so at most one
 *                of {@code stageId} and this is ever set
 */
public record BuilderNewRequest(BuilderMode mode,
                                BuilderNewOptions.SubType subType,
                                CarriageVariant shell,
                                String picked,
                                CarriagePartKind partKind,
                                String name,
                                String stageId,
                                String wholeCarriageId) {

    public BuilderNewRequest {
        picked = picked == null ? "" : picked;
        name = name == null ? "" : name;
        stageId = stageId == null ? "" : stageId;
        wholeCarriageId = wholeCarriageId == null ? "" : wholeCarriageId;
    }

    /** For the callers that never start from a saved build — every sub type but Whole Carriage. */
    public BuilderNewRequest(BuilderMode mode, BuilderNewOptions.SubType subType, CarriageVariant shell,
                             String picked, CarriagePartKind partKind, String name, String stageId) {
        this(mode, subType, shell, picked, partKind, name, stageId, "");
    }

    public String partKindId() {
        return partKind == null ? "" : partKind.id();
    }
}
