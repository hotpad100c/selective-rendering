package com.selectiverendering.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.selectiverendering.blockentity.SelectiveSubmitNodeCollector;
import com.selectiverendering.render.RegionTargets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;

/** 按所在位置隐藏实体，或将其提交到半透明管线。 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
	@WrapMethod(method = "submit")
	private void selectiveRendering$submit(
		EntityRenderState state,
		CameraRenderState cameraRenderState,
		double x,
		double y,
		double z,
		PoseStack poseStack,
		SubmitNodeCollector collector,
		Operation<Void> original
	) {
		int alpha = RegionTargets.entityAlpha(state.x, state.y, state.z);
		if (alpha == 0) {
			return;
		}

		original.call(state, cameraRenderState, x, y, z, poseStack, alpha > 0 ? new SelectiveSubmitNodeCollector(collector) : collector);
	}
}
