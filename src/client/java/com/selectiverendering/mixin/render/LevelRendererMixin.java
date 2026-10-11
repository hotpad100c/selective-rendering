package com.selectiverendering.mixin.render;

import com.selectiverendering.render.information.InfoRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.gizmos.Gizmos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 绘制已保存选区、当前选区和角点的线框。 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

	//? if >=26.2 {
	@Inject(method = "collectPerFrameRenderThreadGizmos", at = @At("TAIL"))
	private void selectiveRendering$emitRegionGizmo(CallbackInfoReturnable<Gizmos.TemporaryCollection> cir) {
		InfoRenderer.emit();
	}
	//?} else {
	/*@Inject(method = "collectPerFrameGizmos", at = @At("TAIL"))
	private void selectiveRendering$emitRegionGizmo(CallbackInfoReturnable<Gizmos.TemporaryCollection> cir) {
		InfoRenderer.emit();
	}
	*///?}

	@Inject(method = "finalizeGizmoCollection", at = @At("TAIL"), require = 0)
	private void selectiveRendering$markGizmosDrained(CallbackInfo ci) {
		InfoRenderer.gizmosDrained = true;
	}

}
