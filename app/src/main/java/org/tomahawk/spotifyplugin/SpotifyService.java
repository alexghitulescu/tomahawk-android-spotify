package org.tomahawk.spotifyplugin;

import com.spotify.sdk.android.player.Config;
import com.spotify.sdk.android.player.ConnectionStateCallback;
import com.spotify.sdk.android.player.PlaybackBitrate;
import com.spotify.sdk.android.player.Player;
import com.spotify.sdk.android.player.PlayerNotificationCallback;
import com.spotify.sdk.android.player.PlayerState;
import com.spotify.sdk.android.player.PlayerStateCallback;
import com.spotify.sdk.android.player.Spotify;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.concurrent.RejectedExecutionException;

public class SpotifyService extends Service {

    // Used for debug logging
    private static final String TAG = SpotifyService.class.getSimpleName();

    /**
     * Command to the service to register a client, receiving callbacks from the service. The
     * Message's replyTo field must be a Messenger of the client where callbacks should be sent.
     */
    static final int MSG_REGISTER_CLIENT = 1;

    /**
     * Command to the service to unregister a client, ot stop receiving callbacks from the service.
     * The Message's replyTo field must be a Messenger of the client as previously given with
     * MSG_REGISTER_CLIENT.
     */
    static final int MSG_UNREGISTER_CLIENT = 2;

    /**
     * Commands to the service
     */
    private static final int MSG_PREPARE = 100;

    private static final String MSG_PREPARE_ARG_URI = "uri";

    private static final String MSG_PREPARE_ARG_ACCESSTOKEN = "accessToken";

    private static final String MSG_PREPARE_ARG_ACCESSTOKENEXPIRES = "accessTokenExpires";

    private static final int MSG_PLAY = 101;

    private static final int MSG_PAUSE = 102;

    private static final int MSG_SEEK = 103;

    private static final String MSG_SEEK_ARG_MS = "ms";

    private static final int MSG_SETBITRATE = 104;

    private static final String MSG_SETBITRATE_ARG_MODE = "mode";

    /**
     * Commands to the client
     */
    private static final int MSG_ONPAUSE = 200;

    private static final int MSG_ONPLAY = 201;

    private static final int MSG_ONPREPARED = 202;

    protected static final String MSG_ONPREPARED_ARG_URI = "uri";

    private static final int MSG_ONPLAYERENDOFTRACK = 203;

    private static final int MSG_ONPLAYERPOSITIONCHANGED = 204;

    private static final String MSG_ONPLAYERPOSITIONCHANGED_ARG_POSITION = "position";

    private static final String MSG_ONPLAYERPOSITIONCHANGED_ARG_TIMESTAMP = "timestamp";

    private static final int MSG_ONERROR = 205;

    private static final String MSG_ONERROR_ARG_MESSAGE = "message";

    public static final String CLIENT_ID = "";

    private WifiManager.WifiLock mWifiLock;

    private Player mPlayer;

    private String mPreparedUri;

    private String mCurrentAccessToken;

    /**
     * Keeps track of all current registered clients.
     */
    ArrayList<Messenger> mClients = new ArrayList<>();

    /**
     * Target we publish for clients to send messages to IncomingHandler.
     */
    final Messenger mMessenger = new Messenger(new IncomingHandler(this));

    /**
     * Handler of incoming messages from clients.
     */
    private static class IncomingHandler extends WeakReferenceHandler<SpotifyService> {

        public IncomingHandler(SpotifyService referencedObject) {
            super(referencedObject);
        }

        @Override
        public void handleMessage(Message msg) {
            final SpotifyService s = getReferencedObject();
            switch (msg.what) {
                case MSG_REGISTER_CLIENT:
                    s.mClients.add(msg.replyTo);
                    break;
                case MSG_UNREGISTER_CLIENT:
                    s.mClients.remove(msg.replyTo);
                    break;
                case MSG_PREPARE:
                    String uri = msg.getData().getString(MSG_PREPARE_ARG_URI);
                    String accessToken =
                            msg.getData().getString(MSG_PREPARE_ARG_ACCESSTOKEN);

                    s.mPreparedUri = uri;
                    if (s.mPlayer == null) {
                        Log.d(TAG, "First call to prepare. Initializing Player object...");
                        s.mCurrentAccessToken = accessToken;
                        Config playerConfig = new Config(s, accessToken, CLIENT_ID);
                        Player.Builder builder = new Player.Builder(playerConfig);
                        s.mPlayer = Spotify.getPlayer(builder, this,
                                new Player.InitializationObserver() {
                                    @Override
                                    public void onInitialized(Player player) {
                                        player.addConnectionStateCallback(
                                                s.mConnectionStateCallback);
                                        player.addPlayerNotificationCallback(
                                                s.mPlayerNotificationCallback);
                                        Bundle args = new Bundle();
                                        args.putString(MSG_ONPREPARED_ARG_URI, s.mPreparedUri);
                                        s.broadcastToAll(MSG_ONPREPARED, args);
                                    }

                                    @Override
                                    public void onError(Throwable throwable) {
                                        Log.e(TAG,
                                                "Could not initialize player: " + throwable
                                                        .getMessage());
                                    }
                                });
                    } else if (accessToken != null && !accessToken.equals(s.mCurrentAccessToken)) {
                        Log.d(TAG, "The access token has changed. Updating Player object...");
                        s.mCurrentAccessToken = accessToken;
                        s.mPlayer.logout();
                    } else {
                        Log.d(TAG, "Everything's set up and ready to go.");
                        Bundle args = new Bundle();
                        args.putString(MSG_ONPREPARED_ARG_URI, s.mPreparedUri);
                        s.broadcastToAll(MSG_ONPREPARED, args);
                    }
                    break;
                case MSG_PLAY:
                    Log.d(TAG, "play called");
                    if (s.mPlayer != null) {
                        try {
                            s.mPlayer.getPlayerState(new PlayerStateCallback() {
                                @Override
                                public void onPlayerState(PlayerState playerState) {
                                    if (!playerState.trackUri.equals(s.mPreparedUri)) {
                                        Log.d(TAG, "play - playing new track uri");
                                        s.mPlayer.play(s.mPreparedUri);
                                    } else if (!playerState.playing) {
                                        Log.d(TAG, "play - resuming playback");
                                        s.mPlayer.resume();
                                    }
                                }
                            });
                        } catch (RejectedExecutionException e) {
                            Log.e(TAG, "play - " + e.getLocalizedMessage());
                        }
                    }
                    break;
                case MSG_PAUSE:
                    Log.d(TAG, "pause called");
                    if (s.mPlayer != null) {
                        try {
                            Log.d(TAG, "pause - pausing playback");
                            s.mPlayer.pause();
                        } catch (RejectedExecutionException e) {
                            Log.e(TAG, "pause - " + e.getLocalizedMessage());
                        }
                    }
                    break;
                case MSG_SEEK:
                    int ms = msg.getData().getInt(MSG_SEEK_ARG_MS);

                    Log.d(TAG, "seek()");
                    if (s.mPlayer != null) {
                        try {
                            Log.d(TAG, "seek - seeking to " + ms + "ms");
                            s.mPlayer.seekToPosition(ms);

                            Bundle args = new Bundle();
                            args.putInt(MSG_ONPLAYERPOSITIONCHANGED_ARG_POSITION, ms);
                            args.putLong(MSG_ONPLAYERPOSITIONCHANGED_ARG_TIMESTAMP,
                                    System.currentTimeMillis());
                            s.broadcastToAll(MSG_ONPLAYERPOSITIONCHANGED, args);
                        } catch (RejectedExecutionException e) {
                            Log.e(TAG, "seek - " + e.getLocalizedMessage());
                        }
                    }
                    break;
                case MSG_SETBITRATE:
                    int mode = msg.getData().getInt(MSG_SETBITRATE_ARG_MODE);

                    if (s.mPlayer != null) {
                        PlaybackBitrate bitrate = null;
                        switch (mode) {
                            case 0:
                                bitrate = PlaybackBitrate.BITRATE_LOW;
                                break;
                            case 1:
                                bitrate = PlaybackBitrate.BITRATE_NORMAL;
                                break;
                            case 2:
                                bitrate = PlaybackBitrate.BITRATE_HIGH;
                                break;
                        }
                        if (bitrate != null) {
                            try {
                                s.mPlayer.setPlaybackBitrate(bitrate);
                            } catch (RejectedExecutionException e) {
                                Log.e(TAG, "setBitrate - " + e.getLocalizedMessage());
                            }
                        } else {
                            Log.d(TAG, "Invalid bitratemode given");
                        }
                    }
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    private final ConnectionStateCallback mConnectionStateCallback = new ConnectionStateCallback() {

        @Override
        public void onLoggedIn() {
            Log.d(TAG, "User logged in");
            Bundle args = new Bundle();
            args.putString(MSG_ONPREPARED_ARG_URI, mPreparedUri);
            broadcastToAll(MSG_ONPREPARED, args);
        }

        @Override
        public void onLoggedOut() {
            Log.d(TAG, "User logged out");
            if (mPlayer != null) {
                mPlayer.login(mCurrentAccessToken);
            } else {
                Log.e(TAG, "Wasn't able to login again, because mPlayer is null.");
            }
        }

        @Override
        public void onLoginFailed(Throwable error) {
            Log.e(TAG, "Login failed: " + error.getLocalizedMessage());
        }

        @Override
        public void onTemporaryError() {
            Log.e(TAG, "Temporary error occurred");
        }

        @Override
        public void onConnectionMessage(String message) {
            Log.d(TAG, "Received connection message: " + message);
        }
    };

    private final PlayerNotificationCallback mPlayerNotificationCallback
            = new PlayerNotificationCallback() {

        @Override
        public void onPlaybackEvent(final EventType eventType, PlayerState playerState) {
            Log.d(TAG, "Playback event received: " + eventType.name());
            if (playerState.trackUri.equals(mPreparedUri)) {
                switch (eventType) {
                    case TRACK_END:
                        broadcastToAll(MSG_ONPLAYERENDOFTRACK);
                        break;
                    case PAUSE:
                        broadcastToAll(MSG_ONPAUSE);
                        break;
                    case PLAY:
                        broadcastToAll(MSG_ONPLAY);
                        break;
                }
            }
        }

        @Override
        public void onPlaybackError(final ErrorType errorType, final String errorDetails) {
            Log.e(TAG, "Playback error received: " + errorType.name());
            Bundle args = new Bundle();
            args.putString(MSG_ONERROR_ARG_MESSAGE,
                    errorType.name() + ": " + errorDetails);
            broadcastToAll(MSG_ONERROR, args);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        mWifiLock = ((WifiManager) getSystemService(Context.WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL, "mylock");
        mWifiLock.acquire();
        Log.d(TAG, "SpotifyService has been created");
    }

    @Override
    public void onDestroy() {
        if (mPlayer != null) {
            mPlayer.removeConnectionStateCallback(mConnectionStateCallback);
            mPlayer.removePlayerNotificationCallback(mPlayerNotificationCallback);
            mPlayer.pause();
            mPlayer = null;
        }
        Spotify.destroyPlayer(this);
        mWifiLock.release();
        Log.d(TAG, "SpotifyService has been destroyed");

        super.onDestroy();
    }

    /**
     * When binding to the service, we return an interface to our messenger for sending messages to
     * the service.
     */
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Client has been bound to SpotifyService");
        return mMessenger.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "Client has been unbound from SpotifyService");
        return super.onUnbind(intent);
    }

    private void broadcastToAll(int what) {
        broadcastToAll(what, null);
    }

    private void broadcastToAll(int what, Bundle bundle) {
        for (int i = mClients.size() - 1; i >= 0; i--) {
            try {
                Message message = Message.obtain(null, what);
                message.setData(bundle);
                mClients.get(i).send(message);
            } catch (RemoteException e) {
                // The client is dead.  Remove it from the list;
                // we are going through the list from back to front
                // so this is safe to do inside the loop.
                mClients.remove(i);
            }
        }
    }
}
