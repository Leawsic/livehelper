package site.leawsic.livehelper.engine.templates;

import site.leawsic.livehelper.model.FrameCommand;

import java.util.Map;

/**
 * 需要预计算的模板：把「建表 / 解析」从逐帧热路径挪到 Clip 装载时。
 *
 * <p>移植自 ImmersiveCinematics 的做法——其 {@code KeyframeInterpolator.interpolatePosition}
 * 允许调用方传入独立的 {@code PathStrategy} 实例，正是为了避免用静态单例缓存逐 Clip 状态。
 *
 * <p>约定：{@link #prepare} 返回 null 表示预计算失败，PlaybackEngine 会退回逐帧
 * {@link MotionTemplate#evaluate} 路径，不影响可用性。
 */
public interface PreparedMotionTemplate extends MotionTemplate {

    Object prepare(Map<String, Object> params);

    FrameCommand evaluatePrepared(Map<String, Object> params, float progress, Object prepared);
}
