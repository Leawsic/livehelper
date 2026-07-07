package site.leawsic.livehelper.model;

/**
 * ClipSlot —— 在 Manager 中引用一个 Clip，指定其起始偏移。
 */
public record ClipSlot(
    int clipId,                  // 引用 Clip.id
    long startOffset,            // 相对 Manager 起始时间的偏移（毫秒）
    long transitionDuration,     // 进入该 Clip 时的转场时长（毫秒）
    String transitionEasing      // 转场曲线：linear/easeIn/easeOut/easeInOut
) {}
