package d2d.testing.gui.main;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySession;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.PublishDiscoverySession;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareNetworkSpecifier;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import d2d.testing.net.threads.selectors.ChangeRequest;
import d2d.testing.net.threads.selectors.RTSPServerSelector;
import d2d.testing.streaming.rtsp.RtspClient;

public class WifiAwareNetwork implements INetwork {
    private static final int DELAY_BETWEEN_CONNECTIONS = 500;
    private WifiAwareManager mWifiAwareManager;
    private PublishDiscoverySession mPublishSession;
    private SubscribeDiscoverySession mSubscribeSession;
    private WifiAwareSession mWifiAwareSession;
    private Handler workerHandle;
    private final HandlerThread worker;
    private RTSPServerSelector mServer;
    private final Map<PeerHandle, RtspClient> mClients;
    private static ConnectivityManager mConManager;

    @Inject
    public WifiAwareNetwork(ConnectivityManager conManager, WifiAwareManager wifiAwareManager){

        mWifiAwareSession = null;
        mPublishSession = null;
        mSubscribeSession = null;
        mServer = null;

        this.mWifiAwareManager = wifiAwareManager;
        this.mClients = new HashMap<>();
        this.mConManager = conManager;

        worker = new HandlerThread("WifiAware Worker");
        worker.start();
        workerHandle = new Handler(worker.getLooper());

    }

    public boolean createSession() throws InterruptedException {
        if(mWifiAwareManager == null) return false;
        if(mWifiAwareSession != null) return true;
        synchronized (this){
            mWifiAwareManager.attach(new AttachCallback(){
                //When WFA is successfully attached
                @Override
                public void onAttached(WifiAwareSession session) {
                    synchronized (WifiAwareNetwork.this){
                        WifiAwareNetwork.this.mWifiAwareSession = session;
                        WifiAwareNetwork.this.notify();
                    }
                }

                @Override
                public void onAttachFailed() {
                    synchronized (WifiAwareNetwork.this){
                        mWifiAwareSession = null;
                        WifiAwareNetwork.this.notify();
                    }
                }
            }, workerHandle);
            //Espera notify del workerthread --> workerHandle
            this.wait();
            return mWifiAwareSession != null;
        }
    }

    /*
        Make a service discoverable
     */
    public boolean publishService(String serviceName) throws InterruptedException {
        if(mWifiAwareSession == null) return false;
        synchronized (WifiAwareNetwork.this){
            PublishConfig config = new PublishConfig.Builder()
                    .setServiceName(serviceName)
                    .build();
            DiscoverySessionCallback discoverySessionCallback = new DiscoverySessionCallback(){
                private int mLastMessageID = 0;
                private boolean mCreatingConnection = false;
                private final Queue<PeerHandle> mPendingConnections = new LinkedList<>();

                @Override
                public void onPublishStarted(@NonNull PublishDiscoverySession session) {
                    synchronized (WifiAwareNetwork.this){
                        mPublishSession = session;
                        try {
                            //------------------------------------v------------------------------------------
                            mServer = new RTSPServerSelector<PeerHandle>(mConManager);
                            mServer.start();
                            //Pone al server RTSP a escuchar en localhost:1234 para peticiones de descarga de libVLC
                            if(!mServer.addNewConnection("127.0.0.1", 1234)){
                                throw new IOException();
                            }
                        } catch (IOException e) {
                            session.close();
                            mPublishSession = null;
                        }
                        WifiAwareNetwork.this.notify();
                    }
                }

                @Override
                public void onSessionConfigFailed() {
                    synchronized (WifiAwareNetwork.this){
                        mPublishSession = null;
                        WifiAwareNetwork.this.notify();
                    }
                }

                @Override
                public void onMessageReceived(PeerHandle peerHandle, byte[] message) {
                    if(mCreatingConnection){
                        mPendingConnections.add(peerHandle);
                    }
                    else{
                        startConnection(peerHandle);
                    }
                }

                @Override
                public void onMessageSendSucceeded(int messageId) {
                    processNextConnection();
                }

                @Override
                public void onMessageSendFailed(int messageId) {
                    processNextConnection();
                }

                private void startConnection(PeerHandle peerHandle){
                    //El server puede establecer conexión con el primer PeerHandle que recibe en
                    // onMessageReceived, mientras que el subscriber usa el segundo, el PeerHandle que
                    // recibe en el mensaje devuelto por server
                    if(addNewConnection(mPublishSession, peerHandle)){
                        int messageId = mLastMessageID++;
                        mPublishSession.sendMessage(peerHandle, messageId, ("connect").getBytes());
                        mCreatingConnection = true;
                    }
                }

                private void processNextConnection(){
                    try {
                        Thread.sleep(DELAY_BETWEEN_CONNECTIONS);
                    } catch (InterruptedException ignored) {}
                    if(mPendingConnections.isEmpty()){
                        mCreatingConnection = false;
                    }
                    else{
                        PeerHandle nextPeerHandle = mPendingConnections.remove();
                        startConnection(nextPeerHandle);
                    }
                }
            };
            mWifiAwareSession.publish(config, discoverySessionCallback, workerHandle);

            this.wait();    //Espera a recibir un notify
            return mPublishSession != null;
        }
    }

    /*
        Subscribe to a service
     */
    public boolean subscribeToService(String serviceName, final MainFragment activity) throws InterruptedException {
        if(mWifiAwareSession == null) return false;

        synchronized (WifiAwareNetwork.this){
            SubscribeConfig config = new SubscribeConfig.Builder()
                    .setServiceName(serviceName)
                    .build();
            mWifiAwareSession.subscribe(config, new DiscoverySessionCallback(){
                private int mLastMessageID = 0;
                private boolean mCreatingConnection = false;
                private final Queue<PeerHandle> mPendingConnections = new LinkedList<>();

                @Override
                public void onSubscribeStarted(@NonNull SubscribeDiscoverySession session) {
                    synchronized (WifiAwareNetwork.this){
                        mSubscribeSession = session;
                        WifiAwareNetwork.this.notify();
                    }
                }

                @Override
                public void onSessionConfigFailed() {
                    synchronized (WifiAwareNetwork.this){
                        mSubscribeSession = null;
                        WifiAwareNetwork.this.notify();
                    }
                }

                @Override
                public void onServiceDiscovered(PeerHandle peerHandle, byte[] serviceSpecificInfo, List<byte[]> matchFilter) {
                    if(mCreatingConnection){
                        mPendingConnections.add(peerHandle);
                    }
                    else{
                        startConnection(peerHandle);
                    }
                }

                @Override
                public void onMessageSendFailed(int messageId) {
                    processNextConnection();
                }

                @Override
                public void onMessageReceived(PeerHandle peerHandle, byte[] message) {
                    RtspClient rtspClient = new RtspClient();
                    rtspClient.setCallback(activity); //TODO: Cambiar callback a un LiveData Object, puede haber excepciones
                    mClients.put(peerHandle, rtspClient);
                    rtspClient.connectionCreated(mConManager, mSubscribeSession, peerHandle);

                    processNextConnection();
                }

                private void startConnection(PeerHandle peerHandle){
                    mCreatingConnection = true;
                    int nextMessageId = mLastMessageID++;
                    mSubscribeSession.sendMessage(peerHandle, nextMessageId, ("connect").getBytes());
                }

                private void processNextConnection(){
                    try {
                        Thread.sleep(DELAY_BETWEEN_CONNECTIONS);
                    } catch (InterruptedException ignored) {}
                    if(mPendingConnections.isEmpty()){
                        mCreatingConnection = false;
                    }
                    else{
                        PeerHandle nextMessagePeerHandle = mPendingConnections.remove();
                        startConnection(nextMessagePeerHandle);
                    }
                }

            }, workerHandle);

            this.wait();
            return mSubscribeSession != null;
        }
    }

    public boolean sessionCreated(){
        return mWifiAwareSession != null;
    }

    public boolean publishSessionCreated(){
        return mPublishSession != null;
    }

    public boolean subscribeSessionCreated(){
        return mSubscribeSession != null;
    }

    public static ConnectivityManager getConnectivityManager() {
        return mConManager;
    }


    public synchronized void closeSessions(){
        if(mPublishSession != null){
            mPublishSession.close();
            mPublishSession = null;
        }
        if(mSubscribeSession != null){
            mSubscribeSession.close();
            mSubscribeSession = null;
        }
        if(mWifiAwareSession != null){
            mWifiAwareSession.close();
            mWifiAwareSession = null;
        }
        for(RtspClient client : mClients.values()){
            client.release();
        }
        mClients.clear();
        if(mServer != null){
            mServer.stop();
            mServer = null;
        }
    }

    public synchronized boolean addNewConnection(DiscoverySession discoverySession, PeerHandle handle){

        if(!mServer.getEnabled().get()) return false;
        if(mServer.getmConnectionsMap().get(handle) != null){
            return true;
        }

        RTSPServerSelector.Connection conn = null;
        try {
            //Crea un ServerSocketChannel específico para gestionar comunicación entre A y B
            //Una red aislada entre A y B
            ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.socket().bind(new InetSocketAddress(0));
            //The port the socket is listening
            int serverPort = serverSocketChannel.socket().getLocalPort();
            NetworkSpecifier networkSpecifier = new WifiAwareNetworkSpecifier.Builder(discoverySession, handle)
                    .setPskPassphrase("wifiawaretest")
                    .setPort(serverPort)
                    .build();
            //Red compatible con Wifi
            NetworkRequest networkRequest = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                    .setNetworkSpecifier(networkSpecifier)
                    .build();

            mServer.addChangeRequest(new ChangeRequest(serverSocketChannel,
                    ChangeRequest.REGISTER,
                    SelectionKey.OP_ACCEPT));

            conn = new RTSPServerSelector.Connection<PeerHandle>(serverSocketChannel,
                    handle,
                    new WifiAwareNetworkCallback(mServer, handle, mConManager));

            mConManager.requestNetwork(networkRequest, conn.mNetCallback);

            mServer.addNewConnection(handle,serverSocketChannel,conn);

        } catch (IOException e) {
            return false;
        }
        return true;
    }



    private static class WifiAwareNetworkCallback extends ConnectivityManager.NetworkCallback{

        private final PeerHandle mConnectionHandle;
        private final RTSPServerSelector mServer;
        private final ConnectivityManager mConManager;

        public WifiAwareNetworkCallback(RTSPServerSelector server, PeerHandle connectionHandle, ConnectivityManager conManager){
            this.mConnectionHandle = connectionHandle;
            this.mServer = server;
            mConManager = conManager;
        }

        @Override
        public void onAvailable(@NonNull Network network) {
            mServer.addNetToConnection(network, mConnectionHandle);
        }

        @Override
        public void onUnavailable() {
            mServer.removeConnection(mConnectionHandle);
        }

        @Override
        public void onLost(@NonNull Network network) {
            mServer.removeConnection(mConnectionHandle);
        }
    }


}