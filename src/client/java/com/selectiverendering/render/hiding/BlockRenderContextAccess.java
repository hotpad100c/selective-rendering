package com.selectiverendering.render.hiding;

import org.jetbrains.annotations.Nullable;

/** 共享 Sodium 当前方块的 alpha；null 表示无上下文，-1 表示正常渲染，0..255 表示淡化。 */
public interface BlockRenderContextAccess {
	@Nullable
	Integer selectiveRendering$alpha();

	void selectiveRendering$setAlpha(@Nullable Integer alpha);
}
