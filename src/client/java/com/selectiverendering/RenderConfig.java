package com.selectiverendering;

import com.selectiverendering.SelectiveRenderingManager.Mode;
import com.selectiverendering.SelectiveRenderingManager.RecordApplyMode;
import com.selectiverendering.SelectiveRenderingManager.RecordMode;
import com.selectiverendering.config.Preset;
import com.selectiverendering.matchingrule.BlockMatchRule;
import com.selectiverendering.region.Region;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 不可变的渲染配置快照，供渲染侧统一读取。 */
public record RenderConfig(
	Mode mode,
	int transparency,
	boolean invert,
	List<BlockMatchRule> rules,
	List<BlockMatchRule> recorded,
	List<Region> regions,
	List<Preset> presets,
	RecordMode recordMode,
	RecordApplyMode recordApplyMode,
	String wand,
	boolean fullBright,
	boolean passThrough,
	BlockPos corner1,
	BlockPos corner2,
	AABB region,
	int alpha,
	Region union
) {
	static RenderConfig initial() {
		return of(
			SelectiveRenderingManager.DEFAULT_MODE, SelectiveRenderingManager.DEFAULT_TRANSPARENCY, SelectiveRenderingManager.DEFAULT_INVERT,
			List.of(), List.of(), List.of(), List.of(),
			SelectiveRenderingManager.DEFAULT_RECORD_MODE, SelectiveRenderingManager.DEFAULT_RECORD_APPLY_MODE,
			SelectiveRenderingManager.DEFAULT_WAND, SelectiveRenderingManager.DEFAULT_FULL_BRIGHT, SelectiveRenderingManager.DEFAULT_PASS_THROUGH,
			null, null, null
		);
	}

	static RenderConfig of(
		Mode mode,
		int transparency,
		boolean invert,
		List<BlockMatchRule> rules,
		List<BlockMatchRule> recorded,
		List<Region> regions,
		List<Preset> presets,
		RecordMode recordMode,
		RecordApplyMode recordApplyMode,
		String wand,
		boolean fullBright,
		boolean passThrough,
		@Nullable BlockPos corner1,
		@Nullable BlockPos corner2,
		@Nullable AABB region
	) {
		return new RenderConfig(
			mode, transparency, invert,
			rules, recorded, regions, presets,
			recordMode, recordApplyMode,
			wand, fullBright, passThrough,
			corner1, corner2, region,
			Math.round(255F * (SelectiveRenderingManager.MAX_TRANSPARENCY - transparency) / SelectiveRenderingManager.MAX_TRANSPARENCY),
			unionOf(regions)
		);
	}

	/** 所有选区的包围盒；没有选区时返回 null。 */
	@Nullable
	private static Region unionOf(List<Region> regions) {
		if (regions.isEmpty()) {
			return null;
		}

		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;

		for (Region region : regions) {
			BlockPos min = region.min();
			BlockPos max = region.max();

			minX = Math.min(minX, min.getX());
			minY = Math.min(minY, min.getY());
			minZ = Math.min(minZ, min.getZ());
			maxX = Math.max(maxX, max.getX());
			maxY = Math.max(maxY, max.getY());
			maxZ = Math.max(maxZ, max.getZ());
		}

		return new Region(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
	}
}
