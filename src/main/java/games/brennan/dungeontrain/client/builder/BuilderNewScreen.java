package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderLabels;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderNewOptions;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.editor.EditorTemplateLists;
import games.brennan.dungeontrain.net.BuilderNewPacket;
import games.brennan.dungeontrain.net.BuilderRenamePacket;
import games.brennan.dungeontrain.net.BuilderSavePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Make a new thing: name it, say what kind it is, and say what it starts from.
 *
 * <p>Replaces the pause menu outright rather than drilling into the world-space X menu, whose New
 * flow is a list of typing prompts — you have to know what "Blank / Current / Standard" mean
 * before you can pick one. Here the mode is the same picture you clicked on the Train Builder
 * screen, and below it there is exactly one list to pick from, whose contents follow what you're
 * making rather than making you learn template ids.</p>
 *
 * <p>Which list that is lives in {@link BuilderNewOptions#copySourceFor} — this class is layout,
 * wiring, and the registry lookups the list needs.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderNewScreen extends Screen {

    private static final int FIELD_WIDTH = 200;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    private static final int LABEL_GAP = 8;
    private static final int BUTTON_WIDTH = 98;
    /** Tall enough for the tile screenshot to read as a picture rather than a texture swatch. */
    private static final int ART_HEIGHT = 44;

    /**
     * Which question the screen is asking. Same controls either way — a save is just a New whose
     * choices were already made, so re-asking them would be offering to change something the
     * builder isn't changing.
     */
    public enum Mode { NEW, SAVE_AS }

    private final Screen lastScreen;
    private final Mode screenMode;

    private BuilderMode mode;
    private BuilderNewOptions.SubType subType = BuilderNewOptions.SubType.WHOLE_CARRIAGE;
    /** Only meaningful for the Parts sub type; travels as the packet's {@code typeId}. */
    private String partKind = BuilderNewOptions.PART_KINDS.get(0);
    private String copyFrom = "";
    private String name = "";

    private EditBox nameField;
    private Button createButton;
    private int hintY;

    public BuilderNewScreen(Screen lastScreen) {
        this(lastScreen, Mode.NEW);
    }

    private BuilderNewScreen(Screen lastScreen, Mode screenMode) {
        super(Component.translatable(screenMode == Mode.SAVE_AS
                ? "gui.dungeontrain.builder.new.save_title"
                : "gui.dungeontrain.builder.new.title"));
        this.lastScreen = lastScreen;
        this.screenMode = screenMode;
        this.mode = BuilderMode.fromId(BuilderBoundsState.modeId())
                .orElse(BuilderMode.TRAIN_OUTSIDE);
    }

    /**
     * The naming step for a build that hasn't got a name yet.
     *
     * <p>Everything but the name is prefilled from what the build already is, and locked: this is
     * the moment the template gets written, not a chance to make it something else.</p>
     */
    public static BuilderNewScreen saveAs(Screen lastScreen) {
        return new BuilderNewScreen(lastScreen, Mode.SAVE_AS);
    }

    @Override
    protected void init() {
        // Dependent controls: rather than juggle visibility, the screen rebuilds itself whenever a
        // choice changes what the rows below it should be. Same approach vanilla takes.
        int fieldX = this.width / 2 - FIELD_WIDTH / 2;
        int y = this.height / 2 - 88;

        this.nameField = new EditBox(this.font, fieldX, y, FIELD_WIDTH, ROW_HEIGHT,
                Component.translatable("gui.dungeontrain.builder.new.name"));
        this.nameField.setMaxLength(32);
        this.nameField.setValue(name);
        this.nameField.setResponder(value -> {
            name = value.toLowerCase(Locale.ROOT);
            refreshCreateEnabled();
        });
        this.addRenderableWidget(this.nameField);
        y += ROW_HEIGHT + ROW_GAP;
        this.hintY = y;
        y += LABEL_GAP + ROW_GAP;

        // The mode as the picture it was on the picker screen, so the two screens read as the same
        // choice rather than as a list of words that happens to match a wall of images.
        BuilderModeArtButton art = new BuilderModeArtButton(fieldX, y, FIELD_WIDTH, ART_HEIGHT, mode,
                this::selectedPhoto,
                value -> {
                    mode = value;
                    copyFrom = "";   // a different mode lists different things
                    rebuild();
                });
        art.active = screenMode == Mode.NEW;
        this.addRenderableWidget(art);
        y += ART_HEIGHT + ROW_GAP;

        // Two per row. A control with no partner takes the whole row rather than leaving a hole
        // beside it, which is the common case — only Parts has a second control to pair.
        List<AbstractWidget> options = buildOptionControls(fieldX, y);
        for (int i = 0; i < options.size(); i += 2) {
            AbstractWidget left = options.get(i);
            AbstractWidget right = i + 1 < options.size() ? options.get(i + 1) : null;
            left.setY(y);
            if (right == null) {
                left.setX(fieldX);
                left.setWidth(FIELD_WIDTH);
            } else {
                left.setX(fieldX);
                left.setWidth(BuilderPauseMenuLayout.leftHalfWidth(FIELD_WIDTH));
                right.setX(BuilderPauseMenuLayout.rightHalfX(fieldX, FIELD_WIDTH));
                right.setY(y);
                right.setWidth(BuilderPauseMenuLayout.rightHalfWidth(FIELD_WIDTH));
            }
            y += ROW_HEIGHT + ROW_GAP;
        }
        if (screenMode == Mode.SAVE_AS) {
            // Prefilled and inert: these describe the build being saved, not choices to remake.
            options.forEach(w -> w.active = false);
        }
        options.forEach(this::addRenderableWidget);

        y += LABEL_GAP;
        this.createButton = Button.builder(Component.translatable(screenMode == Mode.SAVE_AS
                                ? "gui.dungeontrain.builder.new.save"
                                : "gui.dungeontrain.builder.new.create"),
                        b -> confirm())
                .bounds(fieldX, y, BUTTON_WIDTH, ROW_HEIGHT).build();
        this.addRenderableWidget(this.createButton);
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
                .bounds(fieldX + FIELD_WIDTH - BUTTON_WIDTH, y, BUTTON_WIDTH, ROW_HEIGHT).build());

        refreshCreateEnabled();
    }

    /**
     * The sub type and the picker, in reading order — with the part-kind control ahead of the
     * picker when there is one, so the pair shares a row. Built at a placeholder position; the
     * caller lays them out.
     */
    private List<AbstractWidget> buildOptionControls(int x, int y) {
        List<AbstractWidget> controls = new ArrayList<>();
        if (!BuilderNewOptions.hasSubTypes(mode)) {
            return controls;   // track modes: the mode and the name are the whole question
        }

        controls.add(CycleButton.builder(BuilderNewScreen::subTypeLabel)
                .withValues(BuilderNewOptions.SubType.values())
                .withInitialValue(subType)
                .displayOnlyValue()
                .create(x, y, FIELD_WIDTH, ROW_HEIGHT,
                        Component.translatable("gui.dungeontrain.builder.new.subtype"),
                        (button, value) -> {
                            subType = value;
                            copyFrom = "";
                            rebuild();
                        }));

        if (BuilderNewOptions.showsPartKind(mode, subType)) {
            controls.add(CycleButton.builder(BuilderNewScreen::plainLabel)
                    .withValues(BuilderNewOptions.PART_KINDS)
                    .withInitialValue(partKind)
                    .displayOnlyValue()
                    .create(x, y, FIELD_WIDTH, ROW_HEIGHT,
                            Component.translatable("gui.dungeontrain.builder.new.part_kind"),
                            (button, value) -> {
                                partKind = value;
                                copyFrom = "";   // each kind has its own templates
                                rebuild();
                            }));
        }

        BuilderNewOptions.CopySource copySource = BuilderNewOptions.copySourceFor(mode, subType);
        List<String> values = copyCandidates();
        if (!values.isEmpty()) {
            if (!values.contains(copyFrom)) {
                copyFrom = values.get(0);   // the first entry is the default, by construction
            }
            controls.add(CycleButton.<String>builder(value -> pickerLabel(copySource, value))
                    .withValues(values)
                    .withInitialValue(copyFrom)
                    .displayOnlyValue()
                    .create(x, y, FIELD_WIDTH, ROW_HEIGHT,
                            Component.translatable("gui.dungeontrain.builder.new.copy_from"),
                            (button, value) -> copyFrom = value));
        }
        return controls;
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    /**
     * The photo of whatever the picker currently names, or null so the mode's tile art stands in.
     *
     * <p>Asked for every frame rather than resolved once: cycling the picker doesn't rebuild the
     * screen, so this is what makes the picture follow the selection. {@code BuilderPhotoTextures}
     * caches both hits and misses, so the repetition costs a map lookup.</p>
     */
    private BuilderPhotoTextures.Photo selectedPhoto() {
        if (copyFrom == null || copyFrom.isEmpty()) {
            return null;
        }
        return switch (BuilderNewOptions.copySourceFor(mode, subType)) {
            case CARRIAGES -> BuilderPhotoTextures.textureFor(
                    BuilderPhotoPaths.Kind.CARRIAGE, copyFrom, null);
            case CONTENTS -> BuilderPhotoTextures.textureFor(
                    BuilderPhotoPaths.Kind.CONTENTS, copyFrom, null);
            case PARTS -> BuilderPhotoTextures.textureFor(
                    BuilderPhotoPaths.Kind.PART, copyFrom, partKindValue());
            // A stage isn't a template and has no photo of its own. The nearest useful picture is
            // the build already in the world, once it has a name to have been saved under.
            case STAGES -> BuilderBoundsState.isDraft() ? null : BuilderPhotoTextures.textureFor(
                    BuilderPhotoPaths.Kind.CARRIAGE, BuilderBoundsState.buildName(), null);
            case NONE -> null;
        };
    }

    /**
     * <b>New</b> accepts an empty name — you rarely know what a thing is called before you've made
     * it, and an unnamed build is simply a draft with nothing written to disk. <b>Save-as</b> is
     * the moment the template is written, so there a name is required. Either way an invalid name
     * blocks the button, because the ids these become can't hold spaces or capitals.
     */
    private void refreshCreateEnabled() {
        if (this.createButton != null) {
            this.createButton.active = screenMode == Mode.SAVE_AS
                    ? BuilderNewOptions.isValidName(name)
                    : name.isEmpty() || BuilderNewOptions.isValidName(name);
        }
    }

    private void confirm() {
        Minecraft.getInstance().setScreen(null);
        if (screenMode == Mode.SAVE_AS) {
            // Name the draft, then save it — one round trip each, in that order.
            DungeonTrainNet.sendToServer(new BuilderRenamePacket(name));
            DungeonTrainNet.sendToServer(new BuilderSavePacket());
            return;
        }
        DungeonTrainNet.sendToServer(new BuilderNewPacket(
                mode.id(), subType.id(), partKind, copyFrom, name));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 108, 0xFFFFFF);
        if (!name.isEmpty() && !BuilderNewOptions.isValidName(name)) {
            g.drawCenteredString(this.font,
                    Component.translatable("gui.dungeontrain.builder.new.invalid_name"),
                    this.width / 2, hintY, 0xFF7070);
        } else if (name.isEmpty() && screenMode == Mode.NEW) {
            g.drawCenteredString(this.font,
                    Component.translatable("gui.dungeontrain.builder.new.unnamed"),
                    this.width / 2, hintY, 0xA0A0A0);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(lastScreen);
    }

    /**
     * What the single picker lists, with the default first.
     *
     * <p>Every list comes from {@link EditorTemplateLists} — the same call the Train Editor's own
     * template list makes. The Builder is a front end onto the Editor's files, so a template it
     * can't see is a template it would silently fail to copy.</p>
     */
    private List<String> copyCandidates() {
        return switch (BuilderNewOptions.copySourceFor(mode, subType)) {
            case STAGES -> EditorTemplateLists.stages();
            case CARRIAGES -> currentFirst(new ArrayList<>(EditorTemplateLists.carriages()));
            case CONTENTS -> currentFirst(contentsWithMembers());
            case PARTS -> EditorTemplateLists.parts(partKindValue());
            case NONE -> List.of();
        };
    }

    /**
     * Top-level contents, each followed by its sub-variants.
     *
     * <p>A group's members are real things to copy — {@code maze}'s copper room is a room — but
     * they aren't siblings of {@code maze}, and listing them flat (as this screen used to) hides
     * that entirely. The Editor drills into a second screen for them; one cycle button has no
     * second screen to drill into, so they're inlined and marked.</p>
     */
    private static List<String> contentsWithMembers() {
        List<String> out = new ArrayList<>();
        for (String id : EditorTemplateLists.contents()) {
            out.add(id);
            out.addAll(EditorTemplateLists.contentsMembers(id));
        }
        return out;
    }

    private CarriagePartKind partKindValue() {
        for (CarriagePartKind kind : CarriagePartKind.values()) {
            if (kind.name().toLowerCase(Locale.ROOT).equals(partKind)) {
                return kind;
            }
        }
        return CarriagePartKind.values()[0];
    }

    /** Whatever the builder is standing in is the likeliest thing to copy, so lead with it. */
    private static List<String> currentFirst(List<String> ids) {
        String current = EditorStatusHudOverlay.modelId();
        if (current != null && !current.isEmpty() && ids.remove(current)) {
            ids.add(0, current);
        }
        return ids;
    }

    private static Component subTypeLabel(BuilderNewOptions.SubType value) {
        return Component.translatable(value.labelKey());
    }

    /**
     * Template, stage and part-kind ids are authored strings rather than translation keys, so they
     * get read rather than looked up — but read as words, not as filenames.
     */
    private static Component plainLabel(String value) {
        return Component.literal(BuilderLabels.pretty(value));
    }

    /**
     * The picker's label. A contents sub-variant is shown under its parent, so a row reads as
     * {@code Maze › Copper} rather than as a top-level {@code Copper} that came from nowhere.
     *
     * <p>The {@code source} argument is load-bearing, not decoration: an id is only unique within
     * its own store, and several of them collide across stores — {@code quartz} is both a Stage and
     * one of {@code maze}'s sub-variants. Looking the parent up without knowing which list the value
     * came from labelled the <em>quartz stage</em> as "Maze › Quartz".</p>
     */
    private static Component pickerLabel(BuilderNewOptions.CopySource source, String value) {
        if (source == BuilderNewOptions.CopySource.CONTENTS) {
            Optional<String> parent = EditorTemplateLists.contentsParentOf(value);
            if (parent.isPresent()) {
                return Component.literal(
                        BuilderLabels.pretty(parent.get()) + " › " + BuilderLabels.pretty(value));
            }
        }
        return Component.literal(BuilderLabels.pretty(value));
    }
}
