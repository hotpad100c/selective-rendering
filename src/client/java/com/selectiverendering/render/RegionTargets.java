package com.selectiverendering.render;

import com.selectiverendering.RenderConfig;
import com.selectiverendering.SelectiveRenderingManager;
import com.selectiverendering.SelectiveRenderingManager.Mode;
import com.selectiverendering.region.Region;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.world.entity.EntityType;

import java.util.List;
import java.util.function.Predicate;

/** 选区目标 */
public final class RegionTargets {
	private RegionTargets() {
	}

	/** 返回实体应使用的透明度，-1 表示正常渲染 */
	public static int entityAlpha(EntityType<?> type, double x, double y, double z) {
		RenderConfig current = SelectiveRenderingManager.config();
		List<Region> regions = current.entityRegions();

		if (!current.mode().usesRegion() || regions.isEmpty() || !listsType(regions, type)) {
			return -1;
		}

		return hidden(current, regions, region -> region.options().matchesEntity(type), x, y, z) ? current.alpha() : -1;
	}

	/** 返回粒子应使用的透明度，-1 表示正常渲染 */
	public static int particleAlpha(ParticleType<?> type, double x, double y, double z) {
		RenderConfig current = SelectiveRenderingManager.config();
		List<Region> regions = current.particleRegions();

		if (!current.mode().usesRegion() || regions.isEmpty()) {
			return -1;
		}

		boolean listed = false;
		for (Region region : regions) {
			if (region.options().matchesParticle(type)) {
				listed = true;
				break;
			}
		}

		if (!listed) {
			return -1;
		}

		return hidden(current, regions, region -> region.options().matchesParticle(type), x, y, z) ? current.alpha() : -1;
	}

	private static boolean listsType(List<Region> regions, EntityType<?> type) {
		for (Region region : regions) {
			if (region.options().matchesEntity(type)) {
				return true;
			}
		}

		return false;
	}

	private static boolean hidden(RenderConfig current, List<Region> regions, Predicate<Region> applies, double x, double y, double z) {
		Mode mode = current.mode();
		boolean inside = inside(regions, applies, x, y, z);
		boolean outsideMode = mode == Mode.REGION_OUTSIDE || mode == Mode.REGION_OUTSIDE_LISTED || mode == Mode.REGION_OUTSIDE_UNLISTED;

		return current.invert() ^ outsideMode ^ inside;
	}

	private static boolean inside(List<Region> regions, Predicate<Region> applies, double x, double y, double z) {
		for (Region region : regions) {
			if (x >= region.min().getX() && x < region.max().getX() + 1.0
				&& y >= region.min().getY() && y < region.max().getY() + 1.0
				&& z >= region.min().getZ() && z < region.max().getZ() + 1.0
				&& applies.test(region)) {
				return true;
			}
		}

		return false;
	}
}
