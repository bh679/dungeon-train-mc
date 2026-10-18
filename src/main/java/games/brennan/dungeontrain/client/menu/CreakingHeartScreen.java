package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.compat.CreakingHeartSettings;
import games.brennan.dungeontrain.compat.CreakingHeartSettingsHolder;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * One creaking heart's settings, opened by right-clicking it in Creative
 * ({@code client/CreakingHeartMenuOpener}): Works by Day, Works at Night, Number of Creaking, and
 * the Stage it is linked to, or (Custom) its own Diff-Level range and bands, laid out like
 * {@link StageEditScreen}. Every row runs a {@code /dungeontrain editor creakingheart} command;
 * values are read back from the client's copy of the heart each rebuild.
 */
public final class CreakingHeartScreen implements MenuScreen {

    private final BlockPos pos;
    private final String prefix;

    public CreakingHeartScreen(BlockPos pos) {
        this.pos = pos.immutable();
        this.prefix = "dungeontrain editor creakingheart " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    @Override
    public String title() {
        return MenuLang.t("creaking_heart.title");
    }

    @Override
    public List<CommandMenuEntry> entries() {
        CreakingHeartSettings s = current();
        List<CommandMenuEntry> out = new ArrayList<>();
        out.add(new CommandMenuEntry.Toggle(MenuLang.t("creaking_heart.day"), s.day(),
            prefix + " day on", prefix + " day off"));
        out.add(new CommandMenuEntry.Toggle(MenuLang.t("creaking_heart.night"), s.night(),
            prefix + " night on", prefix + " night off"));
        out.add(CommandMenuEntry.Number.of(MenuLang.t("creaking_heart.count", s.count()),
            MenuLang.t("creaking_heart.count_title"), prefix + " count", s.count(),
            CreakingHeartSettings.MIN_COUNT, CreakingHeartSettings.MAX_COUNT, false));

        out.add(new CommandMenuEntry.DrillIn(MenuLang.t("creaking_heart.stage", stageName(s.stageId())),
            StagePickerScreen.forCommand(token -> prefix + " stage " + token, s.stageId())));
        if (s.stageId().isEmpty()) addCustomGate(out, s.gate());

        out.add(new CommandMenuEntry.Back(MenuLang.t("common.back")));
        return out;
    }

    /** Min / Max Diff-Level and one toggle per band, as on a Stage. */
    private void addCustomGate(List<CommandMenuEntry> out, TemplateGate gate) {
        int min = gate.minLevel();
        int max = gate.maxLevel();
        out.add(EditorMenuScreen.levelNumber(MenuLang.t("editor.min_level", min),
            prefix + " minlevel", "minlevel", min, min));
        out.add(EditorMenuScreen.levelNumber(MenuLang.t("editor.max_level",
                max < 0 ? MenuLang.t("editor.max_level_all") : Integer.toString(max)),
            prefix + " maxlevel", "maxlevel", max, min));
        for (TrainPhase p : TrainPhase.values()) {
            out.add(new CommandMenuEntry.Toggle(MenuLang.named("phase", p.token(), p.displayName()),
                gate.phases().contains(p),
                prefix + " phase " + p.token() + " on", prefix + " phase " + p.token() + " off",
                true, prefix + " phase " + p.token() + " others"));
        }
    }

    private CreakingHeartSettings current() {
        var level = Minecraft.getInstance().level;
        return level == null ? CreakingHeartSettings.DEFAULT : CreakingHeartSettingsHolder.at(level, pos);
    }

    private static String stageName(String stageId) {
        if (stageId.isEmpty()) return MenuLang.t("creaking_heart.custom");
        ClientStages.Info info = ClientStages.byId(stageId);
        return info == null ? stageId : info.name();
    }
}
