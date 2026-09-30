package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.building.LostCityReferences;
import games.brennan.dungeontrain.template.SaveResult;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.template.TemplateKind;
import games.brennan.dungeontrain.template.TemplateRegistry;
import games.brennan.dungeontrain.template.TemplateStore;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * The official Lost City buildings through the unified {@link TemplateStore} / {@link TemplateRegistry} surface —
 * listable like any template, never savable: {@code /dt save} on one is refused with the reason.
 */
public final class LostCityTemplates {

    /** Why every write is refused — Big Lost City's buildings are All Rights Reserved. */
    public static final String VIEW_ONLY = "You can't edit these, they are part of the Lost Cities mod.";

    private LostCityTemplates() {}

    private static final TemplateStore<Template.LostCity> STORE = new TemplateStore<>() {
        @Override public TemplateKind kind() { return TemplateKind.LOST_CITY; }

        @Override
        public SaveResult save(ServerPlayer player, Template.LostCity template) {
            throw new IllegalStateException(VIEW_ONLY);
        }

        @Override public boolean canPromote(Template.LostCity template) { return false; }

        @Override
        public void promote(Template.LostCity template) {
            throw new IllegalStateException(VIEW_ONLY);
        }
    };

    private static final TemplateRegistry<Template.LostCity> REGISTRY = new TemplateRegistry<>() {
        @Override public TemplateKind kind() { return TemplateKind.LOST_CITY; }

        @Override public List<Template.LostCity> all() {
            return LostCityReferences.all().stream().map(r -> new Template.LostCity(r.name())).toList();
        }

        @Override public List<Template.LostCity> builtins() { return all(); }

        @Override public List<Template.LostCity> customs() { return List.of(); }

        @Override public Optional<Template.LostCity> find(String id) {
            return LostCityReferences.find(id).map(r -> new Template.LostCity(r.name()));
        }

        @Override public void reload() {
            LostCityReferences.reload(net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer());
        }

        @Override public void clear() { LostCityReferences.clear(); }
    };

    public static TemplateStore<Template.LostCity> store() { return STORE; }

    public static TemplateRegistry<Template.LostCity> registry() { return REGISTRY; }
}
