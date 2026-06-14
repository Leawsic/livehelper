package site.leawsic.livehelper.spout;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

public interface SpoutBinding extends Library {
    SpoutBinding INSTANCE = Native.load("libSpoutBinding", SpoutBinding.class);

    Pointer spCreateSpout(String senderName);

    void spReleaseSpout(Pointer spout);

    int spSendFrameBufferObject(Pointer spout, int fbo, int width, int height);
}
