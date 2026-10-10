package com.selectiverendering.blockentity;

import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.jspecify.annotations.NonNull;

/** 包装渲染提交器，将提交转发到半透明渲染管线。 */
public class SelectiveSubmitNodeCollector extends TranslucentSubmitForwarder implements SubmitNodeCollector {
	private final SubmitNodeCollector base;

	public SelectiveSubmitNodeCollector(SubmitNodeCollector base) {
		this.base = base;
	}

	@Override
	public @NonNull OrderedSubmitNodeCollector order(int order) {
		return new Layer(base.order(order));
	}

	@Override
	protected OrderedSubmitNodeCollector target() {
		return base.order(0);
	}

	private static final class Layer extends TranslucentSubmitForwarder {
		private final OrderedSubmitNodeCollector base;

		Layer(OrderedSubmitNodeCollector base) {
			this.base = base;
		}

		@Override
		protected OrderedSubmitNodeCollector target() {
			return base;
		}
	}
}
