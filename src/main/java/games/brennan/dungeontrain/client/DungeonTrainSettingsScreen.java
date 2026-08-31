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
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;

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
    private EditBox playerMobBehindSpawnField;
    private EditBox groupSizeField;
    private CycleButton<Boolean> compatibleTerrainButton;

    public DungeonTrainSettingsScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int topY = this.height / 2 - 80;

        carriagesField = new EditBox(this.font, centerX + 10, topY, FIELD_WIDTH, FIELD_HEIGHT,
                Component.translatable("gui.dungeontrain.settings.narrate.carriages"));
        carriagesField.setValue(Integer.toString(DungeonTrainConfig.getNumCarriages()));
        carriagesField.setFilter(DungeonTrainSettingsScreen::isIntegerInput);
        addRenderableWidget(carriagesField);

        speedField = new EditBox(this.font, centerX + 10, topY + ROW_GAP, FIELD_WIDTH, FIELD_HEIGHT,
                Component.translatable("gui.dungeontrain.settings.narrate.speed"));
        speedField.setValue(Double.toString(DungeonTrainConfig.getSpeed()));
        speedField.setFilter(DungeonTrainSettingsScreen::isDecimalInput);
        addRenderableWidget(speedField);

        trainYField = new EditBox(this.font, centerX + 10, topY + ROW_GAP * 2, FIELD_WIDTH, FIELD_HEIGHT,
                Component.translatable("gui.dungeontrain.settings.narrate.train_y"));
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
                        Component.translatable("gui.dungeontrain.settings.narrate.generation_mode"),
                        (btn, value) -> refreshGroupSizeVisibility()
                );
        addRenderableWidget(modeButton);

        playerMobSpawnField = new EditBox(this.font, centerX + 10, topY + ROW_GAP * 4, FIELD_WIDTH, FIELD_HEIGHT,
                Component.translatable("gui.dungeontrain.settings.narrate.playermob_spawn"));
        // Two-tier: in a world the field shows THIS world's effective rate;
        // on the title screen it shows the global default for new worlds.
        playerMobSpawnField.setValue(Integer.toString(currentPlayerMobSpawnOneIn()));
        playerMobSpawnField.setFilter(DungeonTrainSettingsScreen::isIntegerInput);
        addRenderableWidget(playerMobSpawnField);

        playerMobBehindSpawnField = new EditBox(this.font, centerX + 10, topY + ROW_GAP * 5, FIELD_WIDTH, FIELD_HEIGHT,
                Component.translatable("gui.dungeontrain.settings.narrate.behind_percent"));
        // Two-tier like the forward field: in a world this is THIS world's effective behind-spawn
        // percent; on the title screen it's the global default for new worlds.
        playerMobBehindSpawnField.setValue(Integer.toString(currentPlayerMobBehindSpawnPercent()));
        playerMobBehindSpawnField.setFilter(DungeonTrainSettingsScreen::isIntegerInput);
        addRenderableWidget(playerMobBehindSpawnField);

        groupSizeField = new EditBox(this.font, centerX + 10, topY + ROW_GAP * 6, FIELD_WIDTH, FIELD_HEIGHT,
                Component.translatable("gui.dungeontrain.settings.narrate.group_size"));
        groupSizeField.setValue(Integer.toString(DungeonTrainConfig.getGroupSize()));
        groupSizeField.setFilter(DungeonTrainSettingsScreen::isIntegerInput);
        addRenderableWidget(groupSizeField);
        refreshGroupSizeVisibility();

        // Compatible Terrain: global default for NEW worlds. ON makes new DT worlds
        // generate from vanilla overworld noise so terrain mods (Tectonic) + Distant
        // Horizons take effect. Does not change the current world (terrain is baked at creation).
        compatibleTerrainButton = CycleButton.onOffBuilder(DungeonTrainCommonConfig.getDefaultCompatibleTerrain())
                .displayOnlyValue()
                .create(centerX + 10, topY + ROW_GAP * 7, FIELD_WIDTH, FIELD_HEIGHT,
                        Component.translatable("gui.dungeontrain.settings.narrate.compatible_terrain"));
        addRenderableWidget(compatibleTerrainButton);

        addRenderableWidget(Button.builder(Component.translatable("gui.dungeontrain.settings.save"), b -> saveAndClose())
                .bounds(centerX - 105, topY + ROW_GAP * 8 + 20, 100, 20)
                .build());

        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(centerX + 5, topY + ROW_GAP * 8 + 20, 100, 20)
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

        graphics.drawString(this.font, Component.translatable("gui.dungeontrain.settings.label.carriages"), centerX - LABEL_OFFSET, topY + 6, 0xFFFFFFFF);
        graphics.drawString(this.font, Component.translatable("gui.dungeontrain.settings.label.speed"), centerX - LABEL_OFFSET, topY + ROW_GAP + 6, 0xFFFFFFFF);
        graphics.drawString(this.font, Component.translatable("gui.dungeontrain.settings.label.train_y"), centerX - LABEL_OFFSET, topY + ROW_GAP * 2 + 6, 0xFFFFFFFF);
        graphics.drawString(this.font, Component.translatable("gui.dungeontrain.settings.label.mode"), centerX - LABEL_OFFSET, topY + ROW_GAP * 3 + 6, 0xFFFFFFFF);
        graphics.drawString(this.font, Component.translatable("gui.dungeontrain.settings.label.playermob_spawn"), centerX - LABEL_OFFSET, topY + ROW_GAP * 4 + 6, 0xFFFFFFFF);
        graphics.drawString(this.font, Component.translatable("gui.dungeontrain.settings.label.behind_percent"), centerX - LABEL_OFFSET, topY + ROW_GAP * 5 + 6, 0xFFFFFFFF);
        if (modeButton != null && modeButton.getValue() == CarriageGenerationMode.RANDOM_GROUPED) {
            graphics.drawString(this.font, Component.translatable("gui.dungeontrain.settings.label.group_size"), centerX - LABEL_OFFSET, topY + ROW_GAP * 6 + 6, 0xFFFFFFFF);
        }
        graphics.drawString(this.font, Component.translatable("gui.dungeontrain.settings.label.compatible_terrain"), centerX - LABEL_OFFSET, topY + ROW_GAP * 7 + 6, 0xFFFFFFFF);

        Component rangeHint = Component.translatable("gui.dungeontrain.settings.range_hint",
                DungeonTrainConfig.MIN_CARRIAGES, DungeonTrainConfig.MAX_CARRIAGES,
                DungeonTrainConfig.MIN_SPEED, DungeonTrainConfig.MAX_SPEED,
                DungeonTrainConfig.MIN_TRAIN_Y, DungeonTrainConfig.MAX_TRAIN_Y,
                CarriageGenerationConfig.MIN_GROUP_SIZE, CarriageGenerationConfig.MAX_GROUP_SIZE,
                DungeonTrainCommonConfig.MIN_PLAYER_MOB_SPAWN_ONE_IN, DungeonTrainCommonConfig.MAX_PLAYER_MOB_SPAWN_ONE_IN);
        graphics.drawCenteredString(this.font, rangeHint, centerX, topY + ROW_GAP * 8 - 4, 0xFFAAAAAA);

        graphics.drawCenteredString(this.font,
                Component.translatable("gui.dungeontrain.settings.hint_general"),
                centerX, topY + ROW_GAP * 8 + 50, 0xFFAAAAAA);

        boolean inWorld = Minecraft.getInstance().getSingleplayerServer() != null;
        Component playerMobScope = Component.translatable(inWorld
                ? "gui.dungeontrain.settings.hint_playermob_world"
                : "gui.dungeontrain.settings.hint_playermob_title");
        graphics.drawCenteredString(this.font, playerMobScope, centerX, topY + ROW_GAP * 8 + 64, 0xFFAAAAAA);

        graphics.drawCenteredString(this.font,
                Component.translatable("gui.dungeontrain.settings.hint_compat_terrain"),
                centerX, topY + ROW_GAP * 8 + 78, 0xFFAAAAAA);

        if (!inWorld) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.dungeontrain.settings.hint_note"),
                    centerX, topY + ROW_GAP * 8 + 92, 0xFFFFAA55);
        }
    }

    private void saveAndClose() {
        Integer carriages = parseIntOrNull(carriagesField.getValue());
        Double speed = parseDoubleOrNull(speedField.getValue());
        Integer trainY = parseIntOrNull(trainYField.getValue());
        Integer groupSize = parseIntOrNull(groupSizeField.getValue());
        Integer playerMobSpawn = parseIntOrNull(playerMobSpawnField.getValue());
        Integer playerMobBehindSpawn = parseIntOrNull(playerMobBehindSpawnField.getValue());

        if (carriages == null || speed == null || trainY == null || groupSize == null
                || playerMobSpawn == null || playerMobBehindSpawn == null) {
            LOGGER.warn("[DungeonTrain] Settings screen: invalid input carriages={} speed={} trainY={} groupSize={} playerMobSpawn={} behind={}",
                    carriagesField.getValue(), speedField.getValue(), trainYField.getValue(),
                    groupSizeField.getValue(), playerMobSpawnField.getValue(), playerMobBehindSpawnField.getValue());
            return;
        }

        DungeonTrainConfig.setNumCarriages(carriages);
        DungeonTrainConfig.setSpeed(speed);
        DungeonTrainConfig.setTrainY(trainY);
        DungeonTrainConfig.setGenerationMode(modeButton.getValue());
        DungeonTrainConfig.setGroupSize(groupSize);

        // Global default for new worlds (no per-world override in v1). Setting this in a
        // world changes the default for future worlds, not the current one (terrain is baked).
        DungeonTrainCommonConfig.setDefaultCompatibleTerrain(compatibleTerrainButton.getValue());

        // Two-tier spawn rate: in a world → set THIS world's per-world override
        // on the integrated-server thread; on the title screen → set the global
        // default for new worlds. Read live by PlayerMobGroupSpawner.
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        String playerMobScope;
        if (server != null) {
            final int rate = playerMobSpawn;
            final int behindRate = playerMobBehindSpawn;
            server.execute(() -> {
                DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
                data.setPlayerMobSpawnOneInOverride(rate);
                data.setPlayerMobBehindSpawnPercentOverride(behindRate);
            });
            playerMobScope = "world-override";
        } else {
            DungeonTrainCommonConfig.setDefaultPlayerMobSpawnOneIn(playerMobSpawn);
            DungeonTrainCommonConfig.setDefaultPlayerMobBehindSpawnPercent(playerMobBehindSpawn);
            playerMobScope = "global-default";
        }

        int effectiveCarriages = DungeonTrainConfig.getNumCarriages();
        double effectiveSpeed = DungeonTrainConfig.getSpeed();
        int effectiveTrainY = DungeonTrainConfig.getTrainY();
        CarriageGenerationMode effectiveMode = DungeonTrainConfig.getGenerationMode();
        int effectiveGroupSize = DungeonTrainConfig.getGroupSize();

        applyToLiveTrains(effectiveCarriages, effectiveSpeed);
        LOGGER.info("[DungeonTrain] Settings saved: carriages={} speed={} trainY={} mode={} groupSize={} playerMobSpawnOneIn={} behindOneIn={} ({})",
                effectiveCarriages, effectiveSpeed, effectiveTrainY, effectiveMode, effectiveGroupSize, playerMobSpawn, playerMobBehindSpawn, playerMobScope);

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

    /** Behind-spawn percent counterpart of {@link #currentPlayerMobSpawnOneIn()}. */
    private static int currentPlayerMobBehindSpawnPercent() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        return server != null
                ? DungeonTrainWorldData.get(server.overworld()).getEffectivePlayerMobBehindSpawnPercent()
                : DungeonTrainCommonConfig.getDefaultPlayerMobBehindSpawnPercent();
    }

    private static Component modeLabel(CarriageGenerationMode mode) {
        return Component.translatable("gui.dungeontrain.options.mode." + mode.name().toLowerCase(Locale.ROOT));
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
