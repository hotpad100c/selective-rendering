package com.selectiverendering.mixin.particle;

import com.selectiverendering.render.RegionTargets;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 粒子在被选区隐藏的位置生成时直接丢弃。 */
@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {
	@Inject(method = "add", at = @At("HEAD"), cancellable = true)
	private void selectiveRendering$skipHidden(Particle particle, CallbackInfo ci) {
		ParticlePosAccessor pos = (ParticlePosAccessor) particle;

		if (RegionTargets.hidesParticle(pos.selectiveRendering$x(), pos.selectiveRendering$y(), pos.selectiveRendering$z())) {
			ci.cancel();
		}
	}
}
