package org.embeddedt.embeddium.impl.render.chunk.compile;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import org.embeddedt.embeddium.impl.gl.util.VertexRange;
import org.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import org.embeddedt.embeddium.impl.render.chunk.compile.buffers.BakedChunkModelBuilder;
import org.embeddedt.embeddium.impl.render.chunk.compile.buffers.ChunkModelBuilder;
import org.embeddedt.embeddium.impl.render.chunk.data.BuiltSectionInfo;
import org.embeddedt.embeddium.impl.render.chunk.data.BuiltSectionMeshParts;
import org.embeddedt.embeddium.impl.render.chunk.terrain.DefaultTerrainRenderPasses;
import org.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import org.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import org.embeddedt.embeddium.impl.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import org.embeddedt.embeddium.impl.util.NativeBuffer;
import org.embeddedt.embeddium.impl.render.chunk.sorting.TranslucentQuadAnalyzer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A collection of temporary buffers for each worker thread which will be used to build chunk meshes for given render
 * passes. This makes a best-effort attempt to pick a suitable size for each scratch buffer, but will never try to
 * shrink a buffer.
 */
public class ChunkBuildBuffers {
    private static final ModelQuadFacing[] ONLY_UNASSIGNED = new ModelQuadFacing[] { ModelQuadFacing.UNASSIGNED };
    private final Reference2ReferenceOpenHashMap<TerrainRenderPass, BakedChunkModelBuilder> builders = new Reference2ReferenceOpenHashMap<>();

    private final ChunkVertexType vertexType;

    public ChunkBuildBuffers(ChunkVertexType vertexType) {
        this.vertexType = vertexType;

        for (TerrainRenderPass pass : DefaultTerrainRenderPasses.ALL) {
            var vertexBuffers = new ChunkMeshBufferBuilder[ModelQuadFacing.COUNT];

            for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
                vertexBuffers[facing] = new ChunkMeshBufferBuilder(this.vertexType, 128 * 1024, pass.isSorted() && facing == ModelQuadFacing.UNASSIGNED.ordinal());
            }

            this.builders.put(pass, new BakedChunkModelBuilder(vertexBuffers, !pass.isSorted()));
        }
    }

    public void init(BuiltSectionInfo.Builder renderData, int sectionIndex) {
        for (var builder : this.builders.values()) {
            builder.begin(renderData, sectionIndex);
        }
    }

    public ChunkModelBuilder get(Material material) {
        return this.builders.get(material.pass);
    }

    public ChunkModelBuilder get(TerrainRenderPass pass) {
        return this.builders.get(pass);
    }

    /**
     * Creates immutable baked chunk meshes from all non-empty scratch buffers. This is used after all blocks
     * have been rendered to pass the finished meshes over to the graphics card. This function can be called multiple
     * times to return multiple copies.
     */
    public BuiltSectionMeshParts createMesh(TerrainRenderPass pass) {
        var builder = this.builders.get(pass);

        List<ByteBuffer> vertexBuffers = new ArrayList<>();
        VertexRange[] vertexRanges = new VertexRange[ModelQuadFacing.COUNT];

        int vertexCount = 0;

        ModelQuadFacing[] facingsToUpload = pass.isSorted() ? ONLY_UNASSIGNED : ModelQuadFacing.VALUES;
        TranslucentQuadAnalyzer.SortState sortState = pass.isSorted() ? builder.getVertexBuffer(ModelQuadFacing.UNASSIGNED).getSortState() : null;

        for (ModelQuadFacing facing : facingsToUpload) {
            var buffer = builder.getVertexBuffer(facing);

            if (buffer.isEmpty()) {
                continue;
            }

            vertexBuffers.add(buffer.slice());
            vertexRanges[facing.ordinal()] = new VertexRange(vertexCount, buffer.count());

            vertexCount += buffer.count();
        }

        if (vertexCount == 0) {
            return null;
        }

        var mergedBuffer = new NativeBuffer(vertexCount * this.vertexType.getVertexFormat().getStride());
        var mergedBufferBuilder = mergedBuffer.getDirectBuffer();

        for (var buffer : vertexBuffers) {
            mergedBufferBuilder.put(buffer);
        }

        mergedBufferBuilder.flip();

        NativeBuffer mergedIndexBuffer;

        if (pass.isSorted()) {
            // Generate the canonical index buffer
            mergedIndexBuffer = new NativeBuffer((vertexCount / 4 * 6) * 4);
            int bufOffset = 0;
            for (ModelQuadFacing facing : facingsToUpload) {
                var buffer = builder.getVertexBuffer(facing);

                if (buffer.isEmpty()) {
                    continue;
                }

                int numPrimitives = buffer.count() / 4;

                ChunkBufferSorter.generateSimpleIndexBuffer(mergedIndexBuffer, numPrimitives, bufOffset);

                bufOffset += numPrimitives * 6;
            }
        } else {
            mergedIndexBuffer = null;
        }

        return new BuiltSectionMeshParts(mergedBuffer, mergedIndexBuffer, sortState, vertexRanges);
    }

    public void destroy() {
        for (var builder : this.builders.values()) {
            builder.destroy();
        }
    }

    public ChunkVertexType getVertexType() {
        return vertexType;
    }
}
