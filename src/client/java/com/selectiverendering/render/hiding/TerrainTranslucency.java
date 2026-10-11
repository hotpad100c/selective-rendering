package com.selectiverendering.render.hiding;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public final class TerrainTranslucency {
	public record Quad(float[] positions, float[] uvs, int[] colors, int[] lights, float normalX, float normalY, float normalZ) {
	}

	private static final Map<Long, List<Quad>> SECTIONS = new ConcurrentHashMap<>();
	// 区段里用到的SPRITES，Sodium只让被渲染区段里用到的动画贴图播放，自己维护时要自己标记
	private static final Map<Long, Set<TextureAtlasSprite>> SPRITES = new ConcurrentHashMap<>();
	private static final ThreadLocal<Builder> BUILDER = new ThreadLocal<>();

	private static final class Builder {
		final long section;
		final boolean capturing;
		final List<Quad> quads = new ArrayList<>();
		final Set<TextureAtlasSprite> sprites = new HashSet<>();

		Builder(long section, boolean capturing) {
			this.section = section;
			this.capturing = capturing;
		}
	}

	public static void begin(long section) {
		BUILDER.set(new Builder(section, ShaderPackState.active()));
	}

	public static void finish(boolean completed) {
		Builder builder = BUILDER.get();
		BUILDER.remove();

		if (builder == null || !completed) {
			return;
		}

		if (builder.quads.isEmpty()) {
			SECTIONS.remove(builder.section);
			SPRITES.remove(builder.section);
		}
		else {
			SPRITES.put(builder.section, builder.sprites);
			SECTIONS.put(builder.section, builder.quads);
		}
	}

	public static boolean capturing() {
		Builder builder = BUILDER.get();
		return builder != null && builder.capturing;
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
	}

}
