package com.selectiverendering.region;

import com.selectiverendering.SelectiveRenderingManager;
import com.selectiverendering.lighting.LightSuppression;
import com.selectiverendering.render.HiddenSections;
import com.selectiverendering.render.RebuildScheduler;
import com.selectiverendering.util.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 选区编辑：魔杖的"双角点" + 已存选区列表的增 / 删 / 裁 / 点选。
 *
 * <p>本类<b>不持有状态</b>：角点、{@code REGIONS} 与锁都在 {@link SelectiveRenderingManager} 里
 * （渲染线程读的快照需要它们），这里只是把"怎么改"集中起来。
 * 每个改动方法的收尾都是同一套：持锁改 → {@code commit} → 锁外标记 section / 重算光照。
 */
public final class RegionEditor {
	/** 裁剪允许的最大碎片数，超过就放弃整个操作（见 {@link #subtractRegions}）。 */
	private static final int MAX_SUBTRACT_FRAGMENTS = 4096;

	private RegionEditor() {
	}

	public static BlockPos getCorner(int index) {
		return index == 0 ? SelectiveRenderingManager.config().corner1() : SelectiveRenderingManager.config().corner2();
	}

	public static AABB regionBox() {
		return SelectiveRenderingManager.config().region();
	}

	public static void setCorner(int index, BlockPos pos) {
		synchronized (SelectiveRenderingManager.LOCK) {
			if (index == 0) {
				SelectiveRenderingManager.corner1 = pos.immutable();
			}
			else {
				SelectiveRenderingManager.corner2 = pos.immutable();
			}

			SelectiveRenderingManager.selectedCorner = index;
			SelectiveRenderingManager.updateRegion();
			SelectiveRenderingManager.commit();
		}
	}

	public static boolean isCornerSelected(int index) {
		return SelectiveRenderingManager.selectedCorner == index;
	}

	/**
	 * 中键"选中角点"：视线穿过哪个角点的方块就选中哪个，两个都碰到时取近的那个。
	 *
	 * <p>对标 litematica 的 {@code RayTraceUtils.traceToSelectionBoxCorner}——
	 * 判定的就是"角点那一格"（1×1×1 的盒子），不是含有它的选区，
	 * 所以两个角点哪怕只差一格也能精准分开。
	 *
	 * <p>和 {@link #setCorner} 的区别：这里<b>只是"把哪个角点设为当前角点"</b>，
	 * 不落盘、不重建，改完直接体现在线框的填充色上
	 * （{@code LevelRendererMixin} 用 {@link #isCornerSelected} 决定画不画填充）。
	 *
	 * @return true 表示选中了某个角点；false 表示两个角点都没碰到（此时取消选中）
	 */
	public static boolean selectCorner(Vec3 start, Vec3 end) {
		int closest;

		synchronized (SelectiveRenderingManager.LOCK) {
			closest = -1;
			double closestDistance = Double.MAX_VALUE;

			for (int index = 0; index < 2; index++) {
				BlockPos pos = getCorner(index);
				if (pos == null) {
					continue;
				}

				Optional<Vec3> hit = cornerBox(pos).clip(start, end);
				if (hit.isEmpty()) {
					continue;
				}

				double distance = start.distanceToSqr(hit.get());
				if (distance < closestDistance) {
					closestDistance = distance;
					closest = index;
				}
			}

			// 点空处 = 取消选中（litematica 的 changeSelection 在 MISS 时也是清空选择）
			SelectiveRenderingManager.selectedCorner = closest;
		}

		return closest >= 0;
	}

	private static AABB cornerBox(BlockPos pos) {
		return new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
	}

	/**
	 * 把"当前选中的那个角点"沿 {@code direction} 平移 {@code amount} 格（左 Alt + 滚轮）。
	 *
	 * <p>每滚一格都会 {@link SelectiveRenderingManager#commit()} → 请求一次落盘。
	 * {@code ModConfig.save()} 带防抖，所以连续滚动不会打出一串磁盘写。
	 */
	public static void moveSelectedCorner(Direction direction, int amount) {
		synchronized (SelectiveRenderingManager.LOCK) {
			int index = SelectiveRenderingManager.selectedCorner;
			if (index < 0) {
				return;
			}

			BlockPos pos = index == 0 ? SelectiveRenderingManager.corner1 : SelectiveRenderingManager.corner2;
			if (pos == null) {
				return;
			}

			BlockPos moved = pos.relative(direction, amount).immutable();
			if (index == 0) {
				SelectiveRenderingManager.corner1 = moved;
			}
			else {
				SelectiveRenderingManager.corner2 = moved;
			}

			SelectiveRenderingManager.updateRegion();
			SelectiveRenderingManager.commit();
		}
	}

	public static boolean addRegionFromSelection() {
		BlockPos first = SelectiveRenderingManager.corner1;
		BlockPos second = SelectiveRenderingManager.corner2;
		if (first == null || second == null) {
			return false;
		}

		return addRegion(Region.of(first, second));
	}

	public static int addRegions(List<Region> boxes) {
		if (boxes.isEmpty()) {
			return 0;
		}

		int added = 0;

		synchronized (SelectiveRenderingManager.LOCK) {
			for (Region box : boxes) {
				if (!SelectiveRenderingManager.REGIONS.contains(box)) {
					SelectiveRenderingManager.REGIONS.add(box);
					added++;
				}
			}

			if (added == 0) {
				return 0;
			}

			SelectiveRenderingManager.commitAndRemember();
		}

		Log.say("[state] {} region(s) recorded", added);

		if (SelectiveRenderingManager.getMode().usesRegion()) {
			for (Region box : boxes) {
				HiddenSections.mark(box.box());
			}

			// 只排一次；每个选区各排一次会把同一片区域的光照任务叠出好几份
			LightSuppression.relight();
		}

		return added;
	}

	@Nullable
	public static Region selection() {
		BlockPos first = SelectiveRenderingManager.corner1;
		BlockPos second = SelectiveRenderingManager.corner2;

		return first == null || second == null ? null : Region.of(first, second);
	}

	/**
	 * 从所有已存选区里<b>挖掉</b> {@code cuts}（集合减法）。
	 *
	 * <h2>为什么是"先合并、再裁剪"</h2>
	 * <p>调用方给的是一堆散点坐标。如果逐点裁剪，每挖掉一个方块位置都要把选区
	 * 切成最多 6 块，几万个位置瞬间就会碎片爆炸。所以流程必须是：
	 * <ol>
	 *   <li>先用 {@link RegionMerger#merge} 把散点贪心压成尽量少的长方体（且不重叠）；</li>
	 *   <li>再用这些长方体做集合减法。</li>
	 * </ol>
	 *
	 * <h2>减法本身</h2>
	 * <p>{@link Region#subtract} 一次只能挖掉一个盒子，所以这里维护一个"当前碎片列表"，
	 * 逐个 cut 去切它。因为 cut 之间互不重叠，已经被某个 cut 切出来的碎片不会再被
	 * 同一个 cut 切到，碎片增长比最坏情况（6^N）温和得多。
	 *
	 * <p>碎片数仍有上限 {@link #MAX_SUBTRACT_FRAGMENTS}：一旦超过就直接放弃整个操作
	 * （<b>不修改</b>选区列表），避免出现"剪了一半"的不一致状态。真超了说明
	 * 记录到的形状过于破碎，提示用户缩小范围重录。
	 *
	 * @return true 表示确实改动了选区列表
	 */
	public static boolean subtractRegions(List<Region> cuts) {
		if (cuts.isEmpty()) {
			return false;
		}

		int before;
		int kept;

		synchronized (SelectiveRenderingManager.LOCK) {
			if (SelectiveRenderingManager.REGIONS.isEmpty()) {
				return false;
			}

			before = SelectiveRenderingManager.REGIONS.size();

			List<Region> current = new ArrayList<>(SelectiveRenderingManager.REGIONS);

			for (Region cut : cuts) {
				List<Region> next = new ArrayList<>(current.size() + 4);

				for (Region region : current) {
					next.addAll(region.subtract(cut));
				}

				if (next.size() > MAX_SUBTRACT_FRAGMENTS) {
					Log.say("[state] 裁剪产生的碎片过多（>{}），已放弃：选区形状过于破碎", MAX_SUBTRACT_FRAGMENTS);
					return false;
				}

				current = next;
			}

			SelectiveRenderingManager.REGIONS.clear();
			for (Region region : current) {
				if (!SelectiveRenderingManager.REGIONS.contains(region)) {
					SelectiveRenderingManager.REGIONS.add(region);
				}
			}

			kept = SelectiveRenderingManager.REGIONS.size();
			SelectiveRenderingManager.commitAndRemember();
		}

		Log.say("[state] regions clipped by recorder: {} -> {}", before, kept);

		if (SelectiveRenderingManager.getMode().usesRegion()) {
			// 这里拿不到被挖掉的具体范围（裁剪是逐碎片进行的），直接全量重建最简单可靠
			RebuildScheduler.rebuildChunks();
		}

		return true;
	}

	public static boolean addRegion(Region newRegion) {
		synchronized (SelectiveRenderingManager.LOCK) {
			if (SelectiveRenderingManager.REGIONS.contains(newRegion)) {
				Log.say("[state] region already added: {}", newRegion.toSource());
				return false;
			}

			SelectiveRenderingManager.REGIONS.add(newRegion);
			SelectiveRenderingManager.commitAndRemember();
		}

		Log.say("[state] region added: {}", newRegion.toSource());

		if (SelectiveRenderingManager.getMode().usesRegion()) {
			HiddenSections.mark(newRegion.box());
			LightSuppression.relight();
		}

		return true;
	}

	public static boolean removeRegionAt(BlockPos pos) {
		synchronized (SelectiveRenderingManager.LOCK) {
			return removeRegions(SelectiveRenderingManager.REGIONS.stream().filter(added -> added.contains(pos)).toList());
		}
	}

	/**
	 * 删掉这批选区，并做完收尾（落盘 / 标记 section / 还原光照）。
	 *
	 * <p>{@link #removeRegionAt}、以及"对着框点一下就删"都走这里。
	 */
	public static boolean removeRegions(List<Region> targets) {
		List<Region> dropped;

		synchronized (SelectiveRenderingManager.LOCK) {
			dropped = targets.stream().filter(SelectiveRenderingManager.REGIONS::contains).toList();
			if (dropped.isEmpty()) {
				return false;
			}

			SelectiveRenderingManager.REGIONS.removeAll(dropped);
			SelectiveRenderingManager.commitAndRemember();
		}

		Log.say("[state] {} region(s) removed", dropped.size());

		if (SelectiveRenderingManager.getMode().usesRegion()) {
			for (Region region : dropped) {
				HiddenSections.mark(region.box());
			}

			// 被删掉的选区已经不在快照里了，但它的光照仍需还原
			LightSuppression.retire(dropped);
			LightSuppression.relight();
		}

		return true;
	}

	/**
	 * 视线（起点 {@code start} → 终点 {@code end}）穿过的所有选区里，<b>最近的那个</b>。
	 *
	 * <p>对标 litematica 的 {@code RayTraceUtils.traceToSelectionBoxBody}：不要求
	 * "点到的方块在选区内"，而是直接拿射线去截选区的盒子，
	 * 所以隔着空气、隔着被淡化的方块也能选中它。
	 *
	 * @return 最近被命中的选区；一个都没碰到时为 null
	 */
	@Nullable
	public static Region pickRegion(Vec3 start, Vec3 end) {
		synchronized (SelectiveRenderingManager.LOCK) {
			Region closest = null;
			double closestDistance = Double.MAX_VALUE;

			for (Region region : SelectiveRenderingManager.REGIONS) {
				Optional<Vec3> hit = region.box().clip(start, end);
				if (hit.isEmpty()) {
					continue;
				}

				double distance = start.distanceToSqr(hit.get());
				if (distance < closestDistance) {
					closestDistance = distance;
					closest = region;
				}
			}

			return closest;
		}
	}

	/**
	 * 魔杖左键的"增删选区"：<b>"删"看射线穿过的最近选区</b>，"加"用当前双角点选区。
	 *
	 * <p>这条分支不要求点到方块——只要视线穿过某个已存选区的盒子就删它
	 * （见 {@link #pickRegion}），这也是 litematica 的元素选择手感。
	 */
	public static boolean toggleRegionAlongRay(Vec3 start, Vec3 end) {
		Region hit = pickRegion(start, end);
		return hit != null ? removeRegions(List.of(hit)) : addRegionFromSelection();
	}

	/**
	 * "裁剪"：把当前双角点围出的盒子从所有已存选区里<b>挖掉</b>（集合减法）。
	 * 一个选区被挖掉中间一块后会被 {@link Region#subtract} 拆成最多 6 个小盒子。
	 *
	 * <p>裁剪<b>不会</b>动当前选区的两个角点——裁完还能继续用同一个框去挖别的地方。
	 * （早期实现裁完会把角点清空，等于当前选区丢失，不好用。）
	 */
	public static boolean clipRegions() {
		BlockPos first = SelectiveRenderingManager.corner1;
		BlockPos second = SelectiveRenderingManager.corner2;
		if (first == null || second == null) {
			return false;
		}

		Region cut = Region.of(first, second);
		List<Region> before;
		int kept;

		synchronized (SelectiveRenderingManager.LOCK) {
			if (SelectiveRenderingManager.REGIONS.isEmpty()) {
				return false;
			}

			before = List.copyOf(SelectiveRenderingManager.REGIONS);
			SelectiveRenderingManager.REGIONS.clear();

			for (Region region : before) {
				for (Region part : region.subtract(cut)) {
					if (!SelectiveRenderingManager.REGIONS.contains(part)) {
						SelectiveRenderingManager.REGIONS.add(part);
					}
				}
			}

			kept = SelectiveRenderingManager.REGIONS.size();

			SelectiveRenderingManager.commitAndRemember();
		}

		Log.say("[state] regions clipped by {}: {} -> {}", cut.toSource(), before.size(), kept);

		if (SelectiveRenderingManager.getMode().usesRegion()) {
			for (Region region : before) {
				HiddenSections.mark(region.box());
			}

			// before 是裁剪前的选区，裁剪后其中一部分已经不在列表里了，同样要还原光照
			LightSuppression.retire(before);
			LightSuppression.relight();
		}

		return true;
	}
}
