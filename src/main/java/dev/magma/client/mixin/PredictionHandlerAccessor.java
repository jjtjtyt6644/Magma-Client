package dev.magma.client.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Blocks we broke client-side that the server hasn't confirmed yet (they can pop back on lag). */
@Mixin(BlockStatePredictionHandler.class)
public interface PredictionHandlerAccessor {
    @Accessor("serverVerifiedStates")
    Long2ObjectOpenHashMap<?> magma$pending();
}
