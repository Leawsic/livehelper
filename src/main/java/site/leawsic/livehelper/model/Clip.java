package site.leawsic.livehelper.model;

import java.util.Map;

public record Clip(
    int id,
    String name,
    long duration,
    String template,
    Map<String, Object> params
) {}
