package com.selectiverendering.render;

import com.selectiverendering.RenderConfig;
import com.selectiverendering.SelectiveRenderingManager.Mode;
import com.selectiverendering.SelectiveRenderingManager;
import com.selectiverendering.matchingrule.BlockMatchRule;
import com.selectiverendering.region.Region;
import com.selectiverendering.util.BlockPosScratch;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 根据配置快照判断方块是否淡化及其透明度。 */
public final class Alpha {
	private static final int MAX_CACHED_ENTRIES = 4096;

	private static final ThreadLocal<StateCache> STATE_CACHE = ThreadLocal.withInitial(StateCache::new);

	/** 按线程缓存判定结果，配置快照更换时失效。 */
	private static final class StateCache {
		private RenderConfig owner;

		private final Map<BlockState, Integer> alpha = new HashMap<>();
		private final Map<BlockState, Integer> listed = new HashMap<>();
		private final Map<Long, Integer> sections = new HashMap<>();

		private void reset(RenderConfig owner) {
			this.owner = owner;
			this.alpha.clear();
			this.listed.clear();
			this.sections.clear();
		}
	}

	private Alpha() {
	}

	public static int getAlpha(BlockState state, BlockPos pos) {
		return getAlpha(state, pos, false);
	}

	/** 返回顶点 alpha 并记录受影响区段；-1 表示正常渲染，0..255 表示透明度。 */
	public static int getAlpha(BlockState state, BlockPos pos, boolean moving) {
		int alpha = alphaNow(state, pos, moving);
		if (alpha < 0) {
			return -1;
		}

		HiddenSections.note(pos);
		return alpha;
	}
	// 排队查询方块实体，避免立即创建实例。
	public static BlockEntity queryBlockEntity(BlockPos pos, Level level){
		return level.getChunkAt(pos).getBlockEntity(pos, LevelChunk.EntityCreationType.QUEUED);
	}

	/** 返回顶点 alpha，不记录受影响区段；-1 表示正常渲染。 */
	public static int alphaNow(BlockState state, BlockPos pos, boolean moving) {
		RenderConfig current = SelectiveRenderingManager.config();
		Mode mode = current.mode();
		if (mode == Mode.OFF) {
			return -1;
		}

		boolean needsRegion = mode.usesRegion();
		boolean needsList = mode.usesList();

		List<Region> regions = needsRegion ? current.blockRegions() : List.of();
		List<BlockMatchRule> rules = needsList ? current.rules() : List.of();

		// 空名单或空选区不参与反转，避免意外淡化整个世界。
		if ((needsRegion && regions.isEmpty()) || (needsList && rules.isEmpty() && !(needsRegion && current.hasLocalRules()))) {
			return -1;
		}

		boolean carried = moving || state.getBlock() instanceof MovingPistonBlock;
		if (carried) {
			Minecraft minecraft = Minecraft.getInstance();
			BlockEntity entity = state.getBlock() instanceof MovingPistonBlock && minecraft.level != null
				? queryBlockEntity(pos, minecraft.level)
				: null;
			if (entity instanceof PistonMovingBlockEntity piston) {
                state = piston.getMovedState();
                // 保留调用方持有的坐标，不能复用可变槽位。
                pos = pos.relative(piston.getMovementDirection().getOpposite());
            }
		}

		StateCache cache = STATE_CACHE.get();
		if (cache.owner != current) {
			cache.reset(current);
		}

		boolean cacheable = !needsRegion && !carried;
		if (cacheable) {
			Integer cached = cache.alpha.get(state);
			if (cached != null) {
				return cached;
			}
		}

		boolean listed = false;
		if (needsList) {
			Integer cached = carried ? null : cache.listed.get(state);
			if (cached != null) {
				listed = cached != 0;
			}
			else {
				listed = matches(state, carried, rules);
				if (!carried) {
					remember(cache.listed, state, listed ? 1 : 0);
				}
			}
		}

		boolean inside = false;
		if (needsRegion) {
			inside = insideRegion(pos, regions, current.union(), cache);

			if (inside && needsList && !listed && current.hasLocalRules()) {
				listed = listedLocally(state, pos, carried, regions);
			}
		}

		boolean matched = switch (mode) {
			case REGION_INSIDE_LISTED -> inside && listed;
			case REGION_INSIDE_UNLISTED -> inside && !listed;
			case REGION_INSIDE -> inside;
			case REGION_OUTSIDE_LISTED -> !inside && listed;
			case REGION_OUTSIDE_UNLISTED -> !inside && !listed;
			case REGION_OUTSIDE -> !inside;
			case BLACKLIST -> listed;
			case WHITELIST -> !listed;
			case OFF -> false;
		};

		boolean hidden = current.invert() != matched;

		int result = hidden ? current.alpha() : -1;

		if (cacheable) {
			remember(cache.alpha, state, result);
		}

		return result;
	}

	/** 判断坐标是否位于选区内，以包围盒和区段缓存加速排除。 */
	private static boolean insideRegion(BlockPos pos, List<Region> regions, @Nullable Region union, StateCache cache) {
		if (union == null || !union.contains(pos)) {
			return false;
		}

		long section = SectionPos.asLong(pos);
		Integer hit = cache.sections.get(section);
		if (hit == null) {
			hit = sectionIntersects(
				SectionPos.x(section) * 16,
				SectionPos.y(section) * 16,
				SectionPos.z(section) * 16,
				regions
			) ? 1 : 0;
			remember(cache.sections, section, hit);
		}

		return hit != 0 && isInside(pos, regions);
	}

	/** 判断边长为 16 的区段是否与选区相交。 */
	private static boolean sectionIntersects(int sx, int sy, int sz, List<Region> regions) {
		int maxX = sx + 15;
		int maxY = sy + 15;
		int maxZ = sz + 15;

		for (Region region : regions) {
			if (region.max().getX() >= sx && region.min().getX() <= maxX
				&& region.max().getY() >= sy && region.min().getY() <= maxY
				&& region.max().getZ() >= sz && region.min().getZ() <= maxZ) {
				return true;
			}
		}

		return false;
	}


	private static <K> void remember(Map<K, Integer> map, K key, int value) {
		if (map.size() >= MAX_CACHED_ENTRIES) {
			map.clear();
		}

		map.put(key, value);
	}

	public static int getFluidAlpha(BlockState state, BlockPos pos) {
		return getAlpha(state, pos);
	}

	/** 当前设置是否要求所有流体使用半透明层。 */
	public static boolean shouldRenderFluidsTranslucent() {
		RenderConfig current = SelectiveRenderingManager.config();
		if (current.mode() == Mode.OFF) {
			return false;
		}

		return current.transparency() < SelectiveRenderingManager.MAX_TRANSPARENCY;
	}

	public static boolean isHidden(BlockState state, BlockPos pos) {
		return getAlpha(state, pos) >= 0;
	}

	public static boolean isHidden(BlockGetter level, BlockPos pos) {
		return getAlpha(level.getBlockState(pos), pos) >= 0;
	}

	/** 使用调用方提供的世界数据判断是否完全隐藏，不记录区段。 */
	public static boolean isHiddenIn(@Nullable BlockGetter level, BlockPos pos) {
		if (level == null) {
			return false;
		}

		return alphaNow(level.getBlockState(pos), pos, false) >= 0;
	}

	/** 判断方块是否被淡化，不记录区段。 */
	public static boolean isFaded(BlockState state, BlockPos pos) {
		return alphaNow(state, pos, false) >= 0;
	}

	public static boolean isHiddenAt(BlockPos pos) {
		return getAlphaAt(pos) >= 0;
	}

	/** 查询客户端世界中的顶点 alpha 并记录区段，仅限客户端线程使用。 */
	public static int getAlphaAt(BlockPos pos) {
		if (SelectiveRenderingManager.getMode() == Mode.OFF) {
			return -1;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return -1;
		}

		return getAlpha(minecraft.level.getBlockState(pos), pos);
	}

	/** 查询客户端世界中的顶点 alpha，不记录区段。 */
	public static int alphaAt(BlockPos pos) {
		if (SelectiveRenderingManager.getMode() == Mode.OFF) {
			return -1;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return -1;
		}

		return alphaNow(minecraft.level.getBlockState(pos), pos, false);
	}

	/** 供 EntityCulling 判断指定坐标是否被淡化。 */
	public static boolean isFadedCube(int x, int y, int z) {
		return alphaAt(BlockPosScratch.at(x, y, z)) >= 0;
	}

	public static int getHiddenAlpha() {
		return SelectiveRenderingManager.config().alpha();
	}

	private static boolean matches(BlockState state, boolean moving, List<BlockMatchRule> rules) {
		for (BlockMatchRule rule : rules) {
			if (rule.matches(state, moving)) {
				return true;
			}
		}

		return false;
	}

	private static boolean listedLocally(BlockState state, BlockPos pos, boolean moving, List<Region> regions) {
		for (Region added : regions) {
			if (!added.options().rules().isEmpty() && added.contains(pos) && matches(state, moving, added.options().rules())) {
				return true;
			}
		}

		return false;
	}

	private static boolean isInside(BlockPos pos, List<Region> regions) {
		for (Region added : regions) {
			if (added.contains(pos)) {
				return true;
			}
		}

		return false;
	}
}
