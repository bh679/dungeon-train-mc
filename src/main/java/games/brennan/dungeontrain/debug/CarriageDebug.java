package games.brennan.dungeontrain.debug;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageTemplate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Sliver-bug diagnostics. Scans a padded region around a carriage footprint
 * and reports any non-air block that falls OUTSIDE the canonical
 * {@code [0, LENGTH) × [0, HEIGHT) × [0, WIDTH)} footprint — i.e. anything
 * the rolling-window erase path would miss. Each stray is logged with its
 * offset from the footprint origin so we can see which axis is leaking.
 *
 * Intended to be removed (or demoted to TRACE) once the sliver source is
 * identified.
 */
public final class CarriageDebug {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int DEFAULT_PAD = 2;

    private CarriageDebug() {}

    /**
     * Scan the region expanded by {@code pad} blocks on every face around the
     * footprint anchored at {@code footprintOrigin}. Logs non-air blocks that
     * lie outside the canonical 9×HEIGHT×WIDTH footprint.
     *
     * @return count of strays found
     */
    public static int scanForStrays(ServerLevel level, BlockPos footprintOrigin, String tag, int pad) {
        int ix0 = footprintOrigin.getX();
        int iy0 = footprintOrigin.getY();
        int iz0 = footprintOrigin.getZ();
        int ix1 = ix0 + CarriageTemplate.LENGTH;
        int iy1 = iy0 + CarriageTemplate.HEIGHT;
        int iz1 = iz0 + CarriageTemplate.WIDTH;

        List<String> strays = new ArrayList<>();
        for (int x = ix0 - pad; x < ix1 + pad; x++) {
            for (int y = iy0 - pad; y < iy1 + pad; y++) {
                for (int z = iz0 - pad; z < iz1 + pad; z++) {
                    boolean inside = x >= ix0 && x < ix1
                        && y >= iy0 && y < iy1
                        && z >= iz0 && z < iz1;
                    if (inside) continue;
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;
                    int dx = x - ix0;
                    int dy = y - iy0;
                    int dz = z - iz0;
                    String name = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
                    strays.add(String.format("[%+d,%+d,%+d]=%s", dx, dy, dz, name));
                }
            }
        }
        if (!strays.isEmpty()) {
            LOGGER.info("[DungeonTrain][DEBUG] STRAY {} @ {} (pad={}) count={} offsets={}",
                tag, footprintOrigin, pad, strays.size(), strays);
        }
        return strays.size();
    }

    public static int scanForStrays(ServerLevel level, BlockPos footprintOrigin, String tag) {
        return scanForStrays(level, footprintOrigin, tag, DEFAULT_PAD);
    }
}
