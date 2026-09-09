package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.VariantGroupMoves;
import games.brennan.dungeontrain.template.TemplateMeta;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageContentsWeights;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.CarriageWeights;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /dt editor [contents|portals] label <id> [name…]} — the editor's rename.
 *
 * <p>A template's id is its file name, and the id-changing {@code rename} verbs have to move that
 * file and everything filed under it — which is why they refuse a bundled template ("ships with the
 * mod"). The label is the other half of the Stage precedent (stable id + free-text name): it lives
 * in the kind's {@code weights.json} beside the weight and gate, so it overlays a bundled entry
 * from the user tier, follows the id through an id rename, and writes through to the source tree
 * in dev mode like every other weights edit. Nothing at spawn time reads it.</p>
 *
 * <p>Kept out of {@link EditorCommand} so the three handlers and their shared wording sit together
 * rather than three screens apart in that file.</p>
 */
final class EditorLabelCommands {

    private static final Logger LOGGER = LogUtils.getLogger();

    private EditorLabelCommands() {}

    /** {@code /dt editor label <id> [name…]} */
    static int runCarriageLabel(CommandSourceStack source, String rawId, String rawName) {
        String id = rawId.toLowerCase(Locale.ROOT);
        if (CarriageVariantRegistry.find(id).isEmpty()) {
            return unknown(source, "carriage", rawId);
        }
        try {
            String stored = CarriageWeights.setName(id, rawName);
            return success(source, "carriage", id, stored,
                CarriageWeights.current().nameFor(id));
        } catch (IOException e) {
            return failure(source, "carriage", id, e);
        }
    }

    /** {@code /dt editor contents label <id> [name…]} */
    static int runContentsLabel(CommandSourceStack source, String rawId, String rawName) {
        String id = rawId.toLowerCase(Locale.ROOT);
        if (CarriageContentsRegistry.find(id).isEmpty()) {
            return unknown(source, "contents", rawId);
        }
        try {
            String stored = CarriageContentsWeights.setName(id, rawName);
            return success(source, "contents", id, stored,
                CarriageContentsWeights.current().nameFor(id));
        } catch (IOException e) {
            return failure(source, "contents", id, e);
        }
    }

    /**
     * {@code /dt editor <tracks|portals> label <kind> <name> [label…]} — {@code kind} is already
     * parsed (null = the caller has sent its own failure).
     */
    static int runTrackLabel(CommandSourceStack source, TrackKind kind, String rawName, String rawLabel) {
        if (kind == null) return 0;
        Optional<String> found = TrackVariantRegistry.find(kind, rawName);
        if (found.isEmpty()) {
            return unknown(source, kind.id(), rawName);
        }
        String name = found.get();
        if (TrackKind.DEFAULT_NAME.equals(name)) {
            source.sendFailure(Component.literal("'default' is the kind's fallback — it keeps its name."));
            return 0;
        }
        try {
            String stored = TrackVariantWeights.setName(kind, name, rawLabel);
            return success(source, kind.id(), name, stored, TrackVariantWeights.nameFor(kind, name));
        } catch (IOException e) {
            return failure(source, kind.id(), name, e);
        }
    }

    // ---------- shared wording ----------

    private static int success(CommandSourceStack source, String what, String id, String stored, String shown) {
        String line = stored == null
            ? "Editor: " + what + " '" + id + "' shows its id again."
            : "Editor: " + what + " '" + id + "' is now shown as \"" + shown + "\" (id unchanged).";
        source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GREEN), true);
        if (stored != null && stored.length() >= TemplateMeta.NAME_MAX) {
            source.sendSuccess(() -> Component.literal(
                "Label cut to " + TemplateMeta.NAME_MAX + " characters.").withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    private static int unknown(CommandSourceStack source, String what, String raw) {
        source.sendFailure(Component.literal("Unknown " + what + " '" + raw + "'.").withStyle(ChatFormatting.RED));
        return 0;
    }

    private static int failure(CommandSourceStack source, String what, String id, IOException e) {
        LOGGER.error("[DungeonTrain] editor label {} '{}' failed", what, id, e);
        source.sendFailure(Component.literal("Label failed: " + e.getMessage()).withStyle(ChatFormatting.RED));
        return 0;
    }

    /** One line per {@link VariantGroupMoves.Refusal}, shared by the contents and portal moves. */
    static String moveRefusal(VariantGroupMoves.Refusal refusal, String child, String oldParent,
                              String newParent, String what) {
        return switch (refusal) {
            case NOT_A_MEMBER -> "'" + child + "' is not a sub-variant of '" + oldParent + "'.";
            case SAME_PARENT -> "'" + child + "' is already a sub-variant of '" + newParent + "'.";
            case SELF -> "Cannot make '" + child + "' a sub-variant of itself.";
            case TARGET_IS_CHILD -> "'" + newParent + "' is itself a sub-variant — nesting is single-hop only.";
            case CHILD_IS_PARENT -> "'" + child + "' has sub-variants of its own — it cannot sit under another "
                + what + ".";
        };
    }
}
