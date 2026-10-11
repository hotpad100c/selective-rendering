package com.selectiverendering.render.hiding;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public final class TerrainTranslucency {
	public record Quad(float[] positions, float[] uvs, int[] colors, int[] lights, float normalX, float normalY, float normalZ) {
	}

	private static final Map<Long, List<Quad>> SECTIONS = new ConcurrentHashMap<>();
	private static final ThreadLocal<Builder> BUILDER = new ThreadLocal<>();
	private static ClientLevel lastLevel;

	private TerrainTranslucency() {
	}

	private static final class Builder {
		final long section;
		final boolean capturing;
		final List<Quad> quads = new ArrayList<>();

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
		}
		else {
			SECTIONS.put(builder.section, builder.quads);
		}
	}

	public static boolean capturing() {
		Builder builder = BUILDER.get();
		return builder != null && builder.capturing;
	}

	public static void add(Quad quad) {
		Builder builder = BUILDER.get();
		if (builder != null) {
			builder.quads.add(quad);
		}
	}

	public static void clear() {
		SECTIONS.clear();
	}

	/** 把已截流的四边形提交给渲染；pose 为相机相对坐标。 */
	public static void emit(PoseStack poseStack, SubmitNodeCollector collector, ClientLevel level, Vec3 camera, Frustum frustum) {
		if (level != lastLevel) {
			SECTIONS.clear();
			lastLevel = level;
		}

		if (SECTIONS.isEmpty()) {
			return;
		}

		RenderType type = RenderTypes.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);

		for (Map.Entry<Long, List<Quad>> entry : SECTIONS.entrySet()) {
			SectionPos section = SectionPos.of(entry.getKey());
			if (!level.hasChunk(section.x(), section.z())) {
				SECTIONS.remove(entry.getKey());
				continue;
			}

			double minX = section.minBlockX();
			double minY = section.minBlockY();
			double minZ = section.minBlockZ();

			if (frustum != null && !frustum.isVisible(new AABB(minX, minY, minZ, minX + 16, minY + 16, minZ + 16))) {
				continue;
			}

			List<Quad> quads = entry.getValue();

			poseStack.pushPose();
			poseStack.translate(minX - camera.x, minY - camera.y, minZ - camera.z);

			collector.submitCustomGeometry(poseStack, type, (pose, consumer) -> {
				for (Quad quad : quads) {
					for (int i = 0; i < 4; i++) {
						consumer.addVertex(pose, quad.positions()[i * 3], quad.positions()[i * 3 + 1], quad.positions()[i * 3 + 2])
							.setColor(quad.colors()[i])
							.setUv(quad.uvs()[i * 2], quad.uvs()[i * 2 + 1])
							.setOverlay(OverlayTexture.NO_OVERLAY)
							.setLight(quad.lights()[i])
							.setNormal(pose, quad.normalX(), quad.normalY(), quad.normalZ());
					}
				}
			});

			poseStack.popPose();
		}
	}
}
