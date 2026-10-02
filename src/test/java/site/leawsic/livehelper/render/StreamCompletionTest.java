package site.leawsic.livehelper.render;

import org.junit.jupiter.api.Test;
import site.leawsic.livehelper.render.StreamManager.CompletionAction;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 时间线走完后的收尾策略。
 *
 * <p>这里曾经有个实机才能复现的 bug：没有常驻机位时，切机位会退化成常驻启动，
 * 而常驻机位没有结束路径，于是该机位永远占着输出——画面冻在最后一帧、玩家视角已交还、
 * 状态仍显示 ON AIR，而可重复触发的规则再切回来只会命中「已经是常驻」分支而空转。
 *
 * <p>根因是把「没有可返回的目标」当成了「永远不会结束」。正确语义是：
 * 开启循环就永远播，否则一定结束；有常驻可回就切回去，没有就释放输出。
 */
class StreamCompletionTest {

    private static final boolean FINISHED = true;
    private static final boolean NOT_FINISHED = false;
    private static final boolean NO_LOOP = false;
    private static final boolean LOOP = true;
    private static final boolean UNLOCKED = false;
    private static final boolean LOCKED = true;

    @Test
    void cueWithBaseReturnsToBase() {
        assertEquals(CompletionAction.RETURN_TO_BASE,
            StreamManager.decideCompletion(true, 1, FINISHED, NO_LOOP, UNLOCKED));
    }

    @Test
    void cueWithoutBaseReleasesOutput() {
        // 回归点：没有常驻机位时也必须结束，否则该机位会永久占住输出且无法再被触发。
        assertEquals(CompletionAction.RELEASE_OUTPUT,
            StreamManager.decideCompletion(true, -1, FINISHED, NO_LOOP, UNLOCKED));
    }

    @Test
    void cueWithVanishedBaseReleasesOutput() {
        // 常驻机位在切机位期间被停掉，baseManagerId 被清空。
        assertEquals(CompletionAction.RELEASE_OUTPUT,
            StreamManager.decideCompletion(true, -1, FINISHED, NO_LOOP, UNLOCKED));
    }

    @Test
    void finishedBaseReleasesOutput() {
        assertEquals(CompletionAction.RELEASE_OUTPUT,
            StreamManager.decideCompletion(false, 1, FINISHED, NO_LOOP, UNLOCKED));
    }

    @Test
    void loopingManagerNeverEnds() {
        assertEquals(CompletionAction.KEEP_RUNNING,
            StreamManager.decideCompletion(true, 1, true, LOOP, UNLOCKED));
        assertEquals(CompletionAction.KEEP_RUNNING,
            StreamManager.decideCompletion(false, 1, true, LOOP, UNLOCKED));
    }

    @Test
    void lockedManagerNeverEnds() {
        assertEquals(CompletionAction.KEEP_RUNNING,
            StreamManager.decideCompletion(true, 1, FINISHED, NO_LOOP, LOCKED));
        assertEquals(CompletionAction.KEEP_RUNNING,
            StreamManager.decideCompletion(false, 1, FINISHED, NO_LOOP, LOCKED));
    }

    @Test
    void unfinishedTimelineKeepsRunning() {
        assertEquals(CompletionAction.KEEP_RUNNING,
            StreamManager.decideCompletion(true, 1, NOT_FINISHED, NO_LOOP, UNLOCKED));
        assertEquals(CompletionAction.KEEP_RUNNING,
            StreamManager.decideCompletion(false, -1, NOT_FINISHED, NO_LOOP, UNLOCKED));
    }

    @Test
    void loopTakesPrecedenceOverMissingBase() {
        // 循环 + 无常驻：仍然常驻运行，由用户显式停止，而不是播完自停。
        assertEquals(CompletionAction.KEEP_RUNNING,
            StreamManager.decideCompletion(true, -1, true, LOOP, UNLOCKED));
    }
}
