package games.brennan.dungeontrain.client.survey;

import games.brennan.discordpresence.survey.SurveyRegistry;
import games.brennan.dungeontrain.client.BugLogReporter;
import games.brennan.dungeontrain.client.ClientLanguage;
import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.policy.AiPolicyScreen;
import games.brennan.dungeontrain.client.version.LauncherDetector;
import games.brennan.dungeontrain.client.version.compare.UpdatePage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The client side of a {@link KnownFeature} note: which features this install can answer, the words
 * of each note, and its button. Shared by the death-screen page ({@code NarrativeDeathScreen}) and
 * the {@code /feedback} card ({@link KnownFeatureNoteScreen}), so the two always say the same thing.
 */
public final class KnownFeatureNotes {

    private static final String KEY = "gui.dungeontrain.known_feature.";
    private static final String AI_KEY = "gui.dungeontrain.nps_ai_note.";
    private static final String ROADMAP_URL = UpdatePage.BASE_URL + "#roadmap";

    private static final String MOD_TRASH_SLOT = "trashslot";
    private static final String MOD_SORTING = "nemos_inventory_sorting";
    private static final String MOD_JEI = "jei";
    /**
     * Companions only the modpack ships switched on. Their presence is how a pack install is told
     * apart from a hand-made one, where "it's already in the pack" would be untrue.
     */
    private static final List<String> PACK_MARKERS = List.of(MOD_TRASH_SLOT, "enchdesc");

    private KnownFeatureNotes() {}

    /** The notes a submitted survey answer earns. Bug reports describe problems, so they earn none. */
    public static List<KnownFeature> forAnswer(String formId, int npsScore, String comment) {
        if (BugLogReporter.BUG_REPORT_ID.equals(formId)) return List.of();
        int score = SurveyRegistry.NPS_ID.equals(formId) ? npsScore : -1;
        return KnownFeatureDetector.detect(score, comment, ClientLanguage.selected(), available());
    }

    /** The features this install can answer. */
    public static Set<KnownFeature> available() {
        Set<KnownFeature> out = EnumSet.of(KnownFeature.AI, KnownFeature.SAVE_QUIT,
                KnownFeature.BACKPACK, KnownFeature.MULTIPLAYER);
        ModList mods = ModList.get();
        if (mods.isLoaded(MOD_TRASH_SLOT)) out.add(KnownFeature.TRASH_SLOT);
        boolean pack = PACK_MARKERS.stream().allMatch(mods::isLoaded);
        if (pack || mods.isLoaded(MOD_SORTING)) out.add(KnownFeature.SORTING);
        if (pack || mods.isLoaded(MOD_JEI)) out.add(KnownFeature.JEI);
        return out;
    }

    public static Component title(KnownFeature feature) {
        if (feature == KnownFeature.AI) return Component.translatable(AI_KEY + "title");
        return Component.translatable(KEY + feature.id() + ".title");
    }

    /** The note's paragraphs, top to bottom. */
    public static List<Component> paragraphs(KnownFeature feature) {
        return switch (feature) {
            case AI -> List.of(Component.translatable(AI_KEY + "body.1"),
                    Component.translatable(AI_KEY + "body.2"),
                    Component.translatable(AI_KEY + "signature"));
            case BACKPACK -> List.of(Component.translatable(KEY + "backpack.body.1"),
                    Component.translatable(KEY + "backpack.body.2"));
            case SORTING -> packMod(feature, MOD_SORTING, "Nemo's Inventory Sorting");
            case JEI -> packMod(feature, MOD_JEI, "Just Enough Items");
            case SAVE_QUIT, MULTIPLAYER, TRASH_SLOT -> List.of(Component.translatable(KEY + feature.id() + ".body"));
        };
    }

    /** A mod the pack ships switched off: how to use it when it is on, how to turn it on when not. */
    private static List<Component> packMod(KnownFeature feature, String modId, String modName) {
        if (ModList.get().isLoaded(modId)) return List.of(Component.translatable(KEY + feature.id() + ".on"));
        String launcher = switch (LauncherDetector.source()) {
            case CURSEFORGE -> "curseforge";
            case MODRINTH -> "modrinth";
            default -> "other";
        };
        return List.of(Component.translatable(KEY + "off", modName),
                Component.translatable(KEY + "enable." + launcher, modName));
    }

    /** The button's label, or null for a note without one. */
    public static Component buttonLabel(KnownFeature feature) {
        return switch (feature.action()) {
            case NONE -> null;
            case AI_POLICY -> Component.translatable(AI_KEY + "button.policy");
            case KEY_BINDS -> Component.translatable(KEY + "button.key_binds");
            case ROADMAP -> Component.translatable(KEY + "button.roadmap");
        };
    }

    /** Press the note's button; whatever opens returns to {@code parent}. */
    public static void runAction(KnownFeature feature, Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        switch (feature.action()) {
            case NONE -> { }
            case AI_POLICY -> {
                UiAnalytics.click(UiAnalytics.SURFACE_NPS_AI_NOTE, "policy");
                mc.setScreen(new AiPolicyScreen(parent));
            }
            case KEY_BINDS -> mc.setScreen(new KeyBindsScreen(parent, mc.options));
            case ROADMAP -> ConfirmLinkScreen.confirmLinkNow(parent, ROADMAP_URL);
        }
    }
}
