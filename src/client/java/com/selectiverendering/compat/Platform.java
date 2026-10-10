package com.selectiverendering.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
//? if <26.2 {
/*import net.minecraft.client.renderer.LevelRenderer;
*///?}
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/**
 * <b>跨版本适配层</b>：把所有"每个 MC 版本签名都不一样"的调用收拢到这里。
 *
 * <p>共享代码只依赖本类；本类内部用 Stonecutter 的 {@code //? if} 按 MC 版本取舍实现。
 *
 * <h2>这里究竟哪些东西变了</h2>
 * <ul>
 *   <li><b>鼠标按键编号</b>：26.1.2 / 26.2 是 0=左、1=右、2=中；26.3 改成了 1=左、2=中、3=右
 *       （{@code MouseButtonInfo} 重新编号）。</li>
 *   <li><b>区块重建入口</b>：26.2 起 {@code LevelRenderer.allChanged()} 被搬到
 *       {@code Minecraft.levelExtractor}（{@code LevelExtractor}）。</li>
 *   <li><b>GUI 访问</b>：26.2 起 {@code minecraft.screen} 变成 {@code minecraft.gui.screen()}。</li>
 *   <li><b>按键名</b>：{@code InputConstants.Type.KEYSYM} → {@code KEYBOARD}。</li>
 *   <li><b>{@code BakedQuad.MaterialInfo} 构造参数</b>：字段数量随版本增减。</li>
 *   <li><b>取主相机</b>：26.1.2 是 {@code GameRenderer#getMainCamera()}，26.2 起改名
 *       成 {@code mainCamera()}。</li>
 * </ul>
 *
 * <p>新增一个 MC 版本支持时，第一步就是把本类每个成员逐个核对一遍。
 */
public final class Platform {
	//? if >=26.3 {
	public static final int MOUSE_LEFT = 1;
	//?} else {
	/*public static final int MOUSE_LEFT = 0;
	*///?}
	public static final int MOUSE_MIDDLE = 2;
	//? if >=26.3 {
	public static final int MOUSE_RIGHT = 3;
	//?} else {
	/*public static final int MOUSE_RIGHT = 1;
	*///?}

	private Platform() {
	}

	/** 当前真正用于渲染的相机。出窍相机类 mod 改的就是它，所以魔杖射线要读它而不是玩家实体。 */
	public static Camera camera(Minecraft minecraft) {
		//? if >=26.2 {
		return minecraft.gameRenderer.mainCamera();
		//?} else {
		/*return minecraft.gameRenderer.getMainCamera();
		*///?}
	}

	/** 世界/渲染器是否已就绪。26.2+ 用 {@code levelExtractor}，旧版判断 {@code levelRenderer != null}。 */
	public static boolean ready() {
		return Minecraft.getInstance().levelRenderer != null;
	}

	public static void markSectionWithNeighbors(int x, int y, int z) {
		//? if >=26.2 {
		Minecraft.getInstance().levelExtractor.setSectionDirtyWithNeighbors(x, y, z);
		//?} else {
		/*LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
		if (renderer != null) {
			renderer.setSectionDirtyWithNeighbors(x, y, z);
		}
		*///?}
	}

	public static void rebuildAll() {
		//? if >=26.2 {
		Minecraft.getInstance().levelExtractor.allChanged();
		//?} else {
		/*LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
		if (renderer != null) {
			renderer.allChanged();
		}
		*///?}
	}

	public static Screen screen(Minecraft minecraft) {
		//? if >=26.2 {
		return minecraft.gui.screen();
		//?} else {
		/*return minecraft.screen;
		*///?}
	}

	public static void setScreen(Minecraft minecraft, Screen screen) {
		//? if >=26.2 {
		minecraft.gui.setScreen(screen);
		//?} else {
		/*minecraft.setScreen(screen);
		*///?}
	}

	public static boolean isKeyDown(Window window, int key) {
		//? if >=26.3 {
		return InputConstants.isKeyDown(key);
		//?} else {
		/*return InputConstants.isKeyDown(window, key);
		*///?}
	}

	public static String keyName(int key) {
		//? if >=26.3 {
		return InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName().getString();
		//?} else {
		/*return InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
		*///?}
	}

	public static BakedQuad.MaterialInfo translucentMaterial(BakedQuad.MaterialInfo material) {
		return new BakedQuad.MaterialInfo(
			material.sprite(),
			ChunkSectionLayer.TRANSLUCENT,
			material.itemRenderType(),
			//? if >=26.3 {
			material.itemGlintRenderType(),
			material.itemGlintSpecialRenderType(),
			//?}
			material.tintIndex(),
			//? if >=26.3 {
			material.shadeDirectionOverride(),
			//?} else {
			/*material.shade(),
			*///?}
			material.lightEmission()
		);
	}
}
