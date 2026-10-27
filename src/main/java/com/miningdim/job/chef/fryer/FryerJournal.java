package com.miningdim.job.chef.fryer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** 原版 SavedData.save 会吞掉写盘异常；交易检查点必须确认原子替换成功才可继续。 */
final class FryerJournal {
    private FryerJournal() {}

    static void write(File file, CompoundTag data) {
        Path target = file.toPath();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        CompoundTag wrapper = new CompoundTag(); wrapper.put("data", data);
        NbtUtils.addCurrentDataVersion(wrapper);
        try {
            Files.createDirectories(target.getParent());
            NbtIo.writeCompressed(wrapper, temporary.toFile());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot persist fryer transaction journal " + target, failure);
        }
    }
}
