package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.command.SaveCommand;
import games.brennan.dungeontrain.editor.relay.EditorRelaySave;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorSaveAsPromptPacket;
import games.brennan.dungeontrain.net.EditorUnsavedListPacket;
import games.brennan.dungeontrain.template.Stores;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Save-as for the Train Editor: what a Save on a template the mod ships does instead of writing
 * over it, outside dev mode.
 *
 * <h2>Save as new</h2>
 * The player's edits become a new template under a name of their own, with everything the source
 * carries beside its blocks, on a plot of its own; and the shipped template is put back exactly as
 * it was last saved. Done by composing what each kind already has rather than a new capture per
 * kind:
 * <ol>
 *   <li>record the shipped template's user-tier files as they were;</li>
 *   <li>save it in place — the ordinary save, upload switched off — so its files hold the edits,
 *       live blocks and held container links included;</li>
 *   <li>run the kind's own New-copy-from under the new name, which now copies the edits, their
 *       sidecars and weights, and registers, places and stamps the copy;</li>
 *   <li>put the recorded files back and restamp the shipped template's plot from them — always,
 *       even when step 3 failed;</li>
 *   <li>upload the new template like any other save.</li>
 * </ol>
 * The per-kind half of each step lives in {@link EditorSaveAsKinds}.
 *
 * <h2>Keep as local edit</h2>
 * The ordinary save in place. The relay gate ({@link EditorRelaySave}) keeps it off My Builds and
 * says so.
 */
public final class EditorSaveAs {

    private static final Logger LOGGER = LogUtils.getLogger();

    private EditorSaveAs() {}

    /** Ask the player what to do with a Save on {@code model}. Writes nothing. */
    public static void prompt(ServerPlayer player, Template model) {
        LOGGER.info("[DungeonTrain] Save-as: asking {} what to do with shipped '{}'",
            player.getName().getString(), EditorTemplateAddress.of(model));
        DungeonTrainNet.sendTo(player,
            new EditorSaveAsPromptPacket(EditorTemplateAddress.of(model), model.displayName()));
    }

    /** Keep the edit over the shipped template, on this install only. */
    public static boolean keepLocal(ServerPlayer player, Template model) {
        boolean saved = SaveCommand.saveOnePlayerVisible(player, model);
        if (saved) refreshUnsaved(player);
        return saved;
    }

    /**
     * Save {@code source}'s edits as a new template called {@code rawName}, and put {@code source}
     * back as it was last saved. See the class note for the steps.
     *
     * @return true when the new template was made
     */
    public static boolean saveAsNew(ServerPlayer player, Template source, String rawName) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        if (EditorDevMode.isEnabled()) {
            // Every step below would also write through to the source tree in dev mode — and a
            // dev checkout saves shipped templates in place on purpose, so it is never asked.
            fail(player, Component.translatable("chat.dungeontrain.save_as.dev_mode"));
            return false;
        }
        ServerLevel level = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        EditorSaveAsKinds.Adapter kind = EditorSaveAsKinds.of(source);
        String name = rawName == null ? "" : rawName.trim().toLowerCase(Locale.ROOT);

        Optional<Component> invalid = kind.validate(source, name);
        if (invalid.isPresent()) {
            fail(player, invalid.get());
            return false;
        }
        // The copy may reload other plots of this kind from disk (a new name shifts the row). Any of
        // them with unsaved edits would lose them, so refuse and name them instead.
        List<String> reloadedIds = kind.reloaded(source, name).stream()
            .map(EditorDirtyCheck::dirtyKeyFor).toList();
        List<String> blocking = EditorSaveAsGuard.unsavedAmong(
            kind.categoryId(source), reloadedIds, EditorDirtyCheck.dirtyKeyFor(source),
            EditorSaveAsGuard.unsavedKeys(level, dims));
        if (!blocking.isEmpty()) {
            fail(player, Component.translatable("chat.dungeontrain.save_as.blocked_dirty",
                String.join(", ", blocking)));
            return false;
        }

        EditorSaveAsFiles recorded;
        try {
            recorded = EditorSaveAsFiles.capture(kind.userFiles(source), EditorSidecarBaseline::lookup);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Save-as: could not record '{}' before saving", source.id(), e);
            fail(player, Component.translatable("chat.dungeontrain.save_as.failed", String.valueOf(e.getMessage())));
            return false;
        }

        Template made = null;
        try {
            EditorRelaySave.withoutUpload(() -> Stores.save(player, source));
            made = kind.copy(player, source, name);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Save-as: {} -> '{}' failed", EditorTemplateAddress.of(source), name, t);
            fail(player, Component.translatable("chat.dungeontrain.save_as.failed", String.valueOf(t.getMessage())));
        } finally {
            restore(player, level, dims, kind, source, recorded);
        }
        if (made == null) {
            refreshUnsaved(player);
            return false;
        }

        EditorRelaySave.afterSave(player, made);
        player.sendSystemMessage(Component.translatable("chat.dungeontrain.save_as.saved",
            made.displayName(), source.displayName()).withStyle(ChatFormatting.GREEN));
        LOGGER.info("[DungeonTrain] Save-as: {} saved {} as new {}; the source restored",
            player.getName().getString(), EditorTemplateAddress.of(source), EditorTemplateAddress.of(made));
        refreshUnsaved(player);
        return true;
    }

    /** Step 4: files back, caches dropped, the one plot restamped from them. */
    private static void restore(ServerPlayer player, ServerLevel level, CarriageDims dims,
                                EditorSaveAsKinds.Adapter kind, Template source, EditorSaveAsFiles recorded) {
        boolean filesOk = recorded.restore();
        try {
            kind.restoreAndRestamp(level, source, dims);
        } catch (Throwable t) {
            filesOk = false;
            LOGGER.error("[DungeonTrain] Save-as: restamping '{}' after restore failed", source.id(), t);
        }
        ProvenanceCache.invalidateAll();
        if (!filesOk) {
            // Nothing is lost — the worst case is the edit left over the shipped template, which is
            // what Keep as local edit does on purpose — but the player should know it happened.
            player.sendSystemMessage(Component.translatable("chat.dungeontrain.save_as.restore_failed",
                source.displayName()).withStyle(ChatFormatting.YELLOW));
        }
    }

    private static void refreshUnsaved(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        DungeonTrainNet.sendTo(player, new EditorUnsavedListPacket(EditorDirtyCheck.findDirty(level, dims)));
    }

    private static void fail(ServerPlayer player, Component message) {
        player.sendSystemMessage(message.copy().withStyle(ChatFormatting.RED));
    }
}
