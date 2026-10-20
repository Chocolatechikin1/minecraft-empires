package com.devc.minecraftempires.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Shared durable journal writes; callers serialize operations on their existing I/O workers. */
final class AIJournal {
    private AIJournal() {}

    static void write(Path file, CompoundTag tag) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        NbtIo.writeCompressed(tag, temporary);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
        for (int attempt = 0; ; attempt++) {
            try {
                // Preserve the previous durable record if atomic replacement is unsupported or keeps failing.
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException denied) {
                // Windows readers/scanners can briefly deny replacement. Retry only on the I/O worker,
                // without deleting the old journal or weakening its atomic commit boundary.
                if (attempt == 4) throw denied;
                try { Thread.sleep(25L << attempt); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException failure = new InterruptedIOException("Journal replacement interrupted");
                    failure.initCause(denied);
                    throw failure;
                }
            }
        }
    }
}
