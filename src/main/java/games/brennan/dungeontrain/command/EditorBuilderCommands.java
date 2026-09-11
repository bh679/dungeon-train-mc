package games.brennan.dungeontrain.command;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.TemplateCreditClient;
import games.brennan.dungeontrain.template.BuilderCredit;
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
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /dt editor [contents|portals] builder <id> <uuid|none> [name…]} — who originally built a
 * template.
 *
 * <p>The credit sits in the kind's {@code weights.json} beside the weight, gate and label (see
 * {@code TemplateMeta#builder()}), so it overlays a bundled entry from the user tier and writes
 * through to the source tree in dev mode — a credit set here ships with the next release. After the
 * local write it is mirrored to the relay for the builder leaderboard ({@link TemplateCreditClient});
 * the reply says whether that half happened, because a release jar has no way to do it and the
 * developer should not have to guess.</p>
 *
 * <p>{@code <uuid>} may be {@code none} to clear the credit. A uuid with no name is accepted; so is
 * a name with a uuid of {@code -}, for a builder who has never been on the relay — that one is
 * thanked on the Credits page but cannot be counted on a board.</p>
 *
 * <p>Kept out of {@link EditorCommand} for the same reason {@link EditorLabelCommands} is: the
 * three handlers and their shared wording sit together.</p>
 */
final class EditorBuilderCommands {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** {@code <uuid>} spelling that clears the credit. */
    static final String NONE = "none";
    /** {@code <uuid>} spelling for "no uuid, name only". */
    static final String NO_UUID = "-";

    private EditorBuilderCommands() {}

    /** {@code /dt editor builder <id> <uuid|none> [name…]} */
    static int runCarriageBuilder(CommandSourceStack source, String rawId, String rawUuid, String rawName) {
        String id = rawId.toLowerCase(Locale.ROOT);
        if (CarriageVariantRegistry.find(id).isEmpty()) {
            return unknown(source, "carriage", rawId);
        }
        BuilderCredit credit = parse(rawUuid, rawName);
        try {
            BuilderCredit stored = CarriageWeights.setBuilder(id, credit);
            return success(source, "carriage", "carriage", id, stored);
        } catch (IOException e) {
            return failure(source, "carriage", id, e);
        }
    }

    /** {@code /dt editor contents builder <id> <uuid|none> [name…]} */
    static int runContentsBuilder(CommandSourceStack source, String rawId, String rawUuid, String rawName) {
        String id = rawId.toLowerCase(Locale.ROOT);
        if (CarriageContentsRegistry.find(id).isEmpty()) {
            return unknown(source, "contents", rawId);
        }
        BuilderCredit credit = parse(rawUuid, rawName);
        try {
            BuilderCredit stored = CarriageContentsWeights.setBuilder(id, credit);
            return success(source, "contents", "contents", id, stored);
        } catch (IOException e) {
            return failure(source, "contents", id, e);
        }
    }

    /**
     * {@code /dt editor <tracks|portals> builder <kind> <name> <uuid|none> [name…]} — {@code kind}
     * is already parsed (null = the caller has sent its own failure).
     */
    static int runTrackBuilder(CommandSourceStack source, TrackKind kind, String rawName, String rawUuid,
                               String rawBuilderName) {
        if (kind == null) return 0;
        Optional<String> found = TrackVariantRegistry.find(kind, rawName);
        if (found.isEmpty()) {
            return unknown(source, kind.id(), rawName);
        }
        String name = found.get();
        BuilderCredit credit = parse(rawUuid, rawBuilderName);
        try {
            BuilderCredit stored = TrackVariantWeights.setBuilder(kind, name, credit);
            return success(source, kind.id(), kind.id(), name, stored);
        } catch (IOException e) {
            return failure(source, kind.id(), name, e);
        }
    }

    /**
     * The credit the arguments spell, or {@code null} for {@link #NONE}. Pure, so the three handlers
     * agree on what "no uuid" and "no name" look like.
     */
    static BuilderCredit parse(String rawUuid, String rawName) {
        String uuid = rawUuid == null ? "" : rawUuid.trim();
        if (uuid.equalsIgnoreCase(NONE)) return null;
        if (uuid.equals(NO_UUID)) uuid = "";
        return BuilderCredit.ofOrNull(uuid, rawName);
    }

    // ---------- shared wording ----------

    /**
     * Report the local write, then mirror to the relay and report that too — on the server thread,
     * so the second line reaches the same player after the first.
     */
    private static int success(CommandSourceStack source, String what, String relayKind, String id,
                               BuilderCredit stored) {
        String line = stored == null
            ? "Editor: " + what + " '" + id + "' credits nobody now."
            : "Editor: " + what + " '" + id + "' built by " + stored.display()
                + (stored.hasUuid() ? "" : " (no uuid — not counted on the leaderboard)") + ".";
        source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GREEN), true);

        MinecraftServer server = source.getServer();
        TemplateCreditClient.record(relayKind, id, stored).thenAccept(outcome ->
            server.execute(() -> source.sendSuccess(() -> relayLine(outcome), false)));
        return 1;
    }

    private static Component relayLine(TemplateCreditClient.Outcome outcome) {
        return switch (outcome) {
            case RECORDED -> Component.literal("Relay: credit recorded for the builder leaderboard.")
                .withStyle(ChatFormatting.GRAY);
            case NO_ADMIN -> Component.literal(
                "Relay: saved locally only — no relay admin URL on this machine, so the leaderboard was not updated.")
                .withStyle(ChatFormatting.YELLOW);
            case REJECTED -> Component.literal("Relay: refused the credit (see the log). Saved locally.")
                .withStyle(ChatFormatting.YELLOW);
            case UNREACHABLE -> Component.literal("Relay: unreachable — credit saved locally, leaderboard not updated.")
                .withStyle(ChatFormatting.YELLOW);
        };
    }

    private static int unknown(CommandSourceStack source, String what, String raw) {
        source.sendFailure(Component.literal("Unknown " + what + " '" + raw + "'.").withStyle(ChatFormatting.RED));
        return 0;
    }

    private static int failure(CommandSourceStack source, String what, String id, IOException e) {
        LOGGER.error("[DungeonTrain] editor builder {} '{}' failed", what, id, e);
        source.sendFailure(Component.literal("Builder credit failed: " + e.getMessage()).withStyle(ChatFormatting.RED));
        return 0;
    }
}
