package games.brennan.dungeontrain.builder.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.BuilderTemplateFiles;
import games.brennan.dungeontrain.editor.SubmitHints;
import games.brennan.dungeontrain.editor.TemplateLoot;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Finds the build a Submit for Review is about and reads {@link SubmitHints} off it, so the note
 * screen knows which questions to ask.
 *
 * <p>The template file on this server comes first — the build this world uploaded is almost always
 * still on disk, and reading it costs nothing. A build this world only knows by relay id (uploaded
 * from another world, restored by the title-screen reconcile) is fetched from the relay instead, the
 * same fallback {@link BuilderRelayUpload}'s adopt takes. Anything that cannot be read is
 * {@link SubmitHints.Hints#NONE}: the general question is always asked, so an unreadable build costs
 * the author two prompts, never the submit.</p>
 */
public final class BuilderSubmitHints {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BuilderSubmitHints() {}

    /** The hints for one of the player's builds. Completes on whichever thread finished the read. */
    public static CompletableFuture<SubmitHints.Hints> forBuild(ServerPlayer player, ServerLevel level, int relayId) {
        String key = DungeonTrainWorldData.get(level).builderRelayBuilds().keyForRelayId(relayId);
        if (key != null) {
            SubmitHints.Hints local = fromDisk(level, key);
            if (local != null) return CompletableFuture.completedFuture(local);
        }
        String owner = player == null ? "" : player.getUUID().toString();
        return SharedCarriageClient.fetchBuild(relayId, owner).thenApply(result -> {
            SharedCarriageClient.BuildFetch build = result.build();
            if (build == null || build.blocks() == null || build.blocks().isEmpty()) return SubmitHints.Hints.NONE;
            try {
                StructureTemplate template = BuilderRelayPreview.templateOf(level, build);
                return SubmitHints.of(template, TemplateLoot.of(template));
            } catch (Throwable t) {
                LOGGER.info("[DungeonTrain] Submit hints: relay build {} would not read: {}", relayId, t.toString());
                return SubmitHints.Hints.NONE;
            }
        }).exceptionally(t -> SubmitHints.Hints.NONE);
    }

    /** The hints from this server's own copy of the template, or null when there is no readable file. */
    private static SubmitHints.Hints fromDisk(ServerLevel level, String key) {
        BuilderPhotoPaths.Kind kind = BuilderRelayKinds.kindOf(BuilderRelayBuilds.kindOfKey(key));
        String subKind = BuilderRelayBuilds.subKindOfKey(key);
        String id = BuilderRelayBuilds.idOfKey(key);
        Optional<CompoundTag> tag = BuilderTemplateFiles.rawTag(kind, subKind, id);
        if (tag.isEmpty()) return null;
        try {
            StructureTemplate template = new StructureTemplate();
            template.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), tag.get());
            return SubmitHints.of(template, TemplateLoot.of(template, kind, subKind.isEmpty() ? null : subKind, id));
        } catch (RuntimeException e) {
            LOGGER.info("[DungeonTrain] Submit hints: template {} would not read: {}", id, e.toString());
            return null;
        }
    }
}
