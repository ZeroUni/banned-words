package zerouni.bannedwords.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import zerouni.bannedwords.BannedWords;

@Mixin(MinecraftServer.class)
public class ServerTickMixin {
	@Inject(at = @At("TAIL"), method = "tick")
	private void onServerTick(CallbackInfo ci) {
		// Tick our custom scheduler on every server tick
		BannedWords.tickScheduler();
	}
}