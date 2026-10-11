package com.selectiverendering.render.hiding;

//? if >=26.3 {
import com.mojang.blaze3d.vertex.QuadInstance;
import com.selectiverendering.SelectiveRenderingManager;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Vector3fc;

public final class VanillaCapture {
	private static final float CAPTURE_INSET = 0.005F;
	private static final int STRIDE = 8;
	private static final ThreadLocal<float[]> FLUID_VERTICES = ThreadLocal.withInitial(() -> new float[4 * STRIDE]);
	private static final ThreadLocal<int[]> FLUID_COUNT = ThreadLocal.withInitial(() -> new int[1]);

	private VanillaCapture() {
	}

	public static void block(float x, float y, float z, BakedQuad quad, QuadInstance instance, int alpha) {
		float[] positions = new float[12];
		float[] uvs = new float[8];
		int[] colors = new int[4];
		int[] lights = new int[4];

		Direction direction = quad.direction();
		float nx = direction.getStepX();
		float ny = direction.getStepY();
		float nz = direction.getStepZ();
		int emission = quad.materialInfo().lightEmission();

		for (int i = 0; i < 4; i++) {
			Vector3fc position = quad.position(i);
			positions[i * 3] = x + position.x() - nx * CAPTURE_INSET;
			positions[i * 3 + 1] = y + position.y() - ny * CAPTURE_INSET;
			positions[i * 3 + 2] = z + position.z() - nz * CAPTURE_INSET;

			long uv = quad.packedUV(i);
			uvs[i * 2] = UVPair.unpackU(uv);
			uvs[i * 2 + 1] = UVPair.unpackV(uv);

			colors[i] = instance.getColor(i) | 0xFF000000;
			lights[i] = instance.getLightCoordsWithEmission(i, emission);
		}

		TerrainTranslucency.add(new TerrainTranslucency.Quad(positions, uvs, colors, lights, nx, ny, nz, alpha, alpha == SelectiveRenderingManager.config().alpha()));
	}

	public static void fluidVertex(float x, float y, float z, int color, float u, float v, int light, int alpha) {
		float[] data = FLUID_VERTICES.get();
		int[] count = FLUID_COUNT.get();
		int base = count[0] * STRIDE;

		data[base] = x;
		data[base + 1] = y;
		data[base + 2] = z;
		data[base + 3] = u;
		data[base + 4] = v;
		data[base + 5] = Float.intBitsToFloat(color | 0xFF000000);
		data[base + 6] = Float.intBitsToFloat(light);

		count[0]++;
		if (count[0] < 4) {
			return;
		}

		count[0] = 0;

		float ux = data[STRIDE] - data[0];
		float uy = data[STRIDE + 1] - data[1];
		float uz = data[STRIDE + 2] - data[2];
		float vx = data[2 * STRIDE] - data[0];
		float vy = data[2 * STRIDE + 1] - data[1];
		float vz = data[2 * STRIDE + 2] - data[2];
		float nx = uy * vz - uz * vy;
		float ny = uz * vx - ux * vz;
		float nz = ux * vy - uy * vx;
		float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (length > 0.0F) {
			nx /= length;
			ny /= length;
			nz /= length;
		}

		float[] positions = new float[12];
		float[] uvs = new float[8];
		int[] colors = new int[4];
		int[] lights = new int[4];

		for (int i = 0; i < 4; i++) {
			positions[i * 3] = data[i * STRIDE];
			positions[i * 3 + 1] = data[i * STRIDE + 1];
			positions[i * 3 + 2] = data[i * STRIDE + 2];
			uvs[i * 2] = data[i * STRIDE + 3];
			uvs[i * 2 + 1] = data[i * STRIDE + 4];
			colors[i] = Float.floatToRawIntBits(data[i * STRIDE + 5]);
			lights[i] = Float.floatToRawIntBits(data[i * STRIDE + 6]);
		}

		TerrainTranslucency.add(new TerrainTranslucency.Quad(positions, uvs, colors, lights, nx, ny, nz, alpha, alpha == SelectiveRenderingManager.config().alpha()));
	}
}
//?} else {
/*import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.resources.model.geometry.BakedQuad;

public final class VanillaCapture {
	private VanillaCapture() {
	}

	public static void block(float x, float y, float z, BakedQuad quad, QuadInstance instance, int alpha) {
	}

	public static void fluidVertex(float x, float y, float z, int color, float u, float v, int light, int alpha) {
	}
}
*///?}
