package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageGenerationConfig;
import games.brennan.dungeontrain.train.CarriageGenerationMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Sub-screen opened from the "Dungeon Train Options…" button injected into
 * Minecraft's {@code CreateWorldScreen}. Captures the world-creation choices
 * into {@link PendingWorldChoices}; the integrated server reads them on start
 * and commits to {@link games.brennan.dungeontrain.world.DungeonTrainWorldData}.
 *
 * <p>Choices (as of v0.25):</p>
 * <ul>
 *   <li>Start with a train — auto-spawn on first login</li>
 *   <li>Train Y — world height to place the train at</li>
 *   <li>Carriage length / width / height — per-world footprint
 *       (default 9 × 7 × 7, clamped via {@link CarriageDims#clamp})</li>
 *   <li>Generation mode — Random, Random Grouped, or Looping</li>
 *   <li>Group size — non-flatbed run length for Random Grouped mode</li>
 * </ul>
 */
public final class DungeonTrainOptionsScreen extends Screen {

    private static final int FIELD_WIDTH = 120;
    private static final int FIELD_HEIGHT = 20;
    private static final int DIM_FIELD_WIDTH = 40;
    private static final int ROW_GAP = 26;
    private static final int LABEL_OFFSET = 110;

    private final Screen parent;

    private Checkbox startsWithTrainBox;
    private EditBox trainYField;
    private EditBox lengthField;
    private EditBox widthField;
    private EditBox heightField;
    private CycleButton<CarriageGenerationMode> modeButton;
    private EditBox groupSizeField;

    public DungeonTrainOptionsScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.options.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int topY = this.height / 2 - 110;

        boolean initialChecked = PendingWorldChoices.isPresent()
            ? PendingWorldChoices.startsWithTrain()
            : true;
        int initialY = PendingWorldChoices.isPresent()
            ? PendingWorldChoices.trainY()
            : DungeonTrainConfig.DEFAULT_TRAIN_Y;
        CarriageDims initialDims = PendingWorldChoices.isPresent()
            ? PendingWorldChoices.dims()
            : CarriageDims.DEFAULT;
        CarriageGenerationMode initialMode = PendingWorldChoices.isPresent()
            ? PendingWorldChoices.generationMode()
            : DungeonTrainConfig.DEFAULT_GENERATION_MODE;
        int initialGroupSize = PendingWorldChoices.isPresent()
            ? PendingWorldChoices.groupSize()
            : DungeonTrainConfig.DEFAULT_GROUP_SIZE;

        // Row 1 — starts-with-train checkbox.
        startsWithTrainBox = Checkbox.builder(
                Component.translatable("gui.dungeontrain.options.starts_with_train"),
                this.font)
            .pos(centerX - 100, topY)
            .selected(initialChecked)
            .build();
        addRenderableWidget(startsWithTrainBox);

        // Row 2 — train Y.
        trainYField = new EditBox(this.font, centerX + 10, topY + ROW_GAP,
            FIELD_WIDTH, FIELD_HEIGHT,
            Component.translatable("gui.dungeontrain.options.train_y"));
        trainYField.setValue(Integer.toString(initialY));
        trainYField.setFilter(DungeonTrainOptionsScreen::isSignedIntegerInput);
        addRenderableWidget(trainYField);

        // Row 3 — three side-by-side dim fields: L × W × H. Each field is
        // narrow so all three fit on one row under a single label.
        int dimsRowY = topY + ROW_GAP * 2;
        int dimsStartX = centerX + 10;
        lengthField = makeDimField(dimsStartX, dimsRowY, initialDims.length(), "length");
        widthField = makeDimField(dimsStartX + DIM_FIELD_WIDTH + 10, dimsRowY, initialDims.width(), "width");
        heightField = makeDimField(dimsStartX + (DIM_FIELD_WIDTH + 10) * 2, dimsRowY, initialDims.height(), "height");

        // Row 4 — generation mode cycle button.
        modeButton = CycleButton.<CarriageGenerationMode>builder(DungeonTrainOptionsScreen::modeLabel)
            .withValues(CarriageGenerationMode.values())
            .withInitialValue(initialMode)
            .displayOnlyValue()
            .create(
                centerX + 10, topY + ROW_GAP * 3,
                FIELD_WIDTH, FIELD_HEIGHT,
                Component.translatable("gui.dungeontrain.options.generation_mode"),
                (btn, value) -> refreshGroupSizeVisibility()
            );
        addRenderableWidget(modeButton);

        // Row 5 — group size, narrow EditBox (only relevant for RANDOM_GROUPED).
        groupSizeField = new EditBox(this.font, centerX + 10, topY + ROW_GAP * 4,
            DIM_FIELD_WIDTH, FIELD_HEIGHT,
            Component.translatable("gui.dungeontrain.options.group_size"));
        groupSizeField.setValue(Integer.toString(initialGroupSize));
        groupSizeField.setFilter(DungeonTrainOptionsScreen::isPositiveIntegerInput);
        addRenderableWidget(groupSizeField);
        refreshGroupSizeVisibility();

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> saveAndClose())
            .bounds(centerX - 105, topY + ROW_GAP * 6 + 10, 100, 20)
            .build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
            .bounds(centerX + 5, topY + ROW_GAP * 6 + 10, 100, 20)
            .build());
    }

    private EditBox makeDimField(int x, int y, int initial, String suffix) {
        EditBox box = new EditBox(this.font, x, y, DIM_FIELD_WIDTH, FIELD_HEIGHT,
            Component.translatable("gui.dungeontrain.options.carriage_" + suffix));
        box.setValue(Integer.toString(initial));
        box.setFilter(DungeonTrainOptionsScreen::isPositiveIntegerInput);
        addRenderableWidget(box);
        return box;
    }

    /**
     * Group size only applies to RANDOM_GROUPED. Hide the field on other
     * modes so the user doesn't wonder whether their value is respected.
     */
    private void refreshGroupSizeVisibility() {
        if (groupSizeField == null || modeButton == null) return;
        groupSizeField.setVisible(modeButton.getValue() == CarriageGenerationMode.RANDOM_GROUPED);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int topY = this.height / 2 - 110;

        graphics.drawCenteredString(this.font, this.title, centerX, topY - 30, 0xFFFFFFFF);

        graphics.drawString(this.font,
            Component.translatable("gui.dungeontrain.options.train_y"),
            centerX - LABEL_OFFSET, topY + ROW_GAP + 6, 0xFFFFFFFF);

        graphics.drawString(this.font,
            Component.translatable("gui.dungeontrain.options.carriage_size"),
            centerX - LABEL_OFFSET, topY + ROW_GAP * 2 + 6, 0xFFFFFFFF);

        graphics.drawString(this.font,
            Component.translatable("gui.dungeontrain.options.generation_mode"),
            centerX - LABEL_OFFSET, topY + ROW_GAP * 3 + 6, 0xFFFFFFFF);

        if (modeButton != null && modeButton.getValue() == CarriageGenerationMode.RANDOM_GROUPED) {
            graphics.drawString(this.font,
                Component.translatable("gui.dungeontrain.options.group_size"),
                centerX - LABEL_OFFSET, topY + ROW_GAP * 4 + 6, 0xFFFFFFFF);
        }

        // Live preview of the clamped carriage footprint. Reads current field
        // values and echoes what will actually be stored — gives instant
        // feedback when the user types an out-of-range value and sees it
        // clamp to the legal floor/ceiling.
        CarriageDims preview = previewDims();
        String previewText = "Preview: " + preview.length() + " × " + preview.width() + " × " + preview.height()
            + "  (L × W × H)";
        graphics.drawCenteredString(this.font, previewText,
            centerX, topY + ROW_GAP * 5 + 4, 0xFFAAFFAA);

        String rangeHint = "Train Y "
            + DungeonTrainConfig.MIN_TRAIN_Y + "–" + DungeonTrainConfig.MAX_TRAIN_Y
            + "  ·  L " + CarriageDims.MIN_LENGTH + "–" + CarriageDims.MAX_LENGTH
            + "  ·  W " + CarriageDims.MIN_WIDTH + "–" + CarriageDims.MAX_WIDTH
            + "  ·  H " + CarriageDims.MIN_HEIGHT + "–" + CarriageDims.MAX_HEIGHT
            + "  ·  Group " + CarriageGenerationConfig.MIN_GROUP_SIZE + "–" + CarriageGenerationConfig.MAX_GROUP_SIZE;
        graphics.drawCenteredString(this.font, rangeHint,
            centerX, topY + ROW_GAP * 5 + 18, 0xFFAAAAAA);
    }

    private CarriageDims previewDims() {
        int l = parseIntOr(lengthField.getValue(), CarriageDims.DEFAULT_LENGTH);
        int w = parseIntOr(widthField.getValue(), CarriageDims.DEFAULT_WIDTH);
        int h = parseIntOr(heightField.getValue(), CarriageDims.DEFAULT_HEIGHT);
        return CarriageDims.clamp(l, w, h);
    }

    private void saveAndClose() {
        Integer y = parseIntOrNull(trainYField.getValue());
        if (y == null) return;
        int clamped = Math.max(DungeonTrainConfig.MIN_TRAIN_Y,
            Math.min(DungeonTrainConfig.MAX_TRAIN_Y, y));
        CarriageDims dims = previewDims();

        int groupSize = parseIntOr(groupSizeField.getValue(), CarriageGenerationConfig.DEFAULT_GROUP_SIZE);
        int clampedGroupSize = Math.max(CarriageGenerationConfig.MIN_GROUP_SIZE,
            Math.min(CarriageGenerationConfig.MAX_GROUP_SIZE, groupSize));
        CarriageGenerationMode mode = modeButton.getValue();

        PendingWorldChoices.set(clamped, startsWithTrainBox.selected(), dims, mode, clampedGroupSize);
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private static Component modeLabel(CarriageGenerationMode mode) {
        return Component.translatable("gui.dungeontrain.options.mode." + mode.name().toLowerCase(Locale.ROOT));
    }

    private static boolean isSignedIntegerInput(String s) {
        if (s.isEmpty() || s.equals("-")) return true;
        int start = s.charAt(0) == '-' ? 1 : 0;
        for (int i = start; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isPositiveIntegerInput(String s) {
        if (s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
