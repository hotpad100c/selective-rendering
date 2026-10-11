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

/** 编辑选区角点及已保存选区，并同步配置、区块重建和光照。 */
public final class RegionEditor {
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

	/** 选择射线碰到的最近角点，仅更新编辑状态。 */
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

			SelectiveRenderingManager.selectedCorner = closest;
		}

		return closest >= 0;
	}

	private static AABB cornerBox(BlockPos pos) {
		return new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
	}

	/** 沿指定方向移动当前选中的角点。 */
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

		Log.debug("[state] {} region(s) recorded", added);

		if (SelectiveRenderingManager.getMode().usesRegion()) {
			for (Region box : boxes) {
				HiddenSections.mark(box.box());
			}

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

	/** 从已有选区中减去 cuts；碎片数超限时放弃操作。 */
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
					Log.warn("[state] 裁剪产生的碎片过多（>{}），已放弃：选区形状过于破碎", MAX_SUBTRACT_FRAGMENTS);
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

		Log.debug("[state] regions clipped by recorder: {} -> {}", before, kept);

		if (SelectiveRenderingManager.getMode().usesRegion()) {
			// 裁剪未保留被移除的范围，需全量重建。
			RebuildScheduler.rebuildChunks();
		}

		return true;
	}

	public static boolean addRegion(Region newRegion) {
		synchronized (SelectiveRenderingManager.LOCK) {
			if (SelectiveRenderingManager.REGIONS.contains(newRegion)) {
				Log.warn("[state] region already added: {}", newRegion.toSource());
				return false;
			}

			SelectiveRenderingManager.REGIONS.add(newRegion);
			SelectiveRenderingManager.commitAndRemember();
		}

		Log.debug("[state] region added: {}", newRegion.toSource());

		if (SelectiveRenderingManager.getMode().usesRegion()) {
			HiddenSections.mark(newRegion.box());
			LightSuppression.relight();
		}

		return true;
	}

	/** 用 updated 替换 old（按几何定位）；范围或开关变了都会重建并重算光照。 */
	public static boolean updateRegion(Region old, Region updated) {
		synchronized (SelectiveRenderingManager.LOCK) {
			int index = SelectiveRenderingManager.REGIONS.indexOf(old);
			if (index < 0) {
				return false;
			}

			int clash = SelectiveRenderingManager.REGIONS.indexOf(updated);
			if (clash >= 0 && clash != index) {
				return false;
			}

			SelectiveRenderingManager.REGIONS.set(index, updated);
			SelectiveRenderingManager.commitAndRemember();
		}

		boolean blocksChanged = !old.min().equals(updated.min()) || !old.max().equals(updated.max())
			|| old.options().blocks() != updated.options().blocks()
			|| !old.options().ruleSources().equals(updated.options().ruleSources());

		if (blocksChanged && SelectiveRenderingManager.getMode().usesRegion()) {
			HiddenSections.mark(old.box());
			HiddenSections.mark(updated.box());

			LightSuppression.retire(List.of(old));
			LightSuppression.relight();
		}

		return true;
	}

	public static boolean removeRegionAt(BlockPos pos) {
		synchronized (SelectiveRenderingManager.LOCK) {
			return removeRegions(SelectiveRenderingManager.REGIONS.stream().filter(added -> added.contains(pos)).toList());
		}
	}

	/** 删除指定选区并保存、重建及还原光照。 */
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

		Log.debug("[state] {} region(s) removed", dropped.size());

		if (SelectiveRenderingManager.getMode().usesRegion()) {
			for (Region region : dropped) {
				HiddenSections.mark(region.box());
			}

			LightSuppression.retire(dropped);
			LightSuppression.relight();
		}

		return true;
	}

	/** 返回射线命中的最近选区，未命中时返回 null。 */
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

	/** 删除射线命中的最近选区，未命中则添加当前选区。 */
	public static boolean toggleRegionAlongRay(Vec3 start, Vec3 end) {
		Region hit = pickRegion(start, end);
		return hit != null ? removeRegions(List.of(hit)) : addRegionFromSelection();
	}

	/** 用当前双角点选区裁剪已保存选区，保留当前角点。 */
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

		Log.debug("[state] regions clipped by {}: {} -> {}", cut.toSource(), before.size(), kept);

		if (SelectiveRenderingManager.getMode().usesRegion()) {
			for (Region region : before) {
				HiddenSections.mark(region.box());
			}

			LightSuppression.retire(before);
			LightSuppression.relight();
		}

		return true;
	}
}
