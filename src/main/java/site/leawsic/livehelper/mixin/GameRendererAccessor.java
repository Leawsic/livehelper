package site.leawsic.livehelper.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("mainCamera")
    Camera livehelper$getMainCamera();

    @Accessor("mainCamera")
    void livehelper$setMainCamera(Camera camera);
}
