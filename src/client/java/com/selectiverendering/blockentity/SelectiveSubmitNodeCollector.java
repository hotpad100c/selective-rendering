package com.selectiverendering.blockentity;

import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;

/**
 * 1.21.9+ 新增渲染架构（{@code SubmitNodeCollector}）下的方块实体淡化方案。
 *
 * <h2>它解决什么问题</h2>
 * <p>新版渲染里，BE 的顶点不是我们直接写进去的，而是"提交 ({@code submit}) 一个节点"，
 * 由引擎统一处理。想给 BE 上透明度，唯一能改的就是<b>提交时用的 RenderType</b>。
 *
 * <h2>做法</h2>
 * <ol>
 *   <li>{@code BlockEntityRenderDispatcherMixin} 把这个包装器套在真正的 collector 外面；</li>
 *   <li>所有 {@code submitXxx(...)} 都转发给 {@code base.order(0)}，
 *       用 RenderType 画几何的那几个，转发前把 {@code RenderType} 换成
 *       {@link TranslucentRenderTypes#translucentVariant} 的半透明变体
 *       （方法清单和换法见 {@link TranslucentSubmitForwarder}）；</li>
 *   <li>{@link #order(int)} 返回的分层也套同样的包装。</li>
 * </ol>
 *
 * <p>为什么直接走 {@code base.order(0)}：原版 {@code SubmitNodeStorage} 自己的
 * {@code submitXxx} 实现就是 {@code order(0).submitXxx(...)}，这里只是照抄它的分派方式，
 * 所以行为等价。<b>如果原版以后改了这个约定，这里要同步改。</b>
 */
public class SelectiveSubmitNodeCollector extends TranslucentSubmitForwarder implements SubmitNodeCollector {
	private final SubmitNodeCollector base;

	public SelectiveSubmitNodeCollector(SubmitNodeCollector base) {
		this.base = base;
	}

	@Override
	public OrderedSubmitNodeCollector order(int order) {
		return new Layer(base.order(order));
	}

	@Override
	protected OrderedSubmitNodeCollector target() {
		return base.order(0);
	}

	/** {@link #order(int)} 返回的分层：同样的转发规则，目的地就是被包的那一层。 */
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
