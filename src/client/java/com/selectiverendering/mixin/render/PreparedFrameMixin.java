package com.selectiverendering.mixin.render;

//? if >=26.3 {
import com.mojang.renderpearl.api.commands.RenderPass;
import com.selectiverendering.render.hiding.RetainedTerrain;
import net.minecraft.client.renderer.SubmitNodeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 在半透明实体之前画常驻的半透明地形缓冲（光影下地形没法靠顶点 alpha 半透明）。
@Mixin(targets = "net.minecraft.client.renderer.feature.FeatureRenderDispatcher$PreparedFrame")
public class PreparedFrameMixin {
	@Shadow
	private SubmitNodeStorage submitNodeStorage;

	@Inject(method = "executeTranslucent", at = @At("HEAD"))
	private void selectiveRendering$drawTranslucentTerrain(RenderPass pass, CallbackInfo ci) {
		RetainedTerrain.draw(pass, this.submitNodeStorage);
	}
}
//?} else {
/*import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Minecraft.class)
public class PreparedFrameMixin {
}
*///?}
