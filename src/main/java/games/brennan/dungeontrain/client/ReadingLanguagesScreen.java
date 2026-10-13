package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.narrative.ReadingLanguages;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * Options › Dungeon Train › Book languages: the languages this player reads community books in, each with
 * a weight. The game language is always first and can't be removed; an added language is removed by
 * cycling its weight to "Remove" (dropped when the page closes). Each row's hint shows the share of books
 * that language gets. Saved to the client config on close; the Options screen re-syncs the server.
 */
@OnlyIn(Dist.CLIENT)
public final class ReadingLanguagesScreen extends Screen {

    /** Weight value standing for "remove this language" in an added language's cycle. */
    private static final int REMOVE = 0;
    private static final String KEY = "gui.dungeontrain.book_languages";

    private final Screen parent;
    private final String gameLocale;
    private int gameWeight;
    /** Working copy: locale + weight, weight {@link #REMOVE} meaning "drop on close". */
    private final List<ReadingLanguages.Entry> extras;
    /** Fresh per {@link #init} — {@code HeaderAndFooterLayout} has no remove, and every weight change rebuilds. */
    private HeaderAndFooterLayout layout;
    private SettingsRowList list;

    public ReadingLanguagesScreen(Screen parent) {
        super(Component.translatable(KEY + ".title"));
        this.parent = parent;
        this.gameLocale = ClientLanguage.selected();
        this.gameWeight = ClientDisplayConfig.gameLanguageWeight();
        this.extras = new ArrayList<>(ClientDisplayConfig.readingLanguages(this.gameLocale));
    }

    @Override
    protected void init() {
        this.layout = new HeaderAndFooterLayout(this);
        this.layout.addTitleHeader(this.title, this.font);
        this.list = this.layout.addToContents(new SettingsRowList(this.minecraft, this.width,
                this.layout.getContentHeight(), this.layout.getHeaderHeight()));
        populate();
        LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
        Button add = footer.addChild(Button.builder(Component.translatable(KEY + ".add"),
                b -> this.minecraft.setScreen(new ReadingLanguagePickScreen(this, chosenFamilies(), this::addLanguage)))
                .width(150).build());
        add.active = liveExtras().size() < ReadingLanguages.MAX_EXTRA;
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(150).build());
        this.layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    /** Coming back from the picker: a re-shown screen only repositions in 1.21, so rebuild for the new row. */
    @Override
    public void added() {
        super.added();
        if (this.list != null) rebuildWidgets();
    }

    @Override
    protected void repositionElements() {
        if (this.layout == null) return;
        this.layout.arrangeElements();
        if (this.list != null) this.list.updateSize(this.width, this.layout);
    }

    private void populate() {
        this.list.addSetting(SettingsRowList.Icon.item(Items.WRITABLE_BOOK), languageName(this.gameLocale),
                shareHint(this.gameWeight), weightButton(this.gameWeight, false, w -> this.gameWeight = w),
                Component.translatable(KEY + ".game.tip"));
        for (int i = 0; i < this.extras.size(); i++) {
            final int index = i;
            ReadingLanguages.Entry e = this.extras.get(i);
            this.list.addSetting(SettingsRowList.Icon.item(Items.BOOK), languageName(e.locale()),
                    shareHint(e.weight()), weightButton(e.weight(), true, w -> {
                        this.extras.set(index, new ReadingLanguages.Entry(e.locale(), w));
                    }),
                    Component.translatable(KEY + ".extra.tip"));
        }
    }

    private CycleButton<Integer> weightButton(int initial, boolean removable,
                                              java.util.function.IntConsumer set) {
        List<Integer> values = new ArrayList<>();
        for (int w = ReadingLanguages.MIN_WEIGHT; w <= ReadingLanguages.MAX_WEIGHT; w++) values.add(w);
        if (removable) values.add(REMOVE);
        return CycleButton.<Integer>builder(w -> w == REMOVE
                        ? Component.translatable(KEY + ".remove")
                        : Component.translatable(KEY + ".weight", w))
                .withValues(values).withInitialValue(initial).displayOnlyValue()
                .create(0, 0, SettingsRowList.CONTROL_W, SettingsRowList.CONTROL_H,
                        Component.translatable(KEY + ".weight_label"), (b, w) -> {
                            set.accept(w);
                            rebuildWidgets(); // every row's share changes
                        });
    }

    /** "N% of books" for a language of {@code weight} against the current total. */
    private Component shareHint(int weight) {
        if (weight == REMOVE) return Component.translatable(KEY + ".removed");
        int total = this.gameWeight;
        for (ReadingLanguages.Entry e : liveExtras()) total += e.weight();
        if (liveExtras().isEmpty()) return Component.translatable(KEY + ".only");
        return Component.translatable(KEY + ".share", Math.round(100f * weight / total));
    }

    private List<ReadingLanguages.Entry> liveExtras() {
        List<ReadingLanguages.Entry> out = new ArrayList<>();
        for (ReadingLanguages.Entry e : this.extras) if (e.weight() != REMOVE) out.add(e);
        return out;
    }

    private java.util.Set<String> chosenFamilies() {
        java.util.Set<String> out = new java.util.HashSet<>();
        out.add(games.brennan.dungeontrain.narrative.LanguageFamily.of(this.gameLocale));
        for (ReadingLanguages.Entry e : liveExtras()) out.add(e.family());
        return out;
    }

    private void addLanguage(String locale) {
        List<ReadingLanguages.Entry> next = new ArrayList<>(liveExtras());
        next.add(new ReadingLanguages.Entry(locale, ReadingLanguages.DEFAULT_WEIGHT));
        this.extras.clear();
        this.extras.addAll(ReadingLanguages.clean(next, this.gameLocale));
    }

    /** A locale's own name as Minecraft lists it ("Українська (Україна)"), or the code. */
    static Component languageName(String locale) {
        LanguageInfo info = locale == null ? null
                : net.minecraft.client.Minecraft.getInstance().getLanguageManager().getLanguage(locale);
        return info != null ? info.toComponent() : Component.literal(String.valueOf(locale));
    }

    @Override
    public void onClose() {
        ClientDisplayConfig.setReadingLanguages(this.gameWeight, ReadingLanguages.clean(liveExtras(), this.gameLocale));
        this.minecraft.setScreen(this.parent);
    }
}
