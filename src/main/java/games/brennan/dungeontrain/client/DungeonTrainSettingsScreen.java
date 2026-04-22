package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.List;

/**
 * Mods-menu config screen for Dungeon Train — edit rolling-window carriage
 * count and train speed, persist to serverconfig/dungeontrain-server.toml, and
 * apply live to any spawned train on the integrated server.
 */
public final class DungeonTrainSettingsScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FIELD_WIDTH = 120;
    private static final int FIELD_HEIGHT = 20;
    private static final int ROW_GAP = 30;
    private static final int LABEL_OFFSET = 110;

    private final Screen parent;
    private EditBox carriagesField;
    private EditBox speedField;
    private EditBox trainYField;

    public DungeonTrainSettingsScreen(Screen parent) {
        super(Component.literal("Dungeon Train Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int topY = this.height / 2 - 60;

        carriagesField = new EditBox(this.font, centerX + 10, topY, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Carriages"));
        carriagesField.setValue(Integer.toString(DungeonTrainConfig.getNumCarriages()));
        carriagesField.setFilter(DungeonTrainSettingsScreen::isIntegerInput);
        addRenderableWidget(carriagesField);

        speedField = new EditBox(this.font, centerX + 10, topY + ROW_GAP, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Speed"));
        speedField.setValue(Double.toString(DungeonTrainConfig.getSpeed()));
        speedField.setFilter(DungeonTrainSettingsScreen::isDecimalInput);
        addRenderableWidget(speedField);

        trainYField = new EditBox(this.font, centerX + 10, topY + ROW_GAP * 2, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Train Y"));
        trainYField.setValue(Integer.toString(DungeonTrainConfig.getTrainY()));
        trainYField.setFilter(DungeonTrainSettingsScreen::isSignedIntegerInput);
        addRenderableWidget(trainYField);

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> saveAndClose())
                .bounds(centerX - 105, topY + ROW_GAP * 3 + 10, 100, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(centerX + 5, topY + ROW_GAP * 3 + 10, 100, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int topY = this.height / 2 - 60;

        graphics.drawCenteredString(this.font, this.title, centerX, topY - 40, 0xFFFFFFFF);

        graphics.drawString(this.font, "Carriages:", centerX - LABEL_OFFSET, topY + 6, 0xFFFFFFFF);
        graphics.drawString(this.font, "Speed (m/s):", centerX - LABEL_OFFSET, topY + ROW_GAP + 6, 0xFFFFFFFF);
        graphics.drawString(this.font, "Train Y:", centerX - LABEL_OFFSET, topY + ROW_GAP * 2 + 6, 0xFFFFFFFF);

        String rangeHint = "Carriages " + DungeonTrainConfig.MIN_CARRIAGES + "-" + DungeonTrainConfig.MAX_CARRIAGES
                + ", Speed " + DungeonTrainConfig.MIN_SPEED + "-" + DungeonTrainConfig.MAX_SPEED
                + ", Train Y " + DungeonTrainConfig.MIN_TRAIN_Y + "-" + DungeonTrainConfig.MAX_TRAIN_Y;
        graphics.drawCenteredString(this.font, rangeHint, centerX, topY + ROW_GAP * 3 - 10, 0xFFAAAAAA);

        graphics.drawCenteredString(this.font,
                "Train Y applies to next spawn only.",
                centerX, topY + ROW_GAP * 3 + 36, 0xFFAAAAAA);

        if (Minecraft.getInstance().getSingleplayerServer() == null) {
            graphics.drawCenteredString(this.font,
                    "Note: live train updates require an active world.",
                    centerX, topY + ROW_GAP * 3 + 50, 0xFFFFAA55);
        }
    }

    private void saveAndClose() {
        Integer carriages = parseIntOrNull(carriagesField.getValue());
        Double speed = parseDoubleOrNull(speedField.getValue());
        Integer trainY = parseIntOrNull(trainYField.getValue());

        if (carriages == null || speed == null || trainY == null) {
            LOGGER.warn("[DungeonTrain] Settings screen: invalid input carriages={} speed={} trainY={}",
                    carriagesField.getValue(), speedField.getValue(), trainYField.getValue());
            return;
        }

        DungeonTrainConfig.setNumCarriages(carriages);
        DungeonTrainConfig.setSpeed(speed);
        DungeonTrainConfig.setTrainY(trainY);

        int effectiveCarriages = DungeonTrainConfig.getNumCarriages();
        double effectiveSpeed = DungeonTrainConfig.getSpeed();
        int effectiveTrainY = DungeonTrainConfig.getTrainY();

        applyToLiveTrains(effectiveCarriages, effectiveSpeed);
        LOGGER.info("[DungeonTrain] Settings saved: carriages={} speed={} trainY={}",
                effectiveCarriages, effectiveSpeed, effectiveTrainY);

        onClose();
    }

    private static void applyToLiveTrains(int carriages, double speed) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;

        Vector3d velocity = new Vector3d(speed, 0.0, 0.0);
        server.execute(() -> {
            int updated = 0;
            for (ServerLevel level : server.getAllLevels()) {
                List<TrainTransformProvider> providers = TrainAssembler.getActiveTrainProviders(level);
                for (TrainTransformProvider p : providers) {
                    p.setCount(carriages);
                    p.setTargetVelocity(velocity);
                    updated++;
                }
            }
            LOGGER.info("[DungeonTrain] Live update applied to {} train(s)", updated);
        });
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private static boolean isIntegerInput(String s) {
        if (s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isSignedIntegerInput(String s) {
        if (s.isEmpty() || s.equals("-")) return true;
        int start = s.charAt(0) == '-' ? 1 : 0;
        for (int i = start; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isDecimalInput(String s) {
        if (s.isEmpty()) return true;
        boolean seenDot = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                if (seenDot) return false;
                seenDot = true;
            } else if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    private static Integer parseIntOrNull(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Double parseDoubleOrNull(String s) {
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
