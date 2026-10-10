package com.selectiverendering.mixin.lighting;

import com.selectiverendering.lighting.LightSuppression;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端的光照数据到达客户端时，标记该区块需要重算。
 *
 * <p>服务端是按"方块真实存在"算的光，这份数据会覆盖客户端为隐藏方块做的伪装。
 * 这里只负责<b>标记</b>，真正的重算在 {@code AreaLightFlushMixin}（数据落地之后）。
 */
@Mixin(ClientPacketListener.class)
public class AreaLightPacketMixin {
	@Inject(method = "applyLightData", at = @At("RETURN"))
	private void selectiveRendering$markLightData(int x, int z, ClientboundLightUpdatePacketData data, boolean update, CallbackInfo ci) {
		LightSuppression.markChunk(x, z);
	}

	/**
	 * 区块随数据包到达时，光照是在 {@code applyLightData} 之后才被启用的：
	 * 在此之前排队的检查可能已被丢弃，所以启用之后再标记一次。
	 */
	@Inject(method = "enableChunkLight", at = @At("RETURN"))
	private void selectiveRendering$markLightEnabled(LevelChunk chunk, int x, int z, CallbackInfo ci) {
		LightSuppression.markChunk(x, z);
	}
}
