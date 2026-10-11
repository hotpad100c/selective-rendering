package com.selectiverendering.render;

import com.selectiverendering.RenderConfig;
import com.selectiverendering.SelectiveRenderingManager;
import com.selectiverendering.SelectiveRenderingManager.Mode;
import com.selectiverendering.matchingrule.BlockMatchRule;
import com.selectiverendering.region.Region;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/*** 方块、实体、粒子是否被选区影响，以及用什么透明度*/
public final class RegionTargets {
	private static final int MAX_CACHED_ENTRIES = 4096;

	private static final ThreadLocal<StateCache> STATE_CACHE = ThreadLocal.withInitial(StateCache::new);

	/** 按线程缓存方块的判定结果，配置快照更换时失效。 */
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

	private RegionTargets() {
	}

	/** 返回方块的顶点 alpha，-1 表示正常渲染 */
	public static int blockAlpha(BlockState state, BlockPos pos, boolean moving) {
		RenderConfig current = SelectiveRenderingManager.config();
		Mode mode = current.mode();
		if (mode == Mode.OFF) {
			return -1;
		}

		boolean needsRegion = mode.usesRegion();
		boolean needsList = mode.usesList();

		List<Region> regions = needsRegion ? current.blockRegions() : List.of();
		List<BlockMatchRule> rules = needsList ? current.rules() : List.of();
		if ((needsRegion && regions.isEmpty()) || (needsList && rules.isEmpty() && !(needsRegion && current.hasLocalRules()))) {
			return -1;
		}

		boolean carried = moving || state.getBlock() instanceof MovingPistonBlock;
		if (carried) {
			Minecraft minecraft = Minecraft.getInstance();
			BlockEntity entity = state.getBlock() instanceof MovingPistonBlock && minecraft.level != null
				? Alpha.queryBlockEntity(pos, minecraft.level)
				: null;
			if (entity instanceof PistonMovingBlockEntity piston) {
				state = piston.getMovedState();
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

		int result = hidden(current, inside, listed) ? current.alpha() : -1;

		if (cacheable) {
			remember(cache.alpha, state, result);
		}

		return result;
	}

	/** 返回实体应使用的透明度，-1 表示正常渲染 */
	public static int entityAlpha(EntityType<?> type, int entityId, double x, double y, double z) {
		RenderConfig current = SelectiveRenderingManager.config();

		return affected(current, current.entityRegions(), region -> region.options().listsEntity(type, entityId), x, y, z) ? current.alpha() : -1;
	}

	/** 返回粒子应使用的透明度，-1 表示正常渲染 */
	public static int particleAlpha(ParticleType<?> type, double x, double y, double z) {
		RenderConfig current = SelectiveRenderingManager.config();

		return affected(current, current.particleRegions(), region -> region.options().listsParticle(type), x, y, z) ? current.alpha() : -1;
	}

	/** 三类目标共用的最终判定：模式决定内外和名单内外，反转取反。 */
	private static boolean hidden(RenderConfig current, boolean inside, boolean listed) {
		boolean matched = switch (current.mode()) {
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

		return current.invert() != matched;
	}

	/** 实体和粒子：只看区域类模式；选区内用包含该位置的选区的名单，选区外用所有选区名单的并集。 */
	private static boolean affected(RenderConfig current, List<Region> regions, Predicate<Region> listed, double x, double y, double z) {
		Mode mode = current.mode();

		if (!mode.usesRegion() || regions.isEmpty()) {
			return false;
		}

		boolean outsideMode = mode == Mode.REGION_OUTSIDE || mode == Mode.REGION_OUTSIDE_LISTED || mode == Mode.REGION_OUTSIDE_UNLISTED;
		boolean inside = false;
		boolean insideListed = false;

		for (Region region : regions) {
			if (contains(region, x, y, z)) {
				inside = true;

				if (!mode.usesList() || listed.test(region)) {
					insideListed = true;
					break;
				}
			}
		}

		boolean isListed = mode.usesList() && (outsideMode ? anyListed(regions, listed) : insideListed);

		return hidden(current, inside, isListed);
	}

	private static boolean anyListed(List<Region> regions, Predicate<Region> listed) {
		for (Region region : regions) {
			if (listed.test(region)) {
				return true;
			}
		}

		return false;
	}

	private static boolean contains(Region region, double x, double y, double z) {
		return x >= region.min().getX() && x < region.max().getX() + 1.0
			&& y >= region.min().getY() && y < region.max().getY() + 1.0
			&& z >= region.min().getZ() && z < region.max().getZ() + 1.0;
	}

	/** 判断坐标是否位于选区内，以包围盒和区段缓存加速排除 */
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
