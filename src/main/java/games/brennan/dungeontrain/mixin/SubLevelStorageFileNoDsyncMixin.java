package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.storage.region.SubLevelStorageFile;
import games.brennan.dungeontrain.ship.sable.SableStorageSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;

/**
 * Stops Sable's sub-level save from waiting on the disk for every single write (#1508).
 *
 * <p>Sable saves trains on the <b>server thread</b> ({@code SubLevelHoldingChunkMap.saveAll},
 * called from {@code ServerLevel.save}). {@code SubLevelStorageFile} opens its channel with
 * {@code CREATE, READ, WRITE, DSYNC}, so each header, sector and padding write blocks until the
 * disk confirms it, and {@code flush()} then {@code force()}s every open file whether it changed
 * or not. With Distant Horizons writing its LOD database to the same disk, a player's autosave
 * froze the game for ~500 s, and 5–15 s freezes followed nearly every autosave and pause.</p>
 *
 * <p>This mixin (a) drops {@code DSYNC} from the constructor's open options, so writes land in
 * the OS cache, and (b) skips the {@code force()} in {@code flush()} for files with no writes
 * since the last flush. Every changed file is still forced at flush, so a completed save is on
 * disk exactly as before; a JVM crash can't lose OS-cached writes, only a power loss between
 * saves could (same tradeoff as vanilla's {@code sync-chunk-writes=false}). {@code close()}'s
 * forces are left alone. {@code SubLevelRegionFile} (holding chunks) extends this class, so both
 * file kinds are covered. Files on disk are byte-identical to before.</p>
 *
 * <p>Bytecode-verified against {@code sable-2.0.5+mc1.21.1}: the 3-arg constructor has the only
 * {@code FileChannel.open} with {@code DSYNC} ({@code writeToExternalFile} opens its own channel
 * without it); {@code writeHeader}, {@code write(int, ByteBuffer)} and
 * {@code padOrTruncateToFullSector} are the only methods that write to or truncate the channel
 * ({@code clear} goes through {@code writeHeader}); {@code flush} is a single
 * {@code file.force(true)}. <b>Re-verify on any {@code sable_version} bump.</b> Left
 * {@code required} so a Sable change fails loudly instead of silently restoring the stall.</p>
 *
 * <p>The clean flag is cleared before the sync and restored on failure, so a write racing the
 * flush re-marks the file dirty rather than being lost from the next flush.</p>
 */
@Mixin(value = SubLevelStorageFile.class, remap = false)
public abstract class SubLevelStorageFileNoDsyncMixin {

    /** No writes since the last successful flush. Starts false, so a fresh file always syncs. */
    @Unique
    private volatile boolean dungeontrain$clean;

    @ModifyArg(
        method = "<init>(Ljava/nio/file/Path;Ljava/nio/file/Path;I)V",
        at = @At(
            value = "INVOKE",
            target = "Ljava/nio/channels/FileChannel;open(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/nio/channels/FileChannel;"
        ),
        index = 1
    )
    private OpenOption[] dungeontrain$dropDsync(OpenOption[] options) {
        return SableStorageSync.withoutDsync(options);
    }

    @Inject(method = "writeHeader", at = @At("HEAD"))
    private void dungeontrain$dirtyOnHeader(CallbackInfo ci) {
        dungeontrain$clean = false;
    }

    @Inject(method = "write(ILjava/nio/ByteBuffer;)V", at = @At("HEAD"))
    private void dungeontrain$dirtyOnWrite(CallbackInfo ci) {
        dungeontrain$clean = false;
    }

    @Inject(method = "padOrTruncateToFullSector", at = @At("HEAD"))
    private void dungeontrain$dirtyOnPad(CallbackInfo ci) {
        dungeontrain$clean = false;
    }

    @WrapOperation(
        method = "flush",
        at = @At(value = "INVOKE", target = "Ljava/nio/channels/FileChannel;force(Z)V")
    )
    private void dungeontrain$forceOnlyIfDirty(FileChannel channel, boolean metaData, Operation<Void> original) {
        if (dungeontrain$clean) {
            SableStorageSync.recordSkipped();
            return;
        }
        dungeontrain$clean = true;
        boolean synced = false;
        try {
            // force() throws IOException through Operation.call undeclared, hence finally.
            original.call(channel, metaData);
            synced = true;
        } finally {
            if (!synced) dungeontrain$clean = false;
        }
        SableStorageSync.recordForced();
    }
}
