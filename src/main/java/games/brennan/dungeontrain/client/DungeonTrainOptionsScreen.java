package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Tiny sub-screen opened from the "Dungeon Train Options…" button injected
 * into Minecraft's {@code CreateWorldScreen}. Captures two values into
 * {@link PendingWorldChoices}; the integrated server reads them on start and
 * commits to {@link games.brennan.dungeontrain.world.DungeonTrainWorldData}.
 */
public final class DungeonTrainOptionsScreen extends Screen {

    private static final int FIELD_WIDTH = 120;
    private static final int FIELD_HEIGHT = 20;
    private static final int ROW_GAP = 30;
    private static final int LABEL_OFFSET = 110;

    private final Screen parent;

    private Checkbox startsWithTrainBox;
    private EditBox trainYField;

    public DungeonTrainOptionsScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.options.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int topY = this.height / 2 - 50;

        boolean initialChecked = PendingWorldChoices.isPresent()
            ? PendingWorldChoices.startsWithTrain()
            : true;
        int initialY = PendingWorldChoices.isPresent()
            ? PendingWorldChoices.trainY()
            : DungeonTrainConfig.DEFAULT_TRAIN_Y;

        startsWithTrainBox = new Checkbox(
            centerX - 100, topY,
            200, FIELD_HEIGHT,
            Component.translatable("gui.dungeontrain.options.starts_with_train"),
            initialChecked
        );
        addRenderableWidget(startsWithTrainBox);

        trainYField = new EditBox(this.font, centerX + 10, topY + ROW_GAP,
            FIELD_WIDTH, FIELD_HEIGHT,
            Component.translatable("gui.dungeontrain.options.train_y"));
        trainYField.setValue(Integer.toString(initialY));
        trainYField.setFilter(DungeonTrainOptionsScreen::isSignedIntegerInput);
        addRenderableWidget(trainYField);

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> saveAndClose())
            .bounds(centerX - 105, topY + ROW_GAP * 2 + 10, 100, 20)
            .build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
            .bounds(centerX + 5, topY + ROW_GAP * 2 + 10, 100, 20)
            .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int topY = this.height / 2 - 50;

        graphics.drawCenteredString(this.font, this.title, centerX, topY - 30, 0xFFFFFFFF);

        graphics.drawString(this.font,
            Component.translatable("gui.dungeontrain.options.train_y"),
            centerX - LABEL_OFFSET, topY + ROW_GAP + 6, 0xFFFFFFFF);

        String rangeHint = "Train Y range " + DungeonTrainConfig.MIN_TRAIN_Y
            + " to " + DungeonTrainConfig.MAX_TRAIN_Y;
        graphics.drawCenteredString(this.font, rangeHint,
            centerX, topY + ROW_GAP * 2 - 10, 0xFFAAAAAA);
    }

    private void saveAndClose() {
        Integer y = parseIntOrNull(trainYField.getValue());
        if (y == null) return;
        int clamped = Math.max(DungeonTrainConfig.MIN_TRAIN_Y,
            Math.min(DungeonTrainConfig.MAX_TRAIN_Y, y));
        PendingWorldChoices.set(clamped, startsWithTrainBox.selected());
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private static boolean isSignedIntegerInput(String s) {
        if (s.isEmpty() || s.equals("-")) return true;
        int start = s.charAt(0) == '-' ? 1 : 0;
        for (int i = start; i < s.length(); i++) {
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
}
