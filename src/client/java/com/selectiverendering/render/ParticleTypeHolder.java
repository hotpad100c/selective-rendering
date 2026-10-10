package com.selectiverendering.render;

import net.minecraft.core.particles.ParticleType;

public interface ParticleTypeHolder {
	void selectiveRendering$setType(ParticleType<?> type);

	ParticleType<?> selectiveRendering$type();
}
