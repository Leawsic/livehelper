package site.leawsic.livehelper.model;

import java.util.Map;

/**
 * 片段（Clip）—— 一个镜头片段。
 * 包含时长、运动模板名称和参数。
 */
public record Clip(
    int id,
    String name,
    long duration,               // 时长（毫秒）
    String template,             // 运动模板名称 (STATIC, ORBIT, DOLLY, ...)
    Map<String, Double> params   // 模板参数
) {}
