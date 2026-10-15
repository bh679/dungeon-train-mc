package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import games.brennan.dungeontrain.mixin.client.CreativeModeInventoryScreenAccessor;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.world.item.CreativeModeTab;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The menus a Live Eye stream may show while Show GUI to viewers is on: inventories and containers,
 * the pause menu, advancements and the death screens. Anything else — the chat box, signs, books, anvils, command
 * blocks, other mods' screens — is read before it draws ({@link StreamCapture}), and so is a listed
 * menu while the player is typing into it (a focused text box, the creative search tab, a recipe
 * book search). What the player types never goes out.
 *
 * <p>Matched by class name, superclasses included, so Better Advancements (optional) needs no
 * compile-time link. NeoForge runs with Mojang names, so vanilla names match in production too.</p>
 */
public final class StreamableScreens {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A screen that is, or extends, one of these is a listed menu. */
    static final Set<String> LISTED = Set.of(
        "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen",
        "net.minecraft.client.gui.screens.PauseScreen",
        "net.minecraft.client.gui.screens.advancements.AdvancementsScreen",
        "betteradvancements.common.gui.BetterAdvancementsScreen",
        // The death screens, for a stream that carries on after death (LivePostMortem): the moment,
        // the stream-on choice, the recap pages and their ride photos. The recap's feedback and
        // mod-recommendation boxes fall under the typing rule like any other text box.
        "games.brennan.dungeontrain.client.DeathMomentScreen",
        "games.brennan.dungeontrain.client.live.LivePostMortemChoiceScreen",
        "games.brennan.dungeontrain.client.NarrativeDeathScreen",
        "games.brennan.dungeontrain.client.snapshot.RideGalleryScreen");

    /** …unless it is, or extends, one of these: a container with a text field of its own. */
    static final Set<String> UNLISTED = Set.of(
        "net.minecraft.client.gui.screens.inventory.AnvilScreen");

    /** {@code RecipeBookComponent.searchBox}; null once it could not be read (then: fail closed). */
    @Nullable
    private static Field recipeSearchField;
    private static boolean recipeSearchResolved;

    private StreamableScreens() {}

    /** True when {@code screen} may be broadcast. */
    public static boolean shown(@Nullable Screen screen) {
        if (screen == null) return false;
        return shown(lineage(screen.getClass()), typing(screen));
    }

    /**
     * @param lineage the screen's class name and every superclass name
     * @param typing  the player is typing into it, or it shows what they typed
     */
    static boolean shown(Set<String> lineage, boolean typing) {
        if (typing) return false;
        for (String name : lineage) if (UNLISTED.contains(name)) return false;
        for (String name : lineage) if (LISTED.contains(name)) return true;
        return false;
    }

    static Set<String> lineage(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) names.add(c.getName());
        return names;
    }

    private static boolean typing(Screen screen) {
        GuiEventListener focused = screen.getFocused();
        if (focused instanceof EditBox) return true;
        if (screen instanceof CreativeModeInventoryScreen && creativeSearchTab()) return true;
        if (screen instanceof RecipeUpdateListener listener) return recipeSearchInUse(listener.getRecipeBookComponent());
        return false;
    }

    /** The creative tab on show has a search box (vanilla's search tab, or a mod tab with one). */
    private static boolean creativeSearchTab() {
        CreativeModeTab tab = CreativeModeInventoryScreenAccessor.dungeontrain$getSelectedTab();
        return tab == null || tab.hasSearchBar();
    }

    /**
     * The recipe book's search box reports no focus through the screen ({@code isFocused} is always
     * false), so it is read directly: open with the box focused or holding text counts as typing.
     */
    private static boolean recipeSearchInUse(@Nullable RecipeBookComponent book) {
        if (book == null || !book.isVisible()) return false;
        EditBox box = recipeSearchBox(book);
        if (box == null) return true; // could not read it: keep the menu off the stream
        return box.isFocused() || !box.getValue().isEmpty();
    }

    @Nullable
    private static EditBox recipeSearchBox(RecipeBookComponent book) {
        if (!recipeSearchResolved) {
            recipeSearchResolved = true;
            try {
                Field f = RecipeBookComponent.class.getDeclaredField("searchBox");
                f.setAccessible(true);
                recipeSearchField = f;
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("[DungeonTrain] live: recipe book search box not readable; menus with a recipe book open stay off stream", e);
            }
        }
        if (recipeSearchField == null) return null;
        try {
            return (EditBox) recipeSearchField.get(book);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
