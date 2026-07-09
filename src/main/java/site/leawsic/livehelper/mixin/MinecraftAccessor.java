package site.leawsic.livehelper.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {

    @Accessor("mainRenderTarget")
    void livehelper$setMainRenderTarget(RenderTarget target);

    @Invoker("runTick")
    void livehelper$runTick(boolean tick);
}
