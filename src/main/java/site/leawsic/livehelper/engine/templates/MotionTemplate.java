package site.leawsic.livehelper.engine.templates;

import site.leawsic.livehelper.model.FrameCommand;

import java.util.Map;

public interface MotionTemplate {
    FrameCommand evaluate(Map<String, Object> params, float progress);
}
