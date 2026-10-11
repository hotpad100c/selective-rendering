package com.selectiverendering.blockentity;

import com.selectiverendering.mixin.hiding.blockentity.RenderSetupAccessor;
import com.selectiverendering.mixin.hiding.blockentity.RenderTypeAccessor;
import com.selectiverendering.mixin.hiding.blockentity.TextureBindingAccessor;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.util.Map;

/** 构造 RenderType 的半透明变体。 */
public final class TranslucentRenderTypes {
	private TranslucentRenderTypes() {
	}

	/** 返回可以带 alpha 绘制的变体，透明度由调用方通过顶点颜色提供。 */
	public static RenderType variant(RenderType renderType) {
		if (renderType.hasBlending()) {
			return renderType;
		}

		Identifier texture = textureOf(renderType);
		if (texture == null) {
			return renderType;
		}

		RenderType variant = RenderTypes.entityTranslucent(texture);
		return variant.format() == renderType.format() ? variant : renderType;
	}

	/** 返回半透明变体并标，本帧所有使用该变体的绘制都会套上隐藏透明度。 */
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
