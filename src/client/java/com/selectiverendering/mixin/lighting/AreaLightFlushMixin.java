package com.selectiverendering.mixin.lighting;

import com.selectiverendering.lighting.LightSuppression;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 光照引擎跑完一轮之后，消化 {@link LightSuppression} 的待重算区块。
 *
 * <p>必须挂在 {@code runLightUpdates} 的 RETURN：服务端发来的光照是先排队、到这一轮才真正落地的，
 * 落地之后再 {@code checkBlock} 才不会被覆盖。详见 {@link LightSuppression} 类注释。
 *
 * <p>{@code LevelLightEngine} 服务端也有同一个方法，所以 {@code flush} 内部会核对引擎归属。
 */
@Mixin(LevelLightEngine.class)
public class AreaLightFlushMixin {
	@Inject(method = "runLightUpdates", at = @At("RETURN"))
	private void selectiveRendering$flushRelight(CallbackInfoReturnable<Integer> cir) {
		LightSuppression.flush((LevelLightEngine) (Object) this);
	}
}
