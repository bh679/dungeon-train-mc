package games.brennan.dungeontrain.data;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Was this save already a Free Play run when it was last written? Answered from the save files
 * alone, so it can be asked at the title screen before anything is loaded.
 *
 * <p>Three signals, any one of which is enough, each the on-disk shadow of a live taint source:</p>
 * <ul>
 *   <li>{@code Data.allowCommands} in {@code level.dat} — a world created with "Allow Cheats". The
 *       owner is permission level 4 there, which {@code OperatorIntegrity} turns into Free Play on
 *       the first sweep after login.</li>
 *   <li>the player's {@code dungeontrain:run_cheated} attachment — {@code RunIntegrity}'s sticky
 *       per-run taint, whatever caused it.</li>
 *   <li>the player's {@code playerGameType} being creative or spectator — the mode switch that
 *       taints a run, in case the attachment didn't get written before the crash.</li>
 * </ul>
 *
 * <p>The player checks read <b>both</b> copies of the player's data: {@code playerdata/<uuid>.dat}
 * and the {@code Data.Player} compound embedded in {@code level.dat}. In singleplayer the embedded
 * one is what Minecraft actually loads the owner from; they are written together and normally agree,
 * but either being tainted is enough.</p>
 *
 * <p>Used by the crash-recovery offer: a Free Play run has nothing to salvage that a fresh Free
 * Play world wouldn't give back anyway, so the offer is skipped. Best-effort and read-only: an
 * unreadable file counts as "not Free Play", which errs towards making the offer.</p>
 */
public final class SaveFreePlayProbe {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String LEVEL_DAT = ExperimentalWarningSeal.LEVEL_DAT;
    static final String PLAYERDATA_DIR = "playerdata";
    static final String TAG_DATA = ExperimentalWarningSeal.TAG_DATA;
    static final String TAG_ALLOW_COMMANDS = "allowCommands";
    static final String TAG_PLAYER = "Player";
    static final String TAG_ATTACHMENTS = "neoforge:attachments";
    static final String TAG_RUN_CHEATED = "dungeontrain:run_cheated";
    static final String TAG_GAME_TYPE = "playerGameType";
    /** Vanilla {@code GameType} ids that taint a run: creative and spectator. */
    static final int GAME_TYPE_CREATIVE = 1;
    static final int GAME_TYPE_SPECTATOR = 3;

    private SaveFreePlayProbe() {}

    /** True when the save shows any Free Play signal for {@code player}. */
    public static boolean wasFreePlay(Path levelDir, UUID player) {
        return worldAllowsCommands(levelDir) || playerTainted(levelDir, player);
    }

    static boolean worldAllowsCommands(Path levelDir) {
        CompoundTag root = read(levelDir.resolve(LEVEL_DAT));
        if (root == null || !root.contains(TAG_DATA, CompoundTag.TAG_COMPOUND)) return false;
        return root.getCompound(TAG_DATA).getBoolean(TAG_ALLOW_COMMANDS);
    }

    static boolean playerTainted(Path levelDir, UUID player) {
        CompoundTag fromFile = read(levelDir.resolve(PLAYERDATA_DIR).resolve(player + ".dat"));
        if (fromFile != null && tainted(fromFile)) return true;
        CompoundTag level = read(levelDir.resolve(LEVEL_DAT));
        if (level == null || !level.contains(TAG_DATA, CompoundTag.TAG_COMPOUND)) return false;
        CompoundTag data = level.getCompound(TAG_DATA);
        return data.contains(TAG_PLAYER, CompoundTag.TAG_COMPOUND) && tainted(data.getCompound(TAG_PLAYER));
    }

    /** One copy of a player's NBT: cheated attachment set, or a creative/spectator game mode. */
    static boolean tainted(CompoundTag playerTag) {
        if (playerTag.contains(TAG_ATTACHMENTS, CompoundTag.TAG_COMPOUND)
                && playerTag.getCompound(TAG_ATTACHMENTS).getBoolean(TAG_RUN_CHEATED)) {
            return true;
        }
        int mode = playerTag.getInt(TAG_GAME_TYPE);
        return mode == GAME_TYPE_CREATIVE || mode == GAME_TYPE_SPECTATOR;
    }

    private static CompoundTag read(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Crash recovery: couldn't read {}: {}", file, e.toString());
            return null;
        }
    }
}
