package org.embeddedt.embeddium.impl.render.chunk.map;

import net.minecraft.client.multiplayer.ClientLevel;

public interface ChunkTrackerHolder {
    static ChunkTracker get(ClientLevel world) {
        return ((ChunkTrackerHolder) world).sodium$getTracker();
    }

    ChunkTracker sodium$getTracker();
}