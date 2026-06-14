package site.leawsic.livehelper.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import site.leawsic.livehelper.render.StreamManager;
import site.leawsic.livehelper.scheduler.MainScheduler;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Redirect(
        method = "run",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick(Z)V")
    )
    private void redirectRunTick(Minecraft instance, boolean tick) {
        if (StreamManager.INSTANCE.hasActive()) {
            MainScheduler.tick(tick);
        } else {
            ((MinecraftAccessor) instance).livehelper$runTick(tick);
        }
    }
}
