package com.selectiverendering.mixin.render;

import com.selectiverendering.render.Alpha;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 需要半透明流体时，将所有流体切换到 TRANSLUCENT 层。 */
@Mixin(FluidModel.class)
public class FluidModelMixin {
	@Inject(method = "layer", at = @At("RETURN"), cancellable = true)
	private void selectiveRendering$fluidLayer(CallbackInfoReturnable<ChunkSectionLayer> cir) {
		if (Alpha.shouldRenderFluidsTranslucent()) {
			cir.setReturnValue(ChunkSectionLayer.TRANSLUCENT);
		}
	}
}
