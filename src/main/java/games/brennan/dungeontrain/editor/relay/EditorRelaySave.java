package games.brennan.dungeontrain.editor.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderSave;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import games.brennan.dungeontrain.builder.relay.BuilderRelayUpload;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Sends a Train Editor save to the player's relay profile.
 *
 * <p>The editor's counterpart to {@code BuilderRelayUpload.afterSave}'s builder call site, and
 * deliberately nothing more than a call site: the capture, the size cap, the choice between a first
 * submit and a save through a held lease, and every message the player sees all live in
 * {@link BuilderRelayUpload} and are shared. An editor upload is indistinguishable from a builder
 * one on the wire, which is the point — the same template saved from either tool updates one entry
 * in one profile.</p>
 *
 * <p>Runs after the template is already on disk, and never instead of it. A relay that is down costs
 * the player one red line in chat and nothing else.</p>
 *
 * <p>Called from inside each editor's own {@code save} rather than from the commands and menus that
 * reach one. The editor has two ways in — the per-kind {@code /dt editor … save} commands, and
 * {@code Stores.save}, which the floating plot panel's Save button and {@code /dt save} both go
 * through — and every adapter behind the second delegates to the first's method. Hooking the two
 * families separately would upload twice for one save; hooking one of them would leave the same
 * template uploading from one command and not the other.</p>
 */
public final class EditorRelaySave {

    private static final Logger LOGGER = LogUtils.getLogger();

    private EditorRelaySave() {}

    /**
     * Upload what an editor save just wrote.
     *
     * <p>Only templates the player authored. The Train Editor is the tool Dungeon Train's own
     * content is made in, so without this gate a single {@code /dt save all} in a dev world would
     * push the shipped carriages into somebody's personal profile under their name. {@code
     * isBuiltin()} is already the line between "ships with the mod" and "yours" — a
     * {@code CarriageVariant.Custom}, or a track-side variant with a name other than {@code
     * default} — so the rule is that existing predicate rather than a second idea of what a new
     * template is.</p>
     *
     * <p>Everything here is best-effort: a save that cannot be located in a plot, or a player with
     * no server, simply does not upload, and the local write already succeeded either way. Silent,
     * with one exception — a template refused for having a built-in name says so. That case is the
     * author editing something and expecting to find it in My Builds, and the quiet version of it
     * is indistinguishable from the relay having lost their work.</p>
     */
    public static void afterSave(ServerPlayer player, Template model) {
        // Nothing here may fail the save. This runs inside each editor's save(), after the template
        // is already on disk, so an exception escaping would report a write that actually succeeded
        // as a failure — and would do it for a feature the player may not even have turned on.
        try {
            upload(player, model);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Editor relay upload: skipped '{}': {}",
                    model == null ? "?" : model.id(), t.toString());
        }
    }

    private static void upload(ServerPlayer player, Template model) {
        if (player == null || model == null) {
            return;
        }
        if (!BuilderRelayUpload.canUpload(player)) {
            // Profiles off, or no network consent. A deliberate configuration rather than a surprise,
            // so this one stays silent.
            return;
        }
        if (model.isBuiltin()) {
            // Said out loud, unlike the other early returns. A built-in NAME is the whole test — a
            // dimensional carriage called 'default' is one — so an author who poses a camel in the
            // default room, saves, and then loads that room back from the relay gets the copy from
            // before the camel and no hint as to why. Silence there reads exactly like data loss.
            player.sendSystemMessage(Component.literal(
                    "Editor: '" + model.displayName() + "' has a built-in name, so it was NOT sent to "
                            + "My Builds. Save it under a name of your own to keep a copy on the relay.")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        // The editor's plots are laid out in the overworld against that world's carriage dims, and
        // both the locate and the capture have to read the same world the plot grid was stamped in
        // — not whichever dimension the player happens to be standing in.
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return;
        }
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BuilderSave.Written written = EditorRelayWrite.of(model, overworld, dims);
        if (written == null) {
            return;
        }
        // The template's own stage link, not a builder world's current stage: a carriage built for
        // the desert stretch carries that with it, and a template with no link uploads with none.
        BuilderRelayUpload.afterSave(player, overworld, written, model.stageId());
    }
}
