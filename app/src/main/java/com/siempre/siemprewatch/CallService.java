package com.siempre.siemprewatch;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.FirebaseApp;
import com.google.firebase.firestore.FirebaseFirestore;
import com.koushikdutta.ion.Ion;
import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.ConnectOptions;
import com.twilio.voice.Voice;

import java.util.HashMap;
import java.util.List;

public class CallService extends Service {
    private static final String TAG = "CallService";

    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;

    private Call activeCall;
    private CallInvite activeCallInvite;
    Call.Listener callListener = callListener();
    HashMap<String, String> twiMLParams;

    public static final int NOTIFICATION_ID = 2;
    public static final String MAKE_CALL_ACTION = "MAKE_CALL_ACTION";
    public static final String DISCONNECT_ACTION = "DISCONNECT_ACTION";
    public static final String ACCEPT_CALL_ACTION = "ACCEPT_CALL_ACTION";

    Context context;
    Ringtone ringtone;
    MediaPlayer mp;

    SharedPreferences userDetails;

    private FirebaseFirestore db;

    @Override
    public void onCreate() {
        super.onCreate();

        context = getApplicationContext();
        userDetails = context.getSharedPreferences("user", MODE_PRIVATE);

        FirebaseApp.initializeApp(context);
        db = FirebaseFirestore.getInstance();

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        Uri notifURI = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        ringtone = RingtoneManager.getRingtone(context, notifURI);

        mp = MediaPlayer.create(this, R.raw.pingbing);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "in onStartCommand");
        String userId = userDetails.getString("userId", "default");
        Log.d(TAG, "onStartCommand has userId" + userId);
        String accessURL = MainActivity.TWILIO_ACCESS_TOKEN_SERVER_URL + "&identity=" + userId + "a";
        if (intent != null) {
            Log.d(TAG, "intent action " + intent.getAction());
            int status = intent.getIntExtra("status", 2);
            if (intent.getAction().equals(CallService.MAKE_CALL_ACTION)) {
                try {
                    String accessToken = Ion.with(this).load(accessURL).asString().get();
                    makeCall(intent.getStringExtra("friendID"), accessToken);
                } catch (Exception e) {
                    Log.d(TAG, e.toString());
                }
            } else if (intent.getAction().equals(CallService.DISCONNECT_ACTION)) {
                disconnect();
            } else if (intent.getAction().equals(CallService.ACCEPT_CALL_ACTION)) {
                acceptCall((CallInvite)intent.getParcelableExtra("callInvite"));
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    public void makeCall(String friendID, String accessToken) {
        Log.d(TAG, "in makeCall is active call null: " + (activeCall == null));
        twiMLParams = new HashMap<>();
        twiMLParams.put("to", friendID + "a");
        ConnectOptions connectOptions = new ConnectOptions.Builder(accessToken)
                .params(twiMLParams)
                .build();
        if (activeCall == null) {
            activeCall = Voice.connect(this, connectOptions, callListener);
        }
    }

    public void acceptCall(CallInvite callInvite) {
        if (activeCall == null) {
            activeCallInvite = callInvite;
            activeCallInvite.accept(this, callListener);
        }
    }

    public void disconnect() {
        Log.d(TAG, "Reached disconnect");
        if (activeCall != null) {
            Log.d(TAG, "Disconnect activeCall not null");
            activeCall.disconnect();
            activeCall = null;
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, "siempre.siemprewatch")
                    .setContentTitle("SiempreWatch")
                    .setContentText("Currently in call")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setPriority(Notification.PRIORITY_MAX)
                    .build();
        } else {
            return new Notification.Builder(this)
                    .setContentTitle("SiempreWatch")
                    .setContentText("Currently in call")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setPriority(Notification.PRIORITY_MAX)
                    .build();
        }
    }

    private Call.Listener callListener() {
        return new Call.Listener() {
            @Override
            public void onConnectFailure(Call call, CallException error) {
                setAudioFocus(false);
                Log.d(TAG, "Connect failure");
                String message = String.format("Call Error: %d, %s", error.getErrorCode(), error.getMessage());
                Log.e(TAG, message);
                activeCall = null;
                updateUIConnection(0);
                updateStatus(userDetails.getInt("status", 2));
                if (ringtone.isPlaying()) {
                    ringtone.stop();
                }
            }

            @Override
            public void onConnected(Call call) {
                ringtone.stop();
                mp.start();
                setAudioFocus(true);
                Log.d(TAG, "Connected");
                if (activeCall == null) {
                    activeCall = call;
                }
                startForeground(NOTIFICATION_ID, buildNotification());
                updateUIConnection(2);
                SharedPreferences.Editor edit = userDetails.edit();
                updateStatus(3);
            }

            @Override
            public void onDisconnected(Call call, CallException error) {
                setAudioFocus(false);
                Log.d(TAG, "Disconnected");
                if (error != null) {
                    String message = String.format("Call Error: %d, %s", error.getErrorCode(), error.getMessage());
                    Log.e(TAG, message);
                }

                if(ringtone.isPlaying()) {
                    ringtone.stop();
                }
                stopForeground(true);
                activeCall = null;
                updateUIConnection(0);
                updateStatus(userDetails.getInt("status", 2));
            }

            @Override
            public void onRinging(Call call) {
                Log.d(TAG, "Ringing");
                updateUIConnection(1);
                ringtone.play();
            }
        };
    }

    private void updateUIConnection(int status) {
        boolean isForeground = false;
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> appProcesses = activityManager.getRunningAppProcesses();
        String packageName = context.getPackageName();
        if (appProcesses != null) {
            for (ActivityManager.RunningAppProcessInfo appProcess : appProcesses) {
                if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND && appProcess.processName.equals(packageName)) {
                    isForeground = true;
                }
            }
        }
        /*
        if (isForeground) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setAction(MainActivity.ACTION_UPDATE_CONNECTION);
            intent.putExtra(MainActivity.CONNECTION_STATUS, status);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            this.startActivity(intent);
        }
        */
    }

    private void updateStatus(int status) {
        Log.d(TAG, "called updateStatus with " + status);
        String userId = userDetails.getString("userId", "default");
        db.collection("users").document(userId)
                .update("status", status).addOnSuccessListener(new OnSuccessListener<Void>() {
            @Override
            public void onSuccess(Void aVoid) {
                Log.d(TAG, "successfully updated status");
            }
        });
    }

    private void setAudioFocus(boolean setFocus) {
        if (audioManager != null) {
            if (setFocus) {
                // Request audio focus before making any device switch.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build();
                    audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(playbackAttributes)
                            .setAcceptsDelayedFocusGain(true)
                            .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                                @Override
                                public void onAudioFocusChange(int i) {
                                }
                            })
                            .build();
                    audioManager.requestAudioFocus(audioFocusRequest);
                } else {
                    audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL,
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                }
                /*
                 * Start by setting MODE_IN_COMMUNICATION as default audio mode. It is
                 * required to be in this mode when playout and/or recording starts for
                 * best possible VoIP performance. Some devices have difficulties with speaker mode
                 * if this is not set.
                 */
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.startBluetoothSco();
            } else {
                Log.d(TAG, "Setting audiomode to normal");
                audioManager.setMode(AudioManager.MODE_NORMAL);
                audioManager.abandonAudioFocus(null);
                Log.d(TAG, "Audiomode is : " + audioManager.getMode());
                audioManager.stopBluetoothSco();
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
