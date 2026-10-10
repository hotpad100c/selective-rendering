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

/**
 * 判定层：<b>"这个方块该不该被淡化、淡化到多少"</b>——整个 Mod 渲染侧的唯一真理来源。
 *
 * <p>只读：所有判定都基于 {@link SelectiveRenderingManager#config()} 这份不可变快照，
 * 不加锁，可以被区块构建线程 / 光照线程 / 渲染线程并发调用。
 * 写状态的事归 {@link SelectiveRenderingManager}，改完选区后的重建归 {@link RebuildScheduler}。
 *
 * <h2>返回值的约定（非常重要）</h2>
 * <p>{@link #getAlpha} 返回 {@code -1} 表示"这个方块正常渲染"；
 * 返回 {@code 0..255} 表示"这个方块要被淡化"，值就是顶点 alpha。
 * <ul>
 *   <li>{@code alpha == 0}　→ 完全隐藏（透明度过 100%），mixin 直接 {@code ci.cancel()}；</li>
 *   <li>{@code 0 < alpha < 255} → 半透明，保留几何但改顶点色 + 换到半透明层；</li>
 *   <li>{@code -1}　　　　　　→ 原样渲染。</li>
 * </ul>
 *
 * <h2>入口一览（先看"记不记账"、再看"手头有什么"）</h2>
 * <pre>
 *                    记账(HiddenSections)   纯查询(不记账)
 *   已有 state+pos    getAlpha / isHidden    alphaNow / isFaded
 *   已有 level+pos    isHidden(level,pos)    isHiddenIn   （区块构建线程用）
 *   只有 pos          getAlphaAt/isHiddenAt  alphaAt / isFadedCube
 * </pre>
 *
 * <h2>已知问题（保留现状，改动前请先读）</h2>
 * <ul>
 *   <li>{@link #alphaNow} 仍然会做 O(规则数 + 选区块数) 的线性扫描，但只在
 *       <b>缓存未命中</b>时发生：非区域模式按 {@code BlockState} 缓存最终结果，
 *       区域模式按 {@code BlockState} 缓存"是否命中名单"、按 section 缓存"是否与选区相交"。
 *       区块构建线程上不同 {@code BlockState} 通常只有几十种，所以命中率极高。
 *       真正还会线性扫的是"区域内模式的逐坐标判定"。</li>
 *   <li>{@link #alphaNow} 里为了处理"活塞推动中的方块"会去访问
 *       {@code Minecraft.getInstance().level}（客户端世界），这是<b>跨线程访问客户端世界</b>，
 *       只在 {@code carried} 为真时触发，属于可接受的脆弱点但值得警惕。</li>
 *   <li>{@link #getAlpha} 有副作用：命中时会往 {@link HiddenSections} 里记账。
 *       一个"查询"方法改全局状态，阅读时容易看漏。</li>
 * </ul>
 */
public final class Alpha {
	/** 每线程缓存的最大条目数，超过就整体清空（见 {@link StateCache}）。 */
	private static final int MAX_CACHED_ENTRIES = 4096;

	/** 每线程一份的判定缓存，见 {@link StateCache}。 */
	private static final ThreadLocal<StateCache> STATE_CACHE = ThreadLocal.withInitial(StateCache::new);

	/**
	 * 每线程一份的判定缓存。
	 *
	 * <h2>为什么必须是每线程</h2>
	 * <p>{@link #alphaNow} 会被区块构建线程、光照线程、渲染线程并发调用，
	 * 用共享 {@code HashMap} 会有数据竞争。每线程一份则完全无锁。
	 *
	 * <h2>失效策略</h2>
	 * <p>缓存只在 {@code owner == 当前的 config} 时有效：{@link SelectiveRenderingManager#publish()} 每次都会
	 * 生成新的 {@link RenderConfig} 实例，所以"配置变了"= "owner 变了"，
	 * 一次引用比较就能判断失效，不需要版本号，也不会漏掉任何一条 {@code commit*} 路径。
	 *
	 * <h2>三条缓存</h2>
	 * <ul>
	 *   <li>{@code alpha}：{@code BlockState → alpha}。<b>只在非区域模式</b>（BLACKLIST /
	 *       WHITELIST）下用——那时判定结果与坐标无关，只与方块状态有关。</li>
	 *   <li>{@code listed}：{@code BlockState → 是否命中名单}。区域模式下名单判定
	 *       同样与坐标无关，可以单独缓存，省掉逐规则的线性扫描。</li>
	 *   <li>{@code sections}：{@code section → 是否与任一选区相交}。一个 section 有 4096 格，
	 *       而不相交的 section 根本不用逐坐标扫，这是区域模式下最有效的一层。</li>
	 * </ul>
	 *
	 * <p>条目数有上限 {@link #MAX_CACHED_ENTRIES}，超了就整体清空。
	 * 换维度 / 换世界时缓存内容自然失效（BlockState 变了、坐标变了），
	 * 而 {@code owner} 不变，所以上限保护是必要的。
	 */
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

	/**
	 * <b>整个 Mod 的心脏</b>：判断一个方块是否该被淡化，返回它的顶点 alpha。
	 *
	 * <p>调用者遍布渲染管线（见 {@code mixin/}）：区块网格构建、面剔除、
	 * 平滑光照、流体渲染、方块实体渲染……全都问这一个问题。
	 * 因此它在热路径上，实现里刻意避免加锁（只读不可变快照）。
	 *
	 * @param state  方块状态（一般是 {@code level.getBlockState(pos)}）
	 * @param pos    世界坐标；注意"活塞移动中的方块"会被换算回它的来源坐标
	 * @param moving 该方块是否正被活塞推动（此时 {@code state} 是 {@code moving_piston} 的壳）
	 * @return {@code -1} 正常渲染；{@code 0..255} 顶点 alpha（0 = 完全不渲染）
	 */
	public static int getAlpha(BlockState state, BlockPos pos, boolean moving) {
		int alpha = alphaNow(state, pos, moving);
		if (alpha < 0) {
			return -1;
		}

		// 副作用：记下"这个位置被隐藏过"。改透明度时可以只重建这些 section，
		// 不必全图重建。只有真正参与画面渲染的路径需要记账，
		// 光照/AO 那种极热的路径走 {@link #alphaNow}，不记账。
		HiddenSections.note(pos);
		return alpha;
	}
	// getBlockEntity的默认实现是this.getChunkAt(pos).getBlockEntity(pos, EntityCreationType.IMMEDIATE)
	// 任意客户端线程都可以通过EntityCreationType.IMMEDIATE，潜在地立即加载（会创建新实例）新的 未加载 的方块实体
	// （虽然对于b36来说这不太可能）。但这是极度不安全的。使用CHECK或者QUEUED。
	public static BlockEntity queryBlockEntity(BlockPos pos, Level level){
		return level.getChunkAt(pos).getBlockEntity(pos, LevelChunk.EntityCreationType.QUEUED);
	}

	/**
	 * 只做判定，<b>不记账</b>。
	 *
	 * <p>光照引擎与 AO 计算会在光照线程上以百万次的量级调用它，
	 * 往 {@link HiddenSections} 里写既没有意义（很多位置根本不在已加载的 section 里）
	 * 又是从非渲染线程改全局状态。所以这两条路径用本方法。
	 *
	 * <h2>缓存</h2>
	 * <p>判定结果被拆成三层缓存（见 {@link StateCache}）：
	 * <ol>
	 *   <li>非区域模式（BLACKLIST / WHITELIST）：结果与坐标无关，直接按 {@code BlockState} 缓存；</li>
	 *   <li>区域模式的"是否命中名单"：同样与坐标无关，按 {@code BlockState} 缓存；</li>
	 *   <li>区域模式的"是否在选区内"：先比外接盒快速否定，再按 <b>section</b> 缓存
	 *       "这个 section 是否与任一选区相交"——不相交的 section 一次都不用扫。</li>
	 * </ol>
	 * 活塞推动中的方块（{@code carried}）不进缓存：它的 state / 坐标会被现场改写，
	 * 而且 {@code [moving=true]} 这类规则依赖 {@code moving} 参数，不是纯函数。
	 *
	 * @return 同 {@link #getAlpha}：-1 表示正常渲染，否则是顶点 alpha
	 */
	public static int alphaNow(BlockState state, BlockPos pos, boolean moving) {
		RenderConfig current = SelectiveRenderingManager.config();
		Mode mode = current.mode();
		if (mode == Mode.OFF) {
			return -1;
		}

		boolean needsRegion = mode.usesRegion();
		boolean needsList = mode.usesList();

		List<Region> regions = needsRegion ? current.regions() : List.of();
		List<BlockMatchRule> rules = needsList ? current.rules() : List.of();

		// 条件不齐就直接不做任何淡化。
		// 这一点在"反转"下尤其重要：反转会把命中集合取反，
		// 若此时名单/选区是空的，取反就变成"整个世界都要淡化"。
		if ((needsRegion && regions.isEmpty()) || (needsList && rules.isEmpty())) {
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
                // 这里刻意用 relative() 新建坐标而不是复用 scratch：
                // 调用方（例如 getAlphaAt）往往还持有传进来的那个 pos 并会在返回后再用一次
                // （HiddenSections.note），复用可变坐标会把它改掉。活塞路径很罕见，一次分配无所谓。
                pos = pos.relative(piston.getMovementDirection().getOpposite());
            }
		}

		StateCache cache = STATE_CACHE.get();
		if (cache.owner != current) {
			cache.reset(current);
		}

		// 第一层：整个结果按 BlockState 缓存（只在与坐标无关的模式里成立）
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

		// 反转：改成淡化"没命中"的那一批。于是"选区内 + 名单内 + 反转"
		// 就等于"只显示选区内的指定方块"，这是原来 9 个模式表达不出来的。
		boolean hidden = current.invert() != matched;

		int result = hidden ? current.alpha() : -1;

		if (cacheable) {
			remember(cache.alpha, state, result);
		}

		return result;
	}

	/**
	 * "这个坐标是否落在任一选区内"，带两级快速否定。
	 *
	 * <ol>
	 *   <li><b>外接盒</b>：{@code union} 是所有选区的包围盒，落在外面的坐标必然不在任何选区里；</li>
	 *   <li><b>section 相交</b>：一个 section 有 4096 格，先花一次代价判断
	 *       "这个 section 有没有和任一选区相交"，不相交就整段跳过。</li>
	 * </ol>
	 */
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

	/**
	 * section（原点 {@code sx,sy,sz}，边长 16）是否与任一选区相交。
	 * 只对 <b>section 覆盖到的那一块</b>做重叠判断，所以比逐坐标判定便宜三个数量级。
	 */
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


	/** 写入一条缓存，超过 {@link #MAX_CACHED_ENTRIES} 就整体清空（防止换世界后无限膨胀）。 */
	private static <K> void remember(Map<K, Integer> map, K key, int value) {
		if (map.size() >= MAX_CACHED_ENTRIES) {
			map.clear();
		}

		map.put(key, value);
	}

	public static int getFluidAlpha(BlockState state, BlockPos pos) {
		return getAlpha(state, pos);
	}

	/**
	 * 是否把所有流体强制塞进 {@code TRANSLUCENT} 层（见 {@code FluidModelMixin}）。
	 *
	 * <p>为什么要这样：流体默认走 {@code translucent} 之外的层（如 {@code cutout}），
	 * 顶点 alpha 不会生效。想让水变淡，就必须让它走半透明管线。
	 *
	 * <p>代价是<b>全局生效</b>：只要开着本 Mod 且透明度 &lt; 100%，
	 * 世界上所有水/熔岩都会进半透明层，排序开销变大。100%（完全隐藏）时不需要，
	 * 因为那时流体直接被 {@code ci.cancel()} 掉了。
	 */
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

	/**
	 * {@link #isHidden} 的<b>工作线程安全版</b>。
	 *
	 * <h2>为什么单独开一个</h2>
	 * <p>遮挡图（原版 {@code VisGraph.setOpaque}、Sodium {@code DirectionalVisGraph.setOpaque}）
	 * 与流体的面判定跑在<b>区块构建工作线程</b>上，以前走 {@link #isHiddenAt}，
	 * 后者内部会 {@code Minecraft.getInstance().level.getBlockState(pos)}——
	 * 这是从工作线程读客户端世界，也不一定对应正在构建的那一份数据。
	 *
	 * <p>这些位置本来就有一个合法的、线程隔离的数据源：
	 * 原版是 {@code SectionCompiler.compile} 的 {@code RenderSectionRegion}，
	 * Sodium 是 {@code BlockRenderCache.getWorldSlice()}。由调用方把它传进来即可。
	 *
	 * <p>另外它<b>不记账</b>：遮挡图对每个实心的格子都会问一次（一个 section 4096 次），
	 * 而这些位置在真正出网格的路径里已经被 {@link #getAlpha} 记过了，再记一遍只是浪费。
	 */
	public static boolean isHiddenIn(@Nullable BlockGetter level, BlockPos pos) {
		if (level == null) {
			return false;
		}

		return alphaNow(level.getBlockState(pos), pos, false) >= 0;
	}

	/**
	 * {@link #isHidden} 的<b>纯查询版</b>：不往 {@link HiddenSections} 记账。
	 *
	 * <p>准星射线（{@code FadedBlockGetter}）用它判断"这一格要不要当空气"。
	 * 射线在客户端主线上跑、每帧两次，不属于渲染管线，
	 * 记账只会污染"改透明度时要重建哪些 section"的集合，所以必须走 {@link #alphaNow}。
	 *
	 * <p>与 {@link #alphaAt} 的区别：那个只收坐标、自己现查方块状态；
	 * 这里由调用方把状态传进来（射线本来就要先取状态做后续判定，不重复查表）。
	 */
	public static boolean isFaded(BlockState state, BlockPos pos) {
		return alphaNow(state, pos, false) >= 0;
	}

	public static boolean isHiddenAt(BlockPos pos) {
		return getAlphaAt(pos) >= 0;
	}

	/**
	 * 只知道坐标、不知道方块状态时用的便捷版本（方块实体渲染用）。
	 * 它会现查 {@code minecraft.level.getBlockState(pos)}，
	 * 因此<b>只能在客户端线程调用</b>，不要从区块构建线程用
	 * （工作线程请改用 {@link #isHiddenIn}）。
	 */
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

	/**
	 * {@link #getAlphaAt(BlockPos)} 的<b>纯查询版</b>：不往 {@link HiddenSections} 里记账。
	 *
	 * <p>EntityCulling 的剔除线程会调用它（见 {@code EntityCullingProviderMixin}）；
	 * 记账会把非渲染线程的写入混进渲染状态，所以给兼容层用的必须是这条无副作用的路径。
	 */
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

	/**
	 * EntityCulling 兼容层用：世界坐标 {@code (x, y, z)} 这一格是否被淡化。
	 *
	 * <p>被淡化 = 视觉上是透明的，所以在剔除方的体素判定里<b>不能当实心墙</b>——
	 * 否则隔着几格淡化方块看活塞/生物，它们会被误剔除（时隐时现）。
	 */
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

	private static boolean isInside(BlockPos pos, List<Region> regions) {
		for (Region added : regions) {
			if (added.contains(pos)) {
				return true;
			}
		}

		return false;
	}
}
