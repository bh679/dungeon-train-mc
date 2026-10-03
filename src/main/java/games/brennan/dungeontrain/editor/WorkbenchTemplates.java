package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.editor.workbench.WorkbenchStagedBuild;
import games.brennan.dungeontrain.editor.workbench.WorkbenchStagingStore;
import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateRegistry;
import games.brennan.dungeontrain.template.TemplateStore;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * {@link Template.Staged}'s store and registry adapters, over {@link WorkbenchStagingStore}.
 *
 * <p>The store's save re-captures the plot into the staged snapshot and nothing else: no registry,
 * no weights, no relay, no source tree. The registry lists what is staged; nothing in it is ever
 * "built in", and a staged build has no bundled tier to promote to or reset from.</p>
 */
public final class WorkbenchTemplates {

    private WorkbenchTemplates() {}

    private static final TemplateStore<Template.Staged> STORE = new TemplateStore<>() {
        @Override public TemplateKind kind() { return TemplateKind.STAGED; }

        @Override
        public SaveResult save(ServerPlayer player, Template.Staged template) throws Exception {
            boolean written = WorkbenchEditor.save(player.serverLevel().getServer().overworld(), template.stagedId());
            return written ? SaveResult.written() : SaveResult.skipped();
        }

        @Override public boolean canPromote(Template.Staged template) { return false; }

        @Override
        public void promote(Template.Staged template) {
            throw new IllegalStateException("A staged build has no bundled tier — commit it to a kind first.");
        }
    };

    private static final TemplateRegistry<Template.Staged> REGISTRY = new TemplateRegistry<>() {
        @Override public TemplateKind kind() { return TemplateKind.STAGED; }

        @Override public List<Template.Staged> all() {
            return WorkbenchStagingStore.list().stream()
                .map(WorkbenchStagedBuild::stagedId).map(Template.Staged::new).toList();
        }

        @Override public List<Template.Staged> builtins() { return List.of(); }

        @Override public List<Template.Staged> customs() { return all(); }

        @Override public Optional<Template.Staged> find(String id) {
            return WorkbenchStagingStore.find(id).map(b -> new Template.Staged(b.stagedId()));
        }

        @Override public void reload() { WorkbenchStagingStore.clearCache(); }

        @Override public void clear() { WorkbenchStagingStore.clearCache(); }
    };

    public static TemplateStore<Template.Staged> store() { return STORE; }

    public static TemplateRegistry<Template.Staged> registry() { return REGISTRY; }
}
