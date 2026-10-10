package com.selectiverendering.render;

import com.selectiverendering.RenderConfig;
import com.selectiverendering.SelectiveRenderingManager;
import com.selectiverendering.SelectiveRenderingManager.Mode;
import com.selectiverendering.region.Region;

import java.util.List;

/** 实体和粒子是否被选区隐藏；只看区域类模式，不使用方块名单。 */
public final class RegionTargets {
	private RegionTargets() {
	}

	/** 返回实体应使用的透明度；-1 表示正常渲染。 */
	public static int entityAlpha(double x, double y, double z) {
		RenderConfig current = SelectiveRenderingManager.config();
		return hidden(current, current.entityRegions(), x, y, z) ? current.alpha() : -1;
	}

	public static boolean hidesParticle(double x, double y, double z) {
		RenderConfig current = SelectiveRenderingManager.config();
		return hidden(current, current.particleRegions(), x, y, z);
	}

	private static boolean hidden(RenderConfig current, List<Region> regions, double x, double y, double z) {
		Mode mode = current.mode();
		if (!mode.usesRegion() || regions.isEmpty()) {
			return false;
		}

		boolean inside = inside(regions, x, y, z);
		boolean outsideMode = mode == Mode.REGION_OUTSIDE || mode == Mode.REGION_OUTSIDE_LISTED || mode == Mode.REGION_OUTSIDE_UNLISTED;

		return current.invert() != (outsideMode ? !inside : inside);
	}

	private static boolean inside(List<Region> regions, double x, double y, double z) {
		for (Region region : regions) {
			if (x >= region.min().getX() && x < region.max().getX() + 1.0
				&& y >= region.min().getY() && y < region.max().getY() + 1.0
				&& z >= region.min().getZ() && z < region.max().getZ() + 1.0) {
				return true;
			}
		}

		return false;
	}
}
