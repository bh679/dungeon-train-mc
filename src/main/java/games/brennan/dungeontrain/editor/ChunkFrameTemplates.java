package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameRegistry;
import games.brennan.dungeontrain.portal.chunkframe.ChunkFrameStore;
import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateRegistry;
import games.brennan.dungeontrain.template.TemplateStore;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * Chunk frames through the unified {@link TemplateStore} / {@link TemplateRegistry} surface — what
 * lets {@code /dt save}, {@code /dt reset} and the rest of the template tooling treat a frame like any
 * other template.
 */
public final class ChunkFrameTemplates {

    private ChunkFrameTemplates() {}

    private static final TemplateStore<Template.ChunkFrame> STORE = new TemplateStore<>() {
        @Override public TemplateKind kind() { return TemplateKind.CHUNK_FRAME; }

        @Override
        public SaveResult save(ServerPlayer player, Template.ChunkFrame template) throws Exception {
            boolean toSource = ChunkFrameEditor.save(player, player.serverLevel().getServer().overworld(),
                template.name());
            return toSource ? SaveResult.written() : SaveResult.skipped();
        }

        @Override public boolean canPromote(Template.ChunkFrame template) { return false; }

        @Override
        public void promote(Template.ChunkFrame template) {
            throw new IllegalStateException(
                "Frames are written to the source tree on save in dev mode — '/dt save default' does not apply.");
        }
    };

    private static final TemplateRegistry<Template.ChunkFrame> REGISTRY = new TemplateRegistry<>() {
        @Override public TemplateKind kind() { return TemplateKind.CHUNK_FRAME; }

        @Override public List<Template.ChunkFrame> all() {
            return ChunkFrameRegistry.names().stream().map(Template.ChunkFrame::new).toList();
        }

        @Override public List<Template.ChunkFrame> builtins() {
            return all().stream().filter(f -> ChunkFrameStore.isBundled(f.name())).toList();
        }

        @Override public List<Template.ChunkFrame> customs() {
            return all().stream().filter(f -> !ChunkFrameStore.isBundled(f.name())).toList();
        }

        @Override public Optional<Template.ChunkFrame> find(String id) {
            return ChunkFrameRegistry.names().contains(id) ? Optional.of(new Template.ChunkFrame(id)) : Optional.empty();
        }

        @Override public void reload() { ChunkFrameRegistry.reload(); }

        @Override public void clear() { ChunkFrameRegistry.clear(); }
    };

    public static TemplateStore<Template.ChunkFrame> store() { return STORE; }

    public static TemplateRegistry<Template.ChunkFrame> registry() { return REGISTRY; }
}
