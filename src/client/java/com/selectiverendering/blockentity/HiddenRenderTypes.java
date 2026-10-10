package com.selectiverendering.blockentity;

import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * "这一帧里，哪些 {@link RenderType} 需要被强行修改透明度" 的临时标记集合。
 */
public final class HiddenRenderTypes {
	private static final Set<RenderType> MARKED = Collections.newSetFromMap(new IdentityHashMap<>());

	private HiddenRenderTypes() {
	}

	public static void mark(RenderType renderType) {
		MARKED.add(renderType);
	}

	public static boolean isMarked(RenderType renderType) {
		return MARKED.contains(renderType);
	}

	public static void clear() {
		MARKED.clear();
	}
}
