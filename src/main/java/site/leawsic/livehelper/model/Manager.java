package site.leawsic.livehelper.model;

import java.util.List;

/**
 * Manager（编排）—— 一个播放列表，定义机位的输出参数和片段顺序。
 */
public record Manager(
    int id,
    String name,
    List<ClipSlot> clips,
    int width,
    int height,
    int fps,
    int renderDistance
) {}
