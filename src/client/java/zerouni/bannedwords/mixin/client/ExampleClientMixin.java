package zerouni.bannedwords.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.MinecraftClient;

@Mixin(MinecraftClient.class)
public class ExampleClientMixin {
	// @Inject(at = @At("HEAD"), method = "run")
	// private void init(CallbackInfo info) {
	// 	// This code is injected into the start of Minecraft.run()V
	// }
}