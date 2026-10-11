package com.selectiverendering.mixin.hiding.particle;

import java.util.Map;
import java.util.List;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.Camera;
import com.selectiverendering.render.hiding.ProjectionPass;
import com.selectiverendering.render.hiding.ProjectionAreas;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.selectiverendering.render.hiding.ParticleTypeHolder;
import com.selectiverendering.region.RegionTargets;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 记录目标粒子的类型；Alpha 为 0 时直接丢弃*/
@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {
	@WrapOperation(
		method = "createParticle",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleEngine;add(Lnet/minecraft/client/particle/Particle;)V")
	)
	private void selectiveRendering$recordType(ParticleEngine engine, Particle particle, Operation<Void> original, @Local(argsOnly = true) ParticleOptions options) {
		((ParticleTypeHolder) particle).selectiveRendering$setType(options.getType());
		original.call(engine, particle);
	}

	@Shadow
	@Final
	private Map<ParticleRenderType, ParticleGroup<?>> particles;

	/**
	 * 投影：源范围里的粒子可能不在视野里，被原版的视锥剔除掉。这里对每个投影范围再提取一遍，
	 * 视锥整体平移位移，让位移后落在视野里的源粒子通过；提取时只留源范围里的粒子并加上位移。
	 */
	@Inject(method = "extract", at = @At("TAIL"))
	private void selectiveRendering$project(ParticlesRenderState state, Frustum frustum, Camera camera, float partialTickTime, CallbackInfo ci) {
		List<ProjectionAreas.Active> areas = ProjectionAreas.current();
		if (areas.isEmpty()) {
			return;
		}

		Vec3 position = camera.position();

		for (ProjectionAreas.Active area : areas) {
			Frustum shifted = new Frustum(frustum);
			shifted.prepare(position.x - area.offsetX(), position.y - area.offsetY(), position.z - area.offsetZ());
			ProjectionPass.beginParticle(area);

			try {
				for (ParticleGroup<?> group : particles.values()) {
					if (group instanceof QuadParticleGroup && !group.isEmpty()) {
						state.add(group.extractRenderState(shifted, camera, partialTickTime));
					}
				}
			}
			finally {
				ProjectionPass.endParticle();
			}
		}
	}

	@Inject(method = "add", at = @At("HEAD"), cancellable = true)
	private void selectiveRendering$skipHidden(Particle p, CallbackInfo ci) {
		ParticlePosAccessor pos = (ParticlePosAccessor) p;
		ParticleTypeHolder holder = (ParticleTypeHolder) p;

		if (RegionTargets.particleAlpha(holder.selectiveRendering$type(), pos.selectiveRendering$x(), pos.selectiveRendering$y(), pos.selectiveRendering$z()) == 0) {
			ci.cancel();
		}
	}
}
