package site.leawsic.livehelper.model;

import java.util.List;

/**
 * Manager（编排）—— 一个播放列表，定义机位的输出参数和片段顺序。
 *
 * @param loop      是否循环播放时间线
 * @param loopMode  循环方式：repeat（从头重播）/ pingpong（往复折返）。
 *                 旧配置缺省为 null，按 repeat 处理。
 */
public record Manager(
    int id,
    String name,
    List<ClipSlot> clips,
    int width,
    int height,
    int fps,
    int renderDistance,
    boolean loop,
    String loopMode,
    boolean locked
) {
    public static final String LOOP_REPEAT = "repeat";
    public static final String LOOP_PINGPONG = "pingpong";

    public Manager(int id, String name, List<ClipSlot> clips, int width, int height, int fps, int renderDistance) {
        this(id, name, clips, width, height, fps, renderDistance, false, LOOP_REPEAT, false);
    }

    /** 归一化循环方式：缺省或非法值一律按 repeat 处理。 */
    public String effectiveLoopMode() {
        return LOOP_PINGPONG.equalsIgnoreCase(loopMode) ? LOOP_PINGPONG : LOOP_REPEAT;
    }
}
