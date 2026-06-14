package site.leawsic.livehelper.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import site.leawsic.livehelper.render.StreamManager;
import site.leawsic.livehelper.scheduler.MainScheduler;

import java.util.concurrent.TimeUnit;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "run", at = @At("HEAD"))
    private void livehelper$submitMainTickTask(CallbackInfo ci) {
        MainScheduler.submitTask(System.nanoTime(), new MainScheduler.ExecutableTask() {
            @Override
            public void run(boolean isOutOfMemoryRecovery, long startNs) {
                Minecraft minecraft = Minecraft.getInstance();
                ((MinecraftAccessor) minecraft).livehelper$runTick(!isOutOfMemoryRecovery);

                int targetFps = minecraft.options.framerateLimit().get();
                if (targetFps <= 0) targetFps = 60;
                targetFps = Math.min(targetFps, 260);
                long frameTime = TimeUnit.SECONDS.toNanos(1) / targetFps;
                MainScheduler.submitTask(startNs + frameTime, this);
            }
        });
    }

    @Redirect(
        method = "run",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick(Z)V")
    )
    private void livehelper$redirectRunTick(Minecraft instance, boolean tick) {
        if (StreamManager.INSTANCE.hasActive()) {
            MainScheduler.tick(!tick);
        } else {
            ((MinecraftAccessor) instance).livehelper$runTick(tick);
        }
    }
}
