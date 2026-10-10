package com.selectiverendering.mixin.compat.entityCulling;

import com.selectiverendering.render.Alpha;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 让 EntityCulling 将淡化方块视为不遮挡视线。 */
@Mixin(targets = "dev.tr7zw.entityculling.Provider", remap = false)
public class EntityCullingProviderMixin {
	@Inject(
		method = "isOpaqueFullCube(III)Z",
		at = @At("HEAD"),
		cancellable = true,
		remap = false,
		require = 0
	)
	private void selectiveRendering$fadedBlocksAreTransparent(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
		if (Alpha.isFadedCube(x, y, z)) {
			cir.setReturnValue(false);
		}
	}
}
