package com.selectiverendering.render;

import com.selectiverendering.SelectiveRenderingManager.Mode;
import com.selectiverendering.SelectiveRenderingManager;
import com.selectiverendering.compat.Platform;
import com.selectiverendering.lighting.LightSuppression;
import com.selectiverendering.region.RegionEditor;

/**
 * 重建调度：隐藏集合 / 透明度变了之后，决定"重烘焙哪些 section、要不要重算光照"。
 *
 * <p>三档，从便宜到贵：
 * <ol>
 *   <li>{@link #rebuildHidden()}：只重建记过账的那些 section（见 {@link HiddenSections}），
 *       记账太多时自动升级成第 3 档；</li>
 *   <li>{@link #rebuildAfterListChange}：按"名单增删是否翻转了大批方块的可见性"在 1 / 3 之间选；</li>
 *   <li>{@link #rebuildChunks()}：全量——清记账、重烘焙全部 section、重算光照。</li>
 * </ol>
 * 只有"选区变了"的路径（见 {@link RegionEditor}）会自己 {@code HiddenSections.mark(box)} + 光照，
 * 不经过这里。
 */
public final class RebuildScheduler {
	private RebuildScheduler() {
	}

	/**
	 * "增量重建"：只重建被隐藏过、且数量不多的那些 section。
	 * {@link HiddenSections#mark()} 返回 false 表示记账太多（不值得精算）或世界还没准备好，
	 * 这时退化成全量重建。
	 */
	public static void rebuildHidden() {
		if (!HiddenSections.mark()) {
			rebuildChunks();
		}
	}

	/**
	 * 名单（{@link SelectiveRenderingManager#ruleSources()}）增删一条后的重建策略。
	 *
	 * @param added true = 往名单里加了一条规则
	 *              <p>逻辑：如果"新增的这批方块正好是本模式要隐藏的那一批"
	 *              （{@link Mode#hidesListed()} == added），受影响的只有刚加入的那一个方块，
	 *              增量重建即可；反之意味着大量方块的可见性翻转，只能全量重建。
	 */
	public static void rebuildAfterListChange(boolean added) {
		Mode mode = SelectiveRenderingManager.getMode();
		if (!mode.usesList()) {
			return;
		}

		if (SelectiveRenderingManager.isInvert()) {
			// 反转下"命中集合"和"被淡化集合"是互补的，上面的增量推断整个反了过来，
			// 直接全量重建最稳（名单增删本来就不是高频操作）。
			rebuildChunks();
			return;
		}

		if (mode.hidesListed() == added) {
			rebuildChunks();
			return;
		}

		rebuildHidden();
	}

	/**
	 * 全量重建：清空隐藏记账 → {@code LevelRenderer.allChanged()}（所有 section 重新烘焙）
	 * → 对所有选区重算光照。
	 *
	 * <p>必须重算光照的原因：我们把被隐藏的方块在光照引擎里伪装成空气
	 * （见 {@code LightEngineMixin}），光照数据已经变了，不重算的话
	 * 隐藏/恢复切换后会残留错误的亮度。
	 */
	public static void rebuildChunks() {
		HiddenSections.clear();
		Platform.rebuildAll();
		LightSuppression.relight();
	}
}
