package com.selectiverendering.render.hiding;

//? if >=26.3 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.caffeinemc.mods.sodium.api.texture.SpriteUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RetainedTerrain {
	private static final int REBUILD_QUAD_BUDGET = 4096;
	private static final int VERTEX_STRIDE = 36;
	private static final float DEPTH_PUSH = 1.0005F;

	private static final List<GpuBuffer> GRAVEYARD = new ArrayList<>();
	private static final Map<Long, BufferPice> BUFFERS = new HashMap<>();

	private static Frame current;
	private static SubmitNodeCollector currentStorage;//当前的目标Collector
	private static ClientLevel lastLevel;
	private static boolean lastShaders;

	private static final class BufferPice {
		final GpuBuffer buffer;
		final int indexCount;
		final List<TerrainTranslucency.Quad> source;
		final float minX, minY, minZ, maxX, maxY, maxZ;

		BufferPice(GpuBuffer buffer, int indexCount, List<TerrainTranslucency.Quad> source, float[] bounds) {
			this.buffer = buffer;
			this.indexCount = indexCount;
			this.source = source;
			this.minX = bounds[0];
			this.minY = bounds[1];
			this.minZ = bounds[2];
			this.maxX = bounds[3];
			this.maxY = bounds[4];
			this.maxZ = bounds[5];
		}
	}

	private record Placed(BufferPice bufferPice, float x, float y, float z) {
	}

	private record Frame(List<Placed> placed, Matrix4f view) {
	}

	// 必须在 实体提交 时建造缓冲，开着光影时只有此时构造的 BufferBuilder 才会按IRIS的扩展顶点格式写数据，有更好的办法吗...？
	public static void prepare(ClientLevel level, CameraRenderState camera, SubmitNodeCollector collector) {
		current = null;
		currentStorage = collector;

		boolean shaders = ShaderPackState.active();
		if (shaders != lastShaders) {
			releaseAll();
			TerrainTranslucency.clear();
			lastShaders = shaders;
		}

		if (!shaders) {
			return;
		}
		GRAVEYARD.forEach(GpuBuffer::close);
		GRAVEYARD.clear();

		if (level != lastLevel) {
			releaseAll();
			TerrainTranslucency.clear();
			lastLevel = level;
		}

		Map<Long, List<TerrainTranslucency.Quad>> sections = TerrainTranslucency.sections();
		BUFFERS.entrySet().removeIf(entry -> {
			if (sections.containsKey(entry.getKey())) {
				return false;
			}

			GRAVEYARD.add(entry.getValue().buffer);
			return true;
		});

		if (sections.isEmpty()) {
			return;
		}

		List<Placed> placedList = new ArrayList<>();
		Vec3 position = camera.pos;
		Frustum frustum = new Frustum(camera.viewRotationMatrix, camera.projectionMatrix);
		frustum.prepare(position.x, position.y, position.z);
		double range = (Minecraft.getInstance().options.renderDistance().get() + 1) * 16.0;
		double rangeSquared = range * range;
		int budget = REBUILD_QUAD_BUDGET;

		for (Map.Entry<Long, List<TerrainTranslucency.Quad>> entry : sections.entrySet()) {
			SectionPos section = SectionPos.of(entry.getKey());

			if (!level.hasChunk(section.x(), section.z())) {
				TerrainTranslucency.remove(entry.getKey());
				continue;
			}

			double minX = section.minBlockX();
			double minY = section.minBlockY();
			double minZ = section.minBlockZ();

			double dx = minX + 8.0 - position.x;
			double dy = minY + 8.0 - position.y;
			double dz = minZ + 8.0 - position.z;
			if (dx * dx + dy * dy + dz * dz > rangeSquared) {
				continue;
			}

			if (!frustum.isVisible(new AABB(minX, minY, minZ, minX + 16, minY + 16, minZ + 16))) {
				continue;
			}

			List<TerrainTranslucency.Quad> quads = entry.getValue();
			BufferPice bufferPice = BUFFERS.get(entry.getKey());

			if (bufferPice == null || bufferPice.source != quads) {
				if (budget <= 0) {
					if (bufferPice == null) {
						continue;
					}
				}
				else {
					BufferPice rebuilt = build(quads);
					if (bufferPice != null) {
						GRAVEYARD.add(bufferPice.buffer);
					}

					BUFFERS.put(entry.getKey(), rebuilt);
					bufferPice = rebuilt;
					budget -= quads.size();
				}
			}

			if (bufferPice.indexCount > 0 && frustum.isVisible(new AABB(minX + bufferPice.minX, minY + bufferPice.minY, minZ + bufferPice.minZ, minX + bufferPice.maxX, minY + bufferPice.maxY, minZ + bufferPice.maxZ))) {
				for (TextureAtlasSprite sprite : TerrainTranslucency.sprites(entry.getKey())) {
					SpriteUtil.INSTANCE.markSpriteActive(sprite);
				}

				placedList.add(new Placed(bufferPice, (float) (minX - position.x), (float) (minY - position.y), (float) (minZ - position.z)));
			}
		}

		current = new Frame(placedList, RenderSystem.getModelViewMatrixCopy());
	}

	public static void draw(RenderPass pass, Object storage) {
		Frame frame = current;
		if (frame == null || storage != currentStorage) {
			return;
		}

		current = null;

		//使用半透明实体管线提交内容
		RenderType type = RenderTypes.entityTranslucentCull(TextureAtlas.LOCATION_BLOCKS);
		PreparedRenderType base = type.prepare();
		Matrix4f texture = new Matrix4f();

		for (Placed placed : frame.placed()) {
			Matrix4f modelView = new Matrix4f(frame.view()).scale(DEPTH_PUSH).translate(placed.x(), placed.y(), placed.z());
			GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(modelView, texture);
			PreparedRenderType prepared = new PreparedRenderType(base.name(), base.pipeline(), base.oitPipelineSet(), transform, base.scissorState(), base.textures());

			RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
			indices.getBuffer(placed.bufferPice().indexCount);
			//你过关！
			prepared.drawFromBuffer(new StagedVertexBuffer.ExecuteInfo(placed.bufferPice().buffer, null, indices.type(), 0, 0, placed.bufferPice().indexCount, PrimitiveTopology.QUADS), pass);
		}
	}

	private static BufferPice build(List<TerrainTranslucency.Quad> quads) {
		try (ByteBufferBuilder bytes = new ByteBufferBuilder(Math.max(1, quads.size()) * 4 * VERTEX_STRIDE)) {
			BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);
			float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

			for (TerrainTranslucency.Quad quad : quads) {
				for (int i = 0; i < 12; i++) {
					bounds[i % 3] = Math.min(bounds[i % 3], quad.positions()[i]);
					bounds[3 + i % 3] = Math.max(bounds[3 + i % 3], quad.positions()[i]);
				}

				for (int i = 0; i < 4; i++) {
					builder.addVertex(quad.positions()[i * 3], quad.positions()[i * 3 + 1], quad.positions()[i * 3 + 2])
						.setColor(quad.colors()[i])
						.setUv(quad.uvs()[i * 2], quad.uvs()[i * 2 + 1])
						.setOverlay(OverlayTexture.NO_OVERLAY)
						.setLight(quad.lights()[i])
						.setNormal(quad.normalX(), quad.normalY(), quad.normalZ());
				}
			}

			try (MeshData mesh = builder.buildOrThrow()) {
				GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "selective_rendering translucent terrain", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, mesh.vertexBuffer());
				return new BufferPice(buffer, mesh.drawState().indexCount(), quads, bounds);
			}
		}
	}

	public static void releaseAll() {
		current = null;
		GRAVEYARD.forEach(GpuBuffer::close);
		GRAVEYARD.clear();

		for (BufferPice bufferPice : BUFFERS.values()) {
			bufferPice.buffer.close();
		}

		BUFFERS.clear();
	}
}
//?} else {
/*public final class RetainedTerrain {
	private RetainedTerrain() {
	}
}
*///?}
