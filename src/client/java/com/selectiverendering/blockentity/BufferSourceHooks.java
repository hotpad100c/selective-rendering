package com.selectiverendering.blockentity;

import com.selectiverendering.render.Alpha;
import com.selectiverendering.render.MovingBlockRenderContext;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * 将间谍VertexCOnsumer写入移动中的活塞方块实体渲染器。
 */
public final class BufferSourceHooks {
	private BufferSourceHooks() {
	}

	public static VertexConsumer wrap(RenderType renderType, VertexConsumer consumer) {
		if (consumer != null) {
			int movingAlpha = MovingBlockRenderContext.alpha();

			if (movingAlpha > 0 && movingAlpha < 255) {
				return new AlphaVertexConsumer(consumer, movingAlpha);
			}

			if (HiddenRenderTypes.isMarked(renderType)) {
				return new AlphaVertexConsumer(consumer, Alpha.getHiddenAlpha());
			}
		}

		return consumer;
	}
}
