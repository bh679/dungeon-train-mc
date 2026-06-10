package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.train.CarriageGenerationConfig;
import games.brennan.dungeontrain.train.CarriageGenerationMode;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
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
 * count, train speed, spawn Y, the carriage generation mode / group size, and
 * the PlayerMob spawn rate. Most fields persist to the server config and apply
 * live to any spawned train on the integrated server. The PlayerMob 1-in-N
 * field is two-tier: in a world it sets that world's per-world override
 * ({@link games.brennan.dungeontrain.world.DungeonTrainWorldData}); on the
 * title screen it sets the global default for new worlds
 * ({@link games.brennan.dungeontrain.config.DungeonTrainCommonConfig}).
 */
public final class DungeonTrainSettingsScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FIELD_WIDTH = 120;
    private static final int FIELD_HEIGHT = 20;
    private static final int ROW_GAP = 26;
    private static final int LABEL_OFFSET = 110;

    private final Screen parent;
    private EditBox carriagesField;
    private EditBox speedField;
    private EditBox trainYField;
    private CycleButton<CarriageGenerationMode> modeButton;
    private EditBox playerMobSpawnField;
    private EditBox groupSizeField;

    public DungeonTrainSettingsScreen(Screen parent) {
        super(Component.literal("Dungeon Train Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int topY = this.height / 2 - 80;

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

        modeButton = CycleButton.<CarriageGenerationMode>builder(DungeonTrainSettingsScreen::modeLabel)
                .withValues(CarriageGenerationMode.values())
                .withInitialValue(DungeonTrainConfig.getGenerationMode())
                .displayOnlyValue()
                .create(
                        centerX + 10, topY + ROW_GAP * 3,
                        FIELD_WIDTH, FIELD_HEIGHT,
                        Component.literal("Generation mode"),
                        (btn, value) -> refreshGroupSizeVisibility()
                );
        addRenderableWidget(modeButton);

        playerMobSpawnField = new EditBox(this.font, centerX + 10, topY + ROW_GAP * 4, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("PlayerMob 1-in-N"));
        // Two-tier: in a world the field shows THIS world's effective rate;
        // on the title screen it shows the global default for new worlds.
        playerMobSpawnField.setValue(Integer.toString(currentPlayerMobSpawnOneIn()));
        playerMobSpawnField.setFilter(DungeonTrainSettingsScreen::isIntegerInput);
        addRenderableWidget(playerMobSpawnField);

        groupSizeField = new EditBox(this.font, centerX + 10, topY + ROW_GAP * 5, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Group size"));
        groupSizeField.setValue(Integer.toString(DungeonTrainConfig.getGroupSize()));
        groupSizeField.setFilter(DungeonTrainSettingsScreen::isIntegerInput);
        addRenderableWidget(groupSizeField);
        refreshGroupSizeVisibility();

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> saveAndClose())
                .bounds(centerX - 105, topY + ROW_GAP * 6 + 20, 100, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(centerX + 5, topY + ROW_GAP * 6 + 20, 100, 20)
                .build());
    }

    private void refreshGroupSizeVisibility() {
        if (groupSizeField == null || modeButton == null) return;
        groupSizeField.setVisible(modeButton.getValue() == CarriageGenerationMode.RANDOM_GROUPED);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int topY = this.height / 2 - 80;

        graphics.drawCenteredString(this.font, this.title, centerX, topY - 40, 0xFFFFFFFF);

        graphics.drawString(this.font, "Carriages:", centerX - LABEL_OFFSET, topY + 6, 0xFFFFFFFF);
        graphics.drawString(this.font, "Speed (m/s):", centerX - LABEL_OFFSET, topY + ROW_GAP + 6, 0xFFFFFFFF);
        graphics.drawString(this.font, "Train Y:", centerX - LABEL_OFFSET, topY + ROW_GAP * 2 + 6, 0xFFFFFFFF);
        graphics.drawString(this.font, "Mode:", centerX - LABEL_OFFSET, topY + ROW_GAP * 3 + 6, 0xFFFFFFFF);
        graphics.drawString(this.font, "PlayerMob 1-in-N:", centerX - LABEL_OFFSET, topY + ROW_GAP * 4 + 6, 0xFFFFFFFF);
        if (modeButton != null && modeButton.getValue() == CarriageGenerationMode.RANDOM_GROUPED) {
            graphics.drawString(this.font, "Group size:", centerX - LABEL_OFFSET, topY + ROW_GAP * 5 + 6, 0xFFFFFFFF);
        }

        String rangeHint = "Carriages " + DungeonTrainConfig.MIN_CARRIAGES + "-" + DungeonTrainConfig.MAX_CARRIAGES
                + ", Speed " + DungeonTrainConfig.MIN_SPEED + "-" + DungeonTrainConfig.MAX_SPEED
                + ", Train Y " + DungeonTrainConfig.MIN_TRAIN_Y + "-" + DungeonTrainConfig.MAX_TRAIN_Y
                + ", Group " + CarriageGenerationConfig.MIN_GROUP_SIZE + "-" + CarriageGenerationConfig.MAX_GROUP_SIZE
                + ", PlayerMob " + DungeonTrainCommonConfig.MIN_PLAYER_MOB_SPAWN_ONE_IN + "-" + DungeonTrainCommonConfig.MAX_PLAYER_MOB_SPAWN_ONE_IN;
        graphics.drawCenteredString(this.font, rangeHint, centerX, topY + ROW_GAP * 6 - 4, 0xFFAAAAAA);

        graphics.drawCenteredString(this.font,
                "Train Y applies to next spawn only. Mode affects new carriage placements.",
                centerX, topY + ROW_GAP * 6 + 50, 0xFFAAAAAA);

        boolean inWorld = Minecraft.getInstance().getSingleplayerServer() != null;
        String playerMobScope = inWorld
                ? "PlayerMob 1-in-N applies to THIS world (0 disables, 1 = every group)."
                : "PlayerMob 1-in-N sets the DEFAULT for new worlds (0 disables, 1 = every group).";
        graphics.drawCenteredString(this.font, playerMobScope, centerX, topY + ROW_GAP * 6 + 64, 0xFFAAAAAA);

        if (!inWorld) {
            graphics.drawCenteredString(this.font,
                    "Note: other settings require an active world; only PlayerMob default saves here.",
                    centerX, topY + ROW_GAP * 6 + 78, 0xFFFFAA55);
        }
    }

    private void saveAndClose() {
        Integer carriages = parseIntOrNull(carriagesField.getValue());
        Double speed = parseDoubleOrNull(speedField.getValue());
        Integer trainY = parseIntOrNull(trainYField.getValue());
        Integer groupSize = parseIntOrNull(groupSizeField.getValue());
        Integer playerMobSpawn = parseIntOrNull(playerMobSpawnField.getValue());

        if (carriages == null || speed == null || trainY == null || groupSize == null || playerMobSpawn == null) {
            LOGGER.warn("[DungeonTrain] Settings screen: invalid input carriages={} speed={} trainY={} groupSize={} playerMobSpawn={}",
                    carriagesField.getValue(), speedField.getValue(), trainYField.getValue(),
                    groupSizeField.getValue(), playerMobSpawnField.getValue());
            return;
        }

        DungeonTrainConfig.setNumCarriages(carriages);
        DungeonTrainConfig.setSpeed(speed);
        DungeonTrainConfig.setTrainY(trainY);
        DungeonTrainConfig.setGenerationMode(modeButton.getValue());
        DungeonTrainConfig.setGroupSize(groupSize);

        // Two-tier spawn rate: in a world → set THIS world's per-world override
        // on the integrated-server thread; on the title screen → set the global
        // default for new worlds. Read live by PlayerMobGroupSpawner.
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        String playerMobScope;
        if (server != null) {
            final int rate = playerMobSpawn;
            server.execute(() -> DungeonTrainWorldData.get(server.overworld()).setPlayerMobSpawnOneInOverride(rate));
            playerMobScope = "world-override";
        } else {
            DungeonTrainCommonConfig.setDefaultPlayerMobSpawnOneIn(playerMobSpawn);
            playerMobScope = "global-default";
        }

        int effectiveCarriages = DungeonTrainConfig.getNumCarriages();
        double effectiveSpeed = DungeonTrainConfig.getSpeed();
        int effectiveTrainY = DungeonTrainConfig.getTrainY();
        CarriageGenerationMode effectiveMode = DungeonTrainConfig.getGenerationMode();
        int effectiveGroupSize = DungeonTrainConfig.getGroupSize();

        applyToLiveTrains(effectiveCarriages, effectiveSpeed);
        LOGGER.info("[DungeonTrain] Settings saved: carriages={} speed={} trainY={} mode={} groupSize={} playerMobSpawnOneIn={} ({})",
                effectiveCarriages, effectiveSpeed, effectiveTrainY, effectiveMode, effectiveGroupSize, playerMobSpawn, playerMobScope);

        onClose();
    }

    /**
     * Context-aware read for the PlayerMob field: in a world → THIS world's
     * effective rate (per-world override or the inherited global default); on
     * the title screen → the global default for new worlds.
     */
    private static int currentPlayerMobSpawnOneIn() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        return server != null
                ? DungeonTrainWorldData.get(server.overworld()).getEffectivePlayerMobSpawnOneIn()
                : DungeonTrainCommonConfig.getDefaultPlayerMobSpawnOneIn();
    }

    private static Component modeLabel(CarriageGenerationMode mode) {
        return switch (mode) {
            case RANDOM -> Component.literal("Random");
            case RANDOM_GROUPED -> Component.literal("Random Grouped");
            case LOOPING -> Component.literal("Looping");
        };
    }

    private static void applyToLiveTrains(int carriages, double speed) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;

        Vector3d velocity = new Vector3d(speed, 0.0, 0.0);
        server.execute(() -> {
            // Per-carriage architecture: each carriage is its own sub-level
            // with its own provider. Velocity propagates to every carriage
            // in every train. Carriage count is now a config knob the
            // appender reads — setting via DungeonTrainConfig already
            // happened on the calling side; nothing to do per-provider for
            // count.
            int updated = 0;
            for (ServerLevel level : server.getAllLevels()) {
                List<TrainTransformProvider> providers = TrainAssembler.getActiveTrainProviders(level);
                for (TrainTransformProvider p : providers) {
                    p.setTargetVelocity(velocity);
                    updated++;
                }
            }
            LOGGER.info("[DungeonTrain] Live update applied to {} carriage(s) (config carriages={})", updated, carriages);
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
