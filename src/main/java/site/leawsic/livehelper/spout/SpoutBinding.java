package site.leawsic.livehelper.spout;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

public interface SpoutBinding extends Library {
    Pointer spCreateSpout(String senderName);

    void spReleaseSpout(Pointer spout);

    int spSendFrameBufferObject(Pointer spout, int fbo, int width, int height);

    static SpoutBinding load(String absolutePath) {
        return Native.load(absolutePath, SpoutBinding.class);
    }
}
