package org.embeddedt.embeddium.impl.world.cloned;

import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMaps;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import org.embeddedt.embeddium.impl.world.ReadableContainerExtended;
import org.embeddedt.embeddium.impl.world.WorldSlice;
import net.fabricmc.fabric.api.blockview.v2.RenderDataBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.DebugLevelSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelDataManager;
import net.neoforged.neoforge.common.world.AuxiliaryLightManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class ClonedChunkSection {
    private static final DataLayer DEFAULT_SKY_LIGHT_ARRAY = new DataLayer(15);
    private static final DataLayer DEFAULT_BLOCK_LIGHT_ARRAY = new DataLayer(0);
    private static final PalettedContainer<BlockState> DEFAULT_STATE_CONTAINER = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
    private static final boolean HAS_FABRIC_RENDER_DATA;

    private final SectionPos pos;

    private final @Nullable Int2ReferenceMap<BlockEntity> blockEntityMap;
    private final @Nullable Int2ReferenceMap<Object> blockEntityRenderDataMap;
    private final @Nullable Long2ObjectFunction<ModelData> modelDataMap;

    private final @Nullable DataLayer[] lightDataArrays;

    private final @Nullable PalettedContainerRO<BlockState> blockData;

    private final @Nullable PalettedContainerRO<Holder<Biome>> biomeData;

    private final @Nullable AuxiliaryLightManager auxLightManager;

    private long lastUsedTimestamp = Long.MAX_VALUE;

    static {
        boolean hasRenderData;
        try {
            hasRenderData = RenderDataBlockEntity.class.isAssignableFrom(BlockEntity.class);
        } catch(Throwable e) {
            hasRenderData = false;
        }
        HAS_FABRIC_RENDER_DATA = hasRenderData;
    }

    public ClonedChunkSection(Level world, LevelChunk chunk, @Nullable LevelChunkSection section, SectionPos pos) {
        this.pos = pos;

        PalettedContainerRO<BlockState> blockData = null;
        PalettedContainerRO<Holder<Biome>> biomeData = null;

        Int2ReferenceMap<BlockEntity> blockEntityMap = null;
        Int2ReferenceMap<Object> blockEntityRenderDataMap = null;
        Long2ObjectFunction<ModelData> modelDataMap = null;

        if (section != null) {
            if (!section.hasOnlyAir()) {
                if (!world.isDebug()) {
                    blockData = ReadableContainerExtended.clone(section.getStates());
                } else {
                    blockData = constructDebugWorldContainer(pos);
                }
                blockEntityMap = copyBlockEntities(chunk, pos);
                modelDataMap = copyModelData(world, pos);

                if (blockEntityMap != null) {
                    blockEntityRenderDataMap = copyBlockEntityRenderData(blockEntityMap);
                }
            }

            biomeData = ReadableContainerExtended.clone(section.getBiomes());
        }

        this.blockData = blockData;
        this.biomeData = biomeData;

        this.blockEntityMap = blockEntityMap;
        this.modelDataMap = modelDataMap;
        this.blockEntityRenderDataMap = blockEntityRenderDataMap;

        this.lightDataArrays = copyLightData(world, pos);

        this.auxLightManager = chunk.getAuxLightManager(chunk.getPos());
    }

    /**
     * Construct a fake PalettedContainer whose contents match those of the debug world. This is needed to
     * match vanilla's odd approach of short-circuiting getBlockState calls inside its render region class.
     */
    @NotNull
    private static PalettedContainer<BlockState> constructDebugWorldContainer(SectionPos pos) {
        // Fast path for sections which are guaranteed to be empty
        if (pos.getY() != 3 && pos.getY() != 4)
            return DEFAULT_STATE_CONTAINER;

        // We use swapUnsafe in the loops to avoid acquiring/releasing the lock on each iteration
        var container = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
        if (pos.getY() == 3) {
            // Set the blocks at relative Y 12 (world Y 60) to barriers
            BlockState barrier = Blocks.BARRIER.defaultBlockState();
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    container.getAndSetUnchecked(x, 12, z, barrier);
                }
            }
        } else if (pos.getY() == 4) {
            // Set the blocks at relative Y 6 (world Y 70) to the appropriate state from the generator
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    container.getAndSetUnchecked(x, 6, z, DebugLevelSource.getBlockStateFor(SectionPos.sectionToBlockCoord(pos.getX(), x), SectionPos.sectionToBlockCoord(pos.getZ(), z)));
                }
            }
        }
        return container;
    }

    @NotNull
    private static DataLayer[] copyLightData(Level world, SectionPos pos) {
        var arrays = new DataLayer[2];
        arrays[LightLayer.BLOCK.ordinal()] = copyLightArray(world, LightLayer.BLOCK, pos);

        // Dimensions without sky-light should not have a default-initialized array
        if (world.dimensionType().hasSkyLight()) {
            arrays[LightLayer.SKY.ordinal()] = copyLightArray(world, LightLayer.SKY, pos);
        }

        return arrays;
    }

    /**
     * Copies the light data array for the given light type for this chunk, or returns a default-initialized value if
     * the light array is not loaded.
     */
    @NotNull
    private static DataLayer copyLightArray(Level world, LightLayer type, SectionPos pos) {
        var array = world.getLightEngine()
                .getLayerListener(type)
                .getDataLayerData(pos);

        if (array == null) {
            array = switch (type) {
                case SKY -> DEFAULT_SKY_LIGHT_ARRAY;
                case BLOCK -> DEFAULT_BLOCK_LIGHT_ARRAY;
            };
        }

        return array;
    }

    @Nullable
    private static Long2ObjectFunction<ModelData> copyModelData(Level level, SectionPos chunkCoord) {
        var forgeMap = level.getModelDataManager().snapshotSectionRegion(chunkCoord.x(), chunkCoord.y(), chunkCoord.z(), chunkCoord.x(), chunkCoord.y(), chunkCoord.z());

        return forgeMap != ModelDataManager.EMPTY_SNAPSHOT ? forgeMap : null;
    }

    private static Iterable<Map.Entry<BlockPos, BlockEntity>> fastIterable(Map<BlockPos, BlockEntity> blockEntityMap) {
        if (blockEntityMap instanceof Object2ObjectMap<BlockPos, BlockEntity> fastutilMap) {
            //noinspection unchecked
            return (Iterable<Map.Entry<BlockPos, BlockEntity>>)(Iterable<?>)Object2ObjectMaps.fastIterable(fastutilMap);
        } else {
            return blockEntityMap.entrySet();
        }
    }

    @Nullable
    private static Int2ReferenceMap<BlockEntity> copyBlockEntities(LevelChunk chunk, SectionPos chunkCoord) {
        BoundingBox box = new BoundingBox(chunkCoord.minBlockX(), chunkCoord.minBlockY(), chunkCoord.minBlockZ(),
                chunkCoord.maxBlockX(), chunkCoord.maxBlockY(), chunkCoord.maxBlockZ());

        Int2ReferenceOpenHashMap<BlockEntity> blockEntities = null;

        // Copy the block entities from the chunk into our cloned section
        for (Map.Entry<BlockPos, BlockEntity> entry : fastIterable(chunk.getBlockEntities())) {
            BlockPos pos = entry.getKey();
            BlockEntity entity = entry.getValue();

            if (box.isInside(pos)) {
                if (blockEntities == null) {
                    blockEntities = new Int2ReferenceOpenHashMap<>();
                }

                blockEntities.put(WorldSlice.getLocalBlockIndex(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15), entity);
            }
        }

        if (blockEntities != null) {
            blockEntities.trim();
        }

        return blockEntities;
    }

    @Nullable
    private static Int2ReferenceMap<Object> copyBlockEntityRenderData(Int2ReferenceMap<BlockEntity> blockEntities) {
        // Immediately exit if block entities do not have Fabric render data.
        if(!HAS_FABRIC_RENDER_DATA) {
            return null;
        }

        Int2ReferenceOpenHashMap<Object> blockEntityRenderDataMap = null;

        // Retrieve any render data after we have copied all block entities, as this will call into the code of
        // other mods. This could potentially result in the chunk being modified, which would cause problems if we
        // were iterating over any data in that chunk.
        // See https://github.com/CaffeineMC/sodium-fabric/issues/942 for more info.
        for (var entry : Int2ReferenceMaps.fastIterable(blockEntities)) {
            Object data = ((RenderDataBlockEntity)entry.getValue()).getRenderData();

            if (data != null) {
                if (blockEntityRenderDataMap == null) {
                    blockEntityRenderDataMap = new Int2ReferenceOpenHashMap<>();
                }

                blockEntityRenderDataMap.put(entry.getIntKey(), data);
            }
        }

        if (blockEntityRenderDataMap != null) {
            blockEntityRenderDataMap.trim();
        }

        return blockEntityRenderDataMap;
    }

    public SectionPos getPosition() {
        return this.pos;
    }

    public @Nullable PalettedContainerRO<BlockState> getBlockData() {
        return this.blockData;
    }

    public @Nullable PalettedContainerRO<Holder<Biome>> getBiomeData() {
        return this.biomeData;
    }

    public @Nullable Int2ReferenceMap<BlockEntity> getBlockEntityMap() {
        return this.blockEntityMap;
    }

    public @Nullable Int2ReferenceMap<Object> getBlockEntityRenderDataMap() {
        return this.blockEntityRenderDataMap;
    }

    public @Nullable Long2ObjectFunction<ModelData> getModelDataMap() {
        return this.modelDataMap;
    }

    public AuxiliaryLightManager getAuxLightManager() {
        return this.auxLightManager;
    }

    public @Nullable DataLayer getLightArray(LightLayer lightType) {
        return this.lightDataArrays[lightType.ordinal()];
    }

    public long getLastUsedTimestamp() {
        return this.lastUsedTimestamp;
    }

    public void setLastUsedTimestamp(long timestamp) {
        this.lastUsedTimestamp = timestamp;
    }
}
