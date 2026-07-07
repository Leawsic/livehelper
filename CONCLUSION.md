## 当前渲染方案与经验总结

当前可用方案不是完全独立的离屏虚拟摄像机，而是“主摄像机接管式推流”：Stream 活跃时，`StreamInstance` 计算当前 `FrameCommand`，写入 `ActiveRenderContext` 的持久上下文；正常 Minecraft 主渲染帧通过 mixin 在 `GameRenderer.renderLevel()` 的 `Camera.setup(...)` 处套用虚拟摄像机位置、旋转和 FOV；Spout 发送主窗口 `mainRenderTarget.frameBufferId`。

这个方案可行的原因：

- 1.20.1 的地形渲染强依赖同一个 `LevelRenderer`、`ViewArea`、chunk 可见区、光照纹理和主窗口 FBO 状态。
- 主渲染管线本身会正确准备 terrain、cloud、entity、hand、HUD、shader、light texture 和 chunk render list。
- Spout 当前 DLL 只导出 `spSendFrameBufferObject`，发送主窗口 FBO 最稳定。

之前失败的方案和原因：

- 独立 `MainTarget` + 第二次 `GameRenderer.render(...)`：虚拟 camera 确实命中，但共享 `LevelRenderer` 状态被第二次渲染污染，MC 主画面闪烁；Spout 发送还容易抓到主窗口玩家画面。
- 直接 `LevelRenderer.renderLevel(...)` 到离屏 FBO：天空/云使用传入 camera 能正确渲染，但 1.20.1 的 `LevelRenderer.setupRender(...)` 内部会读取 `minecraft.player.getX/Y/Z` 来重定位 `ViewArea`，地形 chunk 准备与虚拟 camera 脱节，OBS 出现“只有云、地形黑”。
- 发送 `RenderTarget.getColorTextureId()`：当前 native DLL 不支持 texture 发送，触发 `GL_INVALID_OPERATION`。参考项目新版的 `sendTexture` 路径依赖不同的 native binding，不能直接套用。

当前方案的取舍：

- 优点：OBS 和 MC 都使用完整 vanilla 渲染路径，地形/实体/HUD/手臂稳定。
- 限制：Stream 活跃期间 MC 本机视角会被虚拟摄像机接管；输出分辨率跟随主窗口 framebuffer，而不是独立 Manager 宽高。
- 后续若要恢复真正独立机位，需要新的 Spout texture 发送 binding，或者复制/隔离 `LevelRenderer`/`ViewArea`/chunk render state。
