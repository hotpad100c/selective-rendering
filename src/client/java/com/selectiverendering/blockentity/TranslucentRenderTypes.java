package com.selectiverendering.blockentity;

import com.selectiverendering.mixin.blockentity.RenderSetupAccessor;
import com.selectiverendering.mixin.blockentity.RenderTypeAccessor;
import com.selectiverendering.mixin.blockentity.TextureBindingAccessor;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.util.Map;

/** 构造 RenderType 的半透明变体。 */
public final class TranslucentRenderTypes {
	private TranslucentRenderTypes() {
	}

	public static RenderType translucentVariant(RenderType renderType) {
		if (renderType.hasBlending()) {
			HiddenRenderTypes.mark(renderType);
			return renderType;
		}

		Identifier texture = textureOf(renderType);
		if (texture == null) {
			return renderType;
		}

		RenderType variant = RenderTypes.entityTranslucent(texture);
		if (variant.format() != renderType.format()) {
			return renderType;
		}

		HiddenRenderTypes.mark(variant);
		return variant;
	}

	private static Identifier textureOf(RenderType renderType) {
		RenderSetupAccessor setup = (RenderSetupAccessor) (Object) ((RenderTypeAccessor) (Object) renderType).selectiveRendering$state();
		if (setup == null) {
			return null;
		}

		Map<?, ?> textures = setup.selectiveRendering$textures();
		if (textures == null) {
			return null;
		}

		for (Object binding : textures.values()) {
			Identifier location = ((TextureBindingAccessor) binding).selectiveRendering$location();
			if (location != null) {
				return location;
			}
		}

		return null;
	}
}
