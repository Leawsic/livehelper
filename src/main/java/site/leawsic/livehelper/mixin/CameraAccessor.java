package site.leawsic.livehelper.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Camera.class)
public interface CameraAccessor {
    @Invoker("setPosition")
    void livehelper$setPosition(double x, double y, double z);

    @Invoker("setPosition")
    void livehelper$setPosition(Vec3 position);

    @Invoker("setRotation")
    void livehelper$setRotation(float yaw, float pitch);

    @Accessor("initialized")
    void livehelper$setInitialized(boolean initialized);
}
