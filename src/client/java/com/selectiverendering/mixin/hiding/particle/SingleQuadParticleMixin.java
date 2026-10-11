package com.selectiverendering.mixin.hiding.particle;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.selectiverendering.render.hiding.ParticleTypeHolder;
import com.selectiverendering.region.RegionTargets;
import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SingleQuadParticle.class)
public abstract class SingleQuadParticleMixin {
	@WrapOperation(
		method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/SingleQuadParticle;getLayer()Lnet/minecraft/client/particle/SingleQuadParticle$Layer;")
	)
	private SingleQuadParticle.Layer selectiveRendering$layer(SingleQuadParticle particle, Operation<SingleQuadParticle.Layer> original) {
		SingleQuadParticle.Layer layer = original.call(particle);

		if (selectiveRendering$alpha() < 0 || layer.translucent()) {
			return layer;
		}

		if (layer == SingleQuadParticle.Layer.OPAQUE_TERRAIN) {
			return SingleQuadParticle.Layer.TRANSLUCENT_TERRAIN;
		}

		if (layer == SingleQuadParticle.Layer.OPAQUE_ITEMS) {
			return SingleQuadParticle.Layer.TRANSLUCENT_ITEMS;
		}

		return layer == SingleQuadParticle.Layer.OPAQUE ? SingleQuadParticle.Layer.TRANSLUCENT : layer;
	}

	@WrapOperation(
		method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;colorFromFloat(FFFF)I")
	)
	private int selectiveRendering$color(float alpha, float red, float green, float blue, Operation<Integer> original) {
		int hidden = selectiveRendering$alpha();
		return original.call(hidden < 0 ? alpha : alpha * hidden / 255.0F, red, green, blue);
	}

	@Unique
	private int selectiveRendering$alpha() {
		ParticlePosAccessor pos = (ParticlePosAccessor) (Object) this;
		ParticleTypeHolder holder = (ParticleTypeHolder) (Object) this;

		return RegionTargets.particleAlpha(holder.selectiveRendering$type(), pos.selectiveRendering$x(), pos.selectiveRendering$y(), pos.selectiveRendering$z());
	}
}
