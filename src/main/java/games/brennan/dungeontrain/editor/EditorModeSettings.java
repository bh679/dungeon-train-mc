package games.brennan.dungeontrain.editor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Every {@link EditorModeSetting}'s value in both {@link EditorSettingMode}s. Immutable: a change
 * makes a new one ({@link #with}), so the copy a gate is reading never shifts under it.
 *
 * <p>Two bit sets, one per mode, indexed by the setting's ordinal. They are also the wire format of
 * {@code EditorModeSettingsPacket}.</p>
 */
public record EditorModeSettings(int editorBits, int testBits) {

    /** Every setting at its mode's default — a world nobody has changed, and no world at all. */
    public static final EditorModeSettings DEFAULTS = defaults();

    private static EditorModeSettings defaults() {
        int editor = 0;
        int test = 0;
        for (EditorModeSetting s : EditorModeSetting.values()) {
            if (s.defaultValue(EditorSettingMode.EDITOR)) editor |= bit(s);
            if (s.defaultValue(EditorSettingMode.TEST)) test |= bit(s);
        }
        return new EditorModeSettings(editor, test);
    }

    private static int bit(EditorModeSetting setting) {
        return 1 << setting.ordinal();
    }

    /** {@code setting}'s value in {@code mode}. */
    public boolean get(EditorSettingMode mode, EditorModeSetting setting) {
        int bits = mode == EditorSettingMode.TEST ? testBits : editorBits;
        return (bits & bit(setting)) != 0;
    }

    /** True when {@code setting} is on in both modes — the gates' fast path. */
    public boolean onInBoth(EditorModeSetting setting) {
        return get(EditorSettingMode.EDITOR, setting) && get(EditorSettingMode.TEST, setting);
    }

    /** A copy with {@code setting} set to {@code value} in {@code mode} only. */
    public EditorModeSettings with(EditorSettingMode mode, EditorModeSetting setting, boolean value) {
        if (mode == EditorSettingMode.TEST) {
            return new EditorModeSettings(editorBits, value ? testBits | bit(setting) : testBits & ~bit(setting));
        }
        return new EditorModeSettings(value ? editorBits | bit(setting) : editorBits & ~bit(setting), testBits);
    }

    /** Read from world data: every tag present overrides its default. */
    public static EditorModeSettings load(CompoundTag tag) {
        EditorModeSettings out = DEFAULTS;
        for (EditorModeSetting s : EditorModeSetting.values()) {
            for (EditorSettingMode mode : EditorSettingMode.values()) {
                String key = s.tag(mode);
                if (tag.contains(key, Tag.TAG_BYTE)) out = out.with(mode, s, tag.getBoolean(key));
            }
        }
        return out;
    }

    /** Write into world data: only the values that differ from their default. */
    public void save(CompoundTag tag) {
        for (EditorModeSetting s : EditorModeSetting.values()) {
            for (EditorSettingMode mode : EditorSettingMode.values()) {
                boolean value = get(mode, s);
                if (value != s.defaultValue(mode)) tag.putBoolean(s.tag(mode), value);
            }
        }
    }
}
