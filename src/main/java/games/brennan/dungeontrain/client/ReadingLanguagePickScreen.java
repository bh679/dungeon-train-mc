package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.narrative.LanguageFamily;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Picks one language to add to {@link ReadingLanguagesScreen}: every Minecraft language whose family the
 * player doesn't already read, filtered by a search box. Picking one hands its locale back and returns.
 */
@OnlyIn(Dist.CLIENT)
public final class ReadingLanguagePickScreen extends Screen {

    private static final String KEY = "gui.dungeontrain.book_languages";

    private final ReadingLanguagesScreen parent;
    private final Set<String> chosenFamilies;
    private final Consumer<String> onPick;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 52, 33);
    private SettingsRowList list;
    private EditBox search;
    private String query = "";

    public ReadingLanguagePickScreen(ReadingLanguagesScreen parent, Set<String> chosenFamilies, Consumer<String> onPick) {
        super(Component.translatable(KEY + ".pick.title"));
        this.parent = parent;
        this.chosenFamilies = Set.copyOf(chosenFamilies);
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new net.minecraft.client.gui.components.StringWidget(this.title, this.font));
        this.search = header.addChild(new EditBox(this.font, 200, 20, Component.translatable(KEY + ".pick.search")));
        this.search.setHint(Component.translatable(KEY + ".pick.search"));
        this.search.setValue(this.query);
        this.search.setResponder(q -> {
            this.query = q;
            refill();
        });
        this.list = this.layout.addToContents(new SettingsRowList(this.minecraft, this.width,
                this.layout.getContentHeight(), this.layout.getHeaderHeight()));
        refill();
        this.layout.addToFooter(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).width(200).build());
        this.layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
        setInitialFocus(this.search);
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
        if (this.list != null) this.list.updateSize(this.width, this.layout);
    }

    private void refill() {
        if (this.list == null) return;
        this.list.clear();
        String q = this.query.trim().toLowerCase(Locale.ROOT);
        Map<String, LanguageInfo> languages = this.minecraft.getLanguageManager().getLanguages();
        languages.entrySet().stream()
                .filter(e -> !this.chosenFamilies.contains(LanguageFamily.of(e.getKey())))
                .filter(e -> q.isEmpty() || e.getKey().contains(q)
                        || e.getValue().toComponent().getString().toLowerCase(Locale.ROOT).contains(q))
                .sorted((a, b) -> a.getValue().toComponent().getString()
                        .compareToIgnoreCase(b.getValue().toComponent().getString()))
                .forEach(e -> this.list.addSetting(SettingsRowList.Icon.item(Items.BOOK),
                        e.getValue().toComponent(), Component.literal(e.getKey()),
                        Button.builder(Component.translatable(KEY + ".pick.add"), b -> pick(e.getKey()))
                                .bounds(0, 0, SettingsRowList.CONTROL_W, SettingsRowList.CONTROL_H).build(),
                        null));
        this.list.setScrollAmount(0);
    }

    private void pick(String locale) {
        this.onPick.accept(locale);
        onClose();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
