package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.building.BuildingRegistry;
import games.brennan.dungeontrain.building.BuildingStore;
import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateRegistry;
import games.brennan.dungeontrain.template.TemplateStore;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * Buildings through the unified {@link TemplateStore} / {@link TemplateRegistry} surface — what lets
 * {@code /dt save}, {@code /dt reset} and the rest of the template tooling treat a building like any other
 * template.
 */
public final class BuildingTemplates {

    private BuildingTemplates() {}

    private static final TemplateStore<Template.Building> STORE = new TemplateStore<>() {
        @Override public TemplateKind kind() { return TemplateKind.BUILDING; }

        @Override
        public SaveResult save(ServerPlayer player, Template.Building template) throws Exception {
            boolean toSource = BuildingEditor.save(player, player.serverLevel().getServer().overworld(),
                template.name());
            return toSource ? SaveResult.written() : SaveResult.skipped();
        }

        @Override public boolean canPromote(Template.Building template) { return false; }

        @Override
        public void promote(Template.Building template) {
            throw new IllegalStateException(
                "Buildings are written to the source tree on save in dev mode — '/dt save default' does not apply.");
        }
    };

    private static final TemplateRegistry<Template.Building> REGISTRY = new TemplateRegistry<>() {
        @Override public TemplateKind kind() { return TemplateKind.BUILDING; }

        @Override public List<Template.Building> all() {
            return BuildingRegistry.names().stream().map(Template.Building::new).toList();
        }

        @Override public List<Template.Building> builtins() {
            return all().stream().filter(b -> BuildingStore.isBundled(b.name())).toList();
        }

        @Override public List<Template.Building> customs() {
            return all().stream().filter(b -> !BuildingStore.isBundled(b.name())).toList();
        }

        @Override public Optional<Template.Building> find(String id) {
            return BuildingRegistry.contains(id) ? Optional.of(new Template.Building(id)) : Optional.empty();
        }

        @Override public void reload() { BuildingRegistry.reload(); }

        @Override public void clear() { BuildingRegistry.clear(); }
    };

    public static TemplateStore<Template.Building> store() { return STORE; }

    public static TemplateRegistry<Template.Building> registry() { return REGISTRY; }
}
