package d2d.testing.streaming;

import java.util.UUID;

import d2d.testing.net.threads.workers.RTSPServerWorker;
import d2d.testing.streaming.sessions.ReceiveSession;

public class Streaming {
    private UUID mUUID;
    private String mName;
    private boolean isDownloading;
    private boolean isSharedSecret;
    private boolean mSharedSecretMode;
    private ReceiveSession mReceiveSession;

    public Streaming(UUID id, String name, ReceiveSession receiveSession){
        mUUID = id;
        mReceiveSession = receiveSession;
        mName = name;
        isDownloading = false;
        isSharedSecret = receiveSession.isSharedSecret();
        mSharedSecretMode = receiveSession.getSharedSecretMode();
    }

    public UUID getUUID() {
        return mUUID;
    }

    public ReceiveSession getReceiveSession() {
        return mReceiveSession;
    }

    public String getName() {
        return mName;
    }

    public void setReceiveSession(ReceiveSession mReceiveSession) {
        this.mReceiveSession = mReceiveSession;
    }

    public boolean isDownloading() {
        return isDownloading;
    }

    public void setDownloadState(boolean downloading) {
        isDownloading = downloading;
    }

    public boolean isSharedSecret() {
        return isSharedSecret;
    }
    public boolean ismSharedSecretMode() {
        return mSharedSecretMode;
    }
}
