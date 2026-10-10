package com.selectiverendering.lighting;

import com.selectiverendering.SelectiveRenderingManager;
import com.selectiverendering.region.Region;
import com.selectiverendering.render.Alpha;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * 光照抑制：被隐藏的方块在客户端光照里一律当成空气，并在"隐藏集合变了"或"区块带着服务端光照到了"
 * 的时候，把受影响的光照重算回来。
 *
 * <h2>三块职责</h2>
 * <ol>
 *   <li><b>伪装</b> {@link #airIfHidden}：光照引擎 / AO 的 mixin 统一入口。</li>
 *   <li><b>标记</b> {@link #relight()} / {@link #markChunk}：把"需要重算的区块"放进待办集合。
 *       前者由选区 / 模式变化触发（主线程），后者由 {@code AreaLightPacketMixin} 在收到服务端光照时触发。</li>
 *   <li><b>执行</b> {@link #flush}：由 {@code AreaLightFlushMixin} 挂在
 *       {@code LevelLightEngine.runLightUpdates} 的 RETURN，每帧按预算消化待办。</li>
 * </ol>
 *
 * <h2>为什么执行点选在 runLightUpdates 之后</h2>
 * <p>服务端发来的光照数据（{@code applyLightData}）不是立即写入的，而是 {@code queueSectionData}
 * 排队，要等下一次 {@code runLightUpdates} 才真正落地，并且会<b>覆盖</b>客户端已经算好的结果——
 * 而服务端是按方块真实存在算的。所以必须等它落地之后再 {@code checkBlock}，否则白算。
 * {@code checkBlock} 本身也只是入队，由随后的 {@code runLightUpdates} 传播，不会在本次调用里重入。
 *
 * <h2>为什么只检查选区附近</h2>
 * <p>{@code checkBlock(pos)} 的语义是"这个位置的方块变了，请重新评估"，光的扩散 / 回收由引擎自己沿邻居传播。
 * 所以只需要对<b>真正发生了变化的那些格子</b>（选区本身，外加水平外扩 1 格）下手，
 * 不需要像早期实现那样把选区外扩 15 格整块扫一遍。
 */
public final class LightSuppression {
	/** 每次 {@link #flush} 最多发出多少个 {@code checkBlock}，避免单帧卡顿。 */
	private static final int BUDGET_PER_FLUSH = 2000;

	/** 选区水平方向外扩几格一起检查：选区边缘的邻居也会受到影响。 */
	private static final int MARGIN = 1;

	private static final long NO_CHUNK = Long.MIN_VALUE;

	private static final Object LOCK = new Object();

	/**
	 * 待重算的区块（{@link ChunkPos#pack}），保持插入顺序。
	 * 集合语义天然去重：连续切模式 / 多个数据包只会让同一区块排一次队。
	 */
	private static final LongLinkedOpenHashSet PENDING = new LongLinkedOpenHashSet();

	/**
	 * "曾经被隐藏过、但现在已不在选区列表里"的区域。
	 *
	 * <p>删掉 / 裁掉一个选区之后，那块地方的光照仍然带着我们污染过的数据
	 * （当时那些方块在光照引擎里被当成空气），必须再算一遍才能恢复。
	 * 但它们已经不在选区快照里，所以要单独记一份，每次重算时一起带上。
	 *
	 * <p>只增不减。元素数等于"历史上删 / 裁过的选区总数"，每个只是一个 record，可以忽略。
	 */
	private static final List<Region> RETIRED_REGIONS = new ArrayList<>();

	/** 正在处理的区块和已处理到的列号（0..255）；一个区块跨多帧处理时用来续跑。 */
	private static long cursorChunk = NO_CHUNK;
	private static int cursorColumn;

	private LightSuppression() {
	}

	// ============================== 伪装 ==============================

	/**
	 * 光照 / 平滑光照补丁的统一入口：被隐藏的方块在计算里一律当成空气。
	 *
	 * <p>原版、Sodium、Indigo 各有自己的一套光照数据结构，所以几个 mixin
	 * 都得各打一遍补丁。判定收在这里之后，每个注入点只剩一行。
	 *
	 * @param state 该位置真实的方块状态（由调用方先取好）
	 */
	public static BlockState airIfHidden(BlockState state, BlockPos pos) {
		// 模式关闭 = 本 Mod 对整个管线零干预。
		// 下面那个 fullBright 分支不看分方块、一律"当空气"，如果这里不先短路，
		// 夜视（默认开启）+ 模式关闭时，光照引擎和 AO 会被全局改成"当空气"——
		// 结果就是全世界的 AO 消失、客户端光照数据按到处通透来算。
		if (SelectiveRenderingManager.getMode() == SelectiveRenderingManager.Mode.OFF) {
			return state;
		}

		if (SelectiveRenderingManager.isFullBright()) {
			// 夜视：一律当空气，不再逐方块判定。
			//
			// 对光照引擎来说：画面本来就全亮，"让光穿过被淡化的方块"毫无意义，
			// 而这是全 Mod 最热的一条路径（光照线程上百万次量级），直接抹掉。
			//
			// 对平滑光照(AO)来说：等于"没有任何方块产生遮蔽"。这样被淡化的方块
			// 周围不会留下遮挡暗角（否则会看到一圈像被隐形东西挡住的暗边），
			// 代价是画面失去 AO 的立体感——夜视本来就是平的，可以接受。
			// 这里参考了 meteor 的 Xray，它在 Xray 激活时直接把 AO 拉满。
			return Blocks.AIR.defaultBlockState();
		}

		return Alpha.alphaNow(state, pos, false) >= 0 ? Blocks.AIR.defaultBlockState() : state;
	}

	// ============================== 标记 ==============================

	/**
	 * 隐藏集合变了（切模式、增删 / 裁剪选区……），把所有相关区块排进待办。
	 *
	 * <p>目标 = 当前所有选区 + {@link #RETIRED_REGIONS}，两者都是"光照可能被我们污染过"的地方。
	 * 夜视开启时不做：那时本来就不依赖光照。
	 */
	public static void relight() {
		if (SelectiveRenderingManager.isFullBright()) {
			return;
		}

		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}

		List<Region> targets = targets();
		if (targets.isEmpty()) {
			return;
		}

		synchronized (LOCK) {
			for (Region region : targets) {
				int minChunkX = (region.min().getX() - MARGIN) >> 4;
				int maxChunkX = (region.max().getX() + MARGIN) >> 4;
				int minChunkZ = (region.min().getZ() - MARGIN) >> 4;
				int maxChunkZ = (region.max().getZ() + MARGIN) >> 4;

				for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
					for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
						enqueue(chunkX, chunkZ);
					}
				}
			}
		}
	}

	/** 把"已经不在选区列表里、但光照仍需还原"的区域记下来，供下一次 {@link #relight()} 使用。 */
	public static void retire(List<Region> regions) {
		synchronized (LOCK) {
			for (Region region : regions) {
				if (!RETIRED_REGIONS.contains(region)) {
					RETIRED_REGIONS.add(region);
				}
			}
		}
	}

	/**
	 * 一个区块带着服务端的光照到了（或刚启用光照），标记它在光照落地后需要重算。
	 *
	 * <p>服务端按"方块真实存在"算光，这份数据会覆盖客户端的伪装，所以必须补算。
	 * 只有区域类模式才会出现这个问题；名单类模式隐藏的方块遍布整个世界，不在本类的处理范围。
	 */
	public static void markChunk(int chunkX, int chunkZ) {
		if (!SelectiveRenderingManager.getMode().usesRegion() || SelectiveRenderingManager.isFullBright()) {
			return;
		}

		synchronized (LOCK) {
			enqueue(chunkX, chunkZ);
		}
	}

	private static void enqueue(int chunkX, int chunkZ) {
		long packed = ChunkPos.pack(chunkX, chunkZ);
		PENDING.add(packed);

		if (packed == cursorChunk) {
			// 正在处理的区块又被标记了：之前处理过的列可能已经过期，从头再来
			cursorColumn = 0;
		}
	}

	// ============================== 执行 ==============================

	/**
	 * 由 {@code AreaLightFlushMixin} 在每次 {@code runLightUpdates} 返回后调用：
	 * 按预算消化待办区块。
	 *
	 * <p>{@code LevelLightEngine} 服务端也有，所以先确认这是客户端世界的那台引擎。
	 */
	public static void flush(LevelLightEngine engine) {
		synchronized (LOCK) {
			if (PENDING.isEmpty()) {
				return;
			}
		}

		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			synchronized (LOCK) {
				PENDING.clear();
				cursorChunk = NO_CHUNK;
			}
			return;
		}

		if (engine != level.getLightEngine()) {
			return;
		}

		List<Region> targets = targets();

		synchronized (LOCK) {
			int budget = BUDGET_PER_FLUSH;

			while (budget > 0 && !PENDING.isEmpty()) {
				if (cursorChunk == NO_CHUNK) {
					cursorChunk = PENDING.firstLong();
					cursorColumn = 0;
				}

				LevelChunk chunk = level.getChunkSource().getChunk(ChunkPos.getX(cursorChunk), ChunkPos.getZ(cursorChunk), false);
				if (chunk == null || targets.isEmpty()) {
					finishChunk();
					continue;
				}

				budget = scanChunk(level, chunk, targets, budget);
				if (cursorColumn >= 256) {
					finishChunk();
				}
			}
		}
	}

	private static void finishChunk() {
		PENDING.remove(cursorChunk);
		cursorChunk = NO_CHUNK;
		cursorColumn = 0;
	}

	/**
	 * 对一个区块里与选区相交的列发出 {@code checkBlock}，直到预算用完或列扫完。
	 *
	 * <p>每一列取"所有覆盖它的选区"的并集高度：上沿是选区顶外加 1 格，
	 * 下沿是选区底外加 1 格，并且一直延伸到该列的天光源头（天光被改动后，下面的亮度也要跟着重估）。
	 *
	 * @return 剩余预算
	 */
	private static int scanChunk(ClientLevel level, LevelChunk chunk, List<Region> targets, int budget) {
		int baseX = chunk.getPos().getMinBlockX();
		int baseZ = chunk.getPos().getMinBlockZ();

		List<Region> local = new ArrayList<>();
		for (Region region : targets) {
			if (region.max().getX() + MARGIN >= baseX && region.min().getX() - MARGIN <= baseX + 15
				&& region.max().getZ() + MARGIN >= baseZ && region.min().getZ() - MARGIN <= baseZ + 15) {
				local.add(region);
			}
		}

		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		while (cursorColumn < 256 && budget > 0) {
			int x = baseX + (cursorColumn & 15);
			int z = baseZ + (cursorColumn >> 4);
			cursorColumn++;

			int top = Integer.MIN_VALUE;
			int bottom = Integer.MAX_VALUE;

			for (Region region : local) {
				if (x < region.min().getX() - MARGIN || x > region.max().getX() + MARGIN
					|| z < region.min().getZ() - MARGIN || z > region.max().getZ() + MARGIN) {
					continue;
				}

				top = Math.max(top, region.max().getY() + 1);
				bottom = Math.min(bottom, region.min().getY() - 1);
			}

			if (top == Integer.MIN_VALUE) {
				continue;
			}

			int source = chunk.getSkyLightSources().getLowestSourceY(x & 15, z & 15);
			bottom = Math.max(level.getMinY(), Math.min(bottom, source));
			top = Math.min(level.getMaxY(), top);

			for (int y = top; y >= bottom; y--) {
				level.getLightEngine().checkBlock(pos.set(x, y, z));
				budget--;
			}
		}

		return budget;
	}

	private static List<Region> targets() {
		List<Region> regions = SelectiveRenderingManager.regions();

		synchronized (LOCK) {
			List<Region> targets = new ArrayList<>(regions.size() + RETIRED_REGIONS.size());
			targets.addAll(regions);

			for (Region region : RETIRED_REGIONS) {
				if (!targets.contains(region)) {
					targets.add(region);
				}
			}

			return targets;
		}
	}
}
