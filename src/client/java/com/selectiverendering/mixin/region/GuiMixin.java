package com.selectiverendering.mixin.region;

import com.selectiverendering.region.WandHud;
import net.minecraft.client.DeltaTracker;
//? if <26.2 {
/*import net.minecraft.client.gui.Gui;
*///?}
import net.minecraft.client.gui.GuiGraphicsExtractor;
//? if >=26.2 {
import net.minecraft.client.gui.Hud;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HUD 覆盖层的注入点。
 *
 * <p>只负责把 {@code Hud.extractRenderState} 末尾交给 {@link WandHud}，
 * 全部绘制逻辑在共享的 {@link WandHud} 里。
 *
 * <p>26.2 起 HUD 从 {@code Gui} 拆成了 {@code Hud}（26.1.2 里还没有 {@code Hud} 这个类），
 * 但注入点方法名 {@code extractRenderState} 没变，所以只有 {@code @Mixin} 的目标类按版本切换。
 */
//? if >=26.2 {
@Mixin(Hud.class)
//?} else {
/*@Mixin(Gui.class)
*///?}
public class GuiMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void selectiveRendering$renderOverlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		WandHud.render(graphics);
	}
}
