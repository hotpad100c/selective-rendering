package com.selectiverendering.render.hiding;

import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public final class TerrainTranslucency {
	// alpha 是截流时的透明度，global 表示它等于当时的全局透明度（之后跟随配置变化，不用重建）。
	public record Quad(float[] positions, float[] uvs, int[] colors, int[] lights, float normalX, float normalY, float normalZ, int alpha, boolean global) {
	}

	/** 投影用的四边形按（范围, 区段）存放：坐标仍然是区段内坐标，投影后的位置就是区段原点加位移。 */
	public record ProjectionKey(String area, long section) {
	}

	private static final int FULL_BRIGHT = 0xF000F0;

	private static final Map<ProjectionKey, List<Quad>> PROJECTED = new ConcurrentHashMap<>();
	private static final Map<ProjectionKey, Set<TextureAtlasSprite>> PROJECTED_SPRITES = new ConcurrentHashMap<>();

	private static final Map<Long, List<Quad>> SECTIONS = new ConcurrentHashMap<>();
	// 区段里用到的SPRITES，Sodium只让被渲染区段里用到的动画贴图播放，自己维护时要自己标记
	private static final Map<Long, Set<TextureAtlasSprite>> SPRITES = new ConcurrentHashMap<>();
	private static final ThreadLocal<Builder> BUILDER = new ThreadLocal<>();

	private static final class Builder {
		final long section;
		final boolean capturing;
		final List<Quad> quads = new ArrayList<>();
		final Set<TextureAtlasSprite> sprites = new HashSet<>();
		final List<ProjectionAreas.Active> areas = ProjectionAreas.current();
		final Map<String, List<Quad>> projections = new java.util.HashMap<>();

		Builder(long section, boolean capturing) {
			this.section = section;
			this.capturing = capturing;
		}
	}

	public static void begin(long section) {
		BUILDER.set(new Builder(section, true));
	}

	public static void finish(boolean completed) {
		Builder builder = BUILDER.get();
		BUILDER.remove();

		if (builder == null || !completed) {
			return;
		}

		for (ProjectionAreas.Active area : builder.areas) {
			ProjectionKey key = new ProjectionKey(area.key(), builder.section);
			List<Quad> projected = builder.projections.get(area.key());

			if (projected == null || projected.isEmpty()) {
				PROJECTED.remove(key);
				PROJECTED_SPRITES.remove(key);
			}
			else {
				PROJECTED_SPRITES.put(key, builder.sprites);
				RetainedTerrain.prebuild(key, projected);
				PROJECTED.put(key, projected);
			}
		}

		if (builder.quads.isEmpty()) {
			SECTIONS.remove(builder.section);
			SPRITES.remove(builder.section);
		}
		else {
			SPRITES.put(builder.section, builder.sprites);
			RetainedTerrain.prebuild(builder.section, builder.quads);
			SECTIONS.put(builder.section, builder.quads);
		}
	}

	public static boolean capturing() {
		Builder builder = BUILDER.get();
		return builder != null && builder.capturing;
	}

	/** 当前编译任务里，这个方块是否落在某个投影的源范围里。 */
	public static boolean projecting(BlockPos pos) {
		Builder builder = BUILDER.get();
		if (builder == null) {
			return false;
		}

		for (ProjectionAreas.Active area : builder.areas) {
			if (area.contains(pos)) {
				return true;
			}
		}

		return false;
	}

	/** 把方块的一个四边形（区段内坐标）复制给包含它的每个投影范围。 */
	public static void addProjected(BlockPos pos, Quad quad) {
		Builder builder = BUILDER.get();
		if (builder == null) {
			return;
		}

		// 投影出来的内容不需要光照：目的地的光照和它没有关系，源位置可能很暗，统一全亮。
		Quad lit = new Quad(quad.positions(), quad.uvs(), quad.colors(), new int[]{FULL_BRIGHT, FULL_BRIGHT, FULL_BRIGHT, FULL_BRIGHT},
			quad.normalX(), quad.normalY(), quad.normalZ(), quad.alpha(), quad.global());

		for (ProjectionAreas.Active area : builder.areas) {
			if (area.contains(pos)) {
				builder.projections.computeIfAbsent(area.key(), ignored -> new ArrayList<>()).add(lit);
			}
		}
	}

	static Map<ProjectionKey, List<Quad>> projected() {
		return PROJECTED;
	}

	static Set<TextureAtlasSprite> projectedSprites(ProjectionKey key) {
		Set<TextureAtlasSprite> sprites = PROJECTED_SPRITES.get(key);
		return sprites == null ? Set.of() : sprites;
	}

	static void removeProjected(ProjectionKey key) {
		PROJECTED.remove(key);
		PROJECTED_SPRITES.remove(key);
	}

	public static void addSprite(TextureAtlasSprite sprite) {
		Builder builder = BUILDER.get();
		if (builder != null && sprite != null) {
			builder.sprites.add(sprite);
		}
	}

	static Set<TextureAtlasSprite> sprites(long section) {
		Set<TextureAtlasSprite> sprites = SPRITES.get(section);
		return sprites == null ? Set.of() : sprites;
	}

	public static void add(Quad quad) {
		Builder builder = BUILDER.get();
		if (builder != null) {
			builder.quads.add(quad);
		}
	}

	static Map<Long, List<Quad>> sections() {
		return SECTIONS;
	}

	static void remove(long section) {
		SECTIONS.remove(section);
		SPRITES.remove(section);
	}

	public static void clear() {
		SECTIONS.clear();
		SPRITES.clear();
		PROJECTED.clear();
		PROJECTED_SPRITES.clear();
	}

}
