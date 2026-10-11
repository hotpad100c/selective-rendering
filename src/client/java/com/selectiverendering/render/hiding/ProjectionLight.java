package com.selectiverendering.render.hiding;

import com.selectiverendering.region.Area;
import com.selectiverendering.util.Log;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** 投影自己的光照，这里把每个范围当作悬在空中的一块*/
public final class ProjectionLight {
	/** 范围加上一圈空气外壳的光照，下标相对范围最小角（-1 .. 尺寸）。 */
	public static final class Field {
		public final int version;
		private final int sizeX;
		private final int sizeY;
		private final int sizeZ;
		private final byte[] sky;
		private final byte[] block;

		private Field(int version, int sizeX, int sizeY, int sizeZ, byte[] sky, byte[] block) {
			this.version = version;
			this.sizeX = sizeX;
			this.sizeY = sizeY;
			this.sizeZ = sizeZ;
			this.sky = sky;
			this.block = block;
		}

		private int index(int x, int y, int z) {
			if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
				return -1;
			}

			return x + sizeX * (y + sizeY * z);
		}

		/** 范围内（含外壳）一格的光照，原版光照坐标的打包格式。 */
		public int light(int x, int y, int z) {
			int index = index(x + 1, y + 1, z + 1);
			return index < 0 ? pack(0, 15) : pack(block[index], sky[index]);
		}
	}

	private record Job(int version, Future<Field> future) {
	}

	private static final ExecutorService POOL = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "selective-rendering-projection-light");
		thread.setDaemon(true);
		return thread;
	});

	private static final Map<String, AtomicInteger> VERSIONS = new ConcurrentHashMap<>();
	// 只在渲染线程访问。
	private static final Map<String, Field> FIELDS = new HashMap<>();
	private static final Map<String, Job> JOBS = new HashMap<>();
	private static final Direction[] DIRECTIONS = Direction.values();

	private ProjectionLight() {
	}

	public static int pack(int block, int sky) {
		return (block << 4) | (sky << 20);
	}

	/** 范围里有区段重新编译过（方块可能变了）。失效 */
	public static void invalidate(String areaKey) {
		VERSIONS.computeIfAbsent(areaKey, ignored -> new AtomicInteger()).incrementAndGet();
	}

	private static int version(String areaKey) {
		AtomicInteger version = VERSIONS.get(areaKey);
		return version == null ? 0 : version.get();
	}

	/** 渲染线程：返回这个范围当前可用的光照场，新的还没算完时返回旧的（可能为 null）；读方块和计算都在后台线程。 */
	public static Field field(ClientLevel level, ProjectionAreas.Active active) {
		String key = active.key();
		Field known = FIELDS.get(key);
		Job job = JOBS.get(key);

		if (job != null && job.future().isDone()) {
			JOBS.remove(key);

			try {
				known = job.future().get();
				FIELDS.put(key, known);
			}
			catch (Exception exception) {
				Log.error("[projection] light failed", exception);
			}

			job = null;
		}

		int wanted = version(key);
		if (job == null && (known == null || known.version != wanted)) {
			Area area = active.area();
			JOBS.put(key, new Job(wanted, POOL.submit(() -> compute(level, area, wanted))));
		}

		return known;
	}

	public static void clear() {
		JOBS.values().forEach(job -> job.future().cancel(true));
		JOBS.clear();
		FIELDS.clear();
	}

	private static Field compute(ClientLevel level, Area area, int version) {
		int sizeX = area.max().getX() - area.min().getX() + 3;
		int sizeY = area.max().getY() - area.min().getY() + 3;
		int sizeZ = area.max().getZ() - area.min().getZ() + 3;
		int count = sizeX * sizeY * sizeZ;

		byte[] opacity = new byte[count];
		byte[] emission = new byte[count];
		boolean[] skyDown = new boolean[count];
		boolean[] shell = new boolean[count];

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int z = 0; z < sizeZ; z++) {
			for (int y = 0; y < sizeY; y++) {
				for (int x = 0; x < sizeX; x++) {
					int index = x + sizeX * (y + sizeY * z);

					if (x == 0 || y == 0 || z == 0 || x == sizeX - 1 || y == sizeY - 1 || z == sizeZ - 1) {
						shell[index] = true;
						skyDown[index] = true;
						continue;
					}

					BlockState state = level.getBlockState(cursor.set(area.min().getX() + x - 1, area.min().getY() + y - 1, area.min().getZ() + z - 1));
					opacity[index] = (byte) state.getLightDampening();
					emission[index] = (byte) state.getLightEmission();
					skyDown[index] = state.propagatesSkylightDown();
				}
			}
		}

		return compute(version, sizeX, sizeY, sizeZ, opacity, emission, skyDown, shell);
	}

	private static Field compute(int version, int sizeX, int sizeY, int sizeZ, byte[] opacity, byte[] emission, boolean[] skyDown, boolean[] shell) {
		int count = sizeX * sizeY * sizeZ;
		byte[] sky = new byte[count];
		byte[] block = new byte[count];
		IntArrayFIFOQueue skyQueue = new IntArrayFIFOQueue();
		IntArrayFIFOQueue blockQueue = new IntArrayFIFOQueue();

		for (int index = 0; index < count; index++) {
			if (shell[index]) {
				sky[index] = 15;
				skyQueue.enqueue(index);
			}
			else if (emission[index] > 0) {
				block[index] = emission[index];
				blockQueue.enqueue(index);
			}
		}

		spread(sky, skyQueue, opacity, skyDown, true, sizeX, sizeY, sizeZ);
		spread(block, blockQueue, opacity, skyDown, false, sizeX, sizeY, sizeZ);
		return new Field(version, sizeX, sizeY, sizeZ, sky, block);
	}

	private static void spread(byte[] light, IntArrayFIFOQueue queue, byte[] opacity, boolean[] skyDown, boolean skyLight, int sizeX, int sizeY, int sizeZ) {
		while (!queue.isEmpty()) {
			int index = queue.dequeueInt();
			int level = light[index];
			int x = index % sizeX;
			int y = index / sizeX % sizeY;
			int z = index / (sizeX * sizeY);

			for (Direction direction : DIRECTIONS) {
				int nx = x + direction.getStepX();
				int ny = y + direction.getStepY();
				int nz = z + direction.getStepZ();

				if (nx < 0 || ny < 0 || nz < 0 || nx >= sizeX || ny >= sizeY || nz >= sizeZ) {
					continue;
				}

				int next = nx + sizeX * (ny + sizeY * nz);
				int value = skyLight && level == 15 && direction == Direction.DOWN && skyDown[next]
					? 15
					: level - Math.max(1, opacity[next]);

				if (value > light[next]) {
					light[next] = (byte) value;
					queue.enqueue(next);
				}
			}
		}
	}

	/** 按投影自己的光照重算一个区段里投影四边形的光照：取面朝向那一格和方块自己那一格里更亮的。 */
	public static List<TerrainTranslucency.Quad> relight(List<TerrainTranslucency.Quad> quads, int sectionMinX, int sectionMinY, int sectionMinZ, Area area, Field field) {
		float shiftX = sectionMinX - area.min().getX();
		float shiftY = sectionMinY - area.min().getY();
		float shiftZ = sectionMinZ - area.min().getZ();

		List<TerrainTranslucency.Quad> result = new ArrayList<>(quads.size());

		for (TerrainTranslucency.Quad quad : quads) {
			float[] positions = quad.positions();
			float centerX = (positions[0] + positions[3] + positions[6] + positions[9]) * 0.25F + shiftX;
			float centerY = (positions[1] + positions[4] + positions[7] + positions[10]) * 0.25F + shiftY;
			float centerZ = (positions[2] + positions[5] + positions[8] + positions[11]) * 0.25F + shiftZ;

			int front = field.light(
				(int) Math.floor(centerX + quad.normalX() * 0.5F),
				(int) Math.floor(centerY + quad.normalY() * 0.5F),
				(int) Math.floor(centerZ + quad.normalZ() * 0.5F)
			);
			int own = field.light(
				(int) Math.floor(centerX - quad.normalX() * 0.01F),
				(int) Math.floor(centerY - quad.normalY() * 0.01F),
				(int) Math.floor(centerZ - quad.normalZ() * 0.01F)
			);

			int block = Math.max((front >> 4) & 15, (own >> 4) & 15);
			int sky = Math.max((front >> 20) & 15, (own >> 20) & 15);
			int packed = pack(block, sky);

			result.add(new TerrainTranslucency.Quad(
				positions, quad.uvs(), quad.colors(), new int[]{packed, packed, packed, packed},
				quad.normalX(), quad.normalY(), quad.normalZ(), quad.alpha(), quad.global(), quad.translucent()
			));
		}

		return result;
	}
}
