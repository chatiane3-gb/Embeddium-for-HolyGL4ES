package me.jellysquid.mods.sodium.mixin.features.render.immediate.buffer_builder;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import me.jellysquid.mods.sodium.client.buffer.ExtendedVertexFormat;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormatElement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Thanks to Maximum for this optimization, taken from Fireblanket.
 */
@Mixin(VertexFormat.class)
public class VertexFormatMixin implements ExtendedVertexFormat {
    @Shadow
    @Final
    private ImmutableList<VertexFormatElement> elements;

    private ExtendedVertexFormat.Element[] embeddium$extendedElements;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void embeddium$createElementArray(ImmutableMap<String, VertexFormatElement> immutableList, CallbackInfo ci) {
        this.embeddium$extendedElements = new ExtendedVertexFormat.Element[this.elements.size()];

        VertexFormatElement currentElement = elements.get(0);
        int id = 0;
        for (VertexFormatElement element : this.elements) {
            if (element.getType() == VertexFormatElement.Type.PADDING) continue;

            int oldId = id;
            int byteLength = 0;

            do {
                if (++id >= this.embeddium$extendedElements.length)
                    id -= this.embeddium$extendedElements.length;
                byteLength += currentElement.getByteLength();
                currentElement = this.elements.get(id);
            } while (currentElement.getType() == VertexFormatElement.Type.PADDING);

            this.embeddium$extendedElements[oldId] = new ExtendedVertexFormat.Element(element, id - oldId, byteLength);
        }
    }

    @Override
    public ExtendedVertexFormat.Element[] embeddium$getExtendedElements() {
        return this.embeddium$extendedElements;
    }
}