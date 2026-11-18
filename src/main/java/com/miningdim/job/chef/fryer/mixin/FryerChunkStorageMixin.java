package com.miningdim.job.chef.fryer.mixin;

import com.miningdim.job.chef.fryer.FryerChunkPersistence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkStorage.class)
public abstract class FryerChunkStorageMixin {
    @Redirect(method = "write", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/storage/IOWorker;store(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> wok$trackFryerWrite(IOWorker worker, ChunkPos pos, CompoundTag chunk) {
        CompletableFuture<Void> written = worker.store(pos, chunk);
        if ((Object) this instanceof ChunkMap map) {
            FryerChunkPersistence.track(((FryerChunkMapAccess) map).wok$fryerLevel(), worker, pos, chunk, written);
        }
        return written;
    }
}
