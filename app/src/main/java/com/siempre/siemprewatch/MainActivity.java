package com.siempre.siemprewatch;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.firebase.FirebaseApp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.iid.FirebaseInstanceId;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;
import com.twilio.voice.Call;
import com.twilio.voice.CallInvite;
import com.twilio.voice.RegistrationException;
import com.twilio.voice.RegistrationListener;
import com.twilio.voice.Voice;

import java.util.Set;

import javax.annotation.Nullable;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int MIC_PERMISSION_REQUEST_CODE = 1;
    public static String CAPABILITY_PHONE_APP = "SIEMPRE_WATCH_CAPABILITY_PHONE_APP";
    public static String CAPABILITY_WATCH_APP = "SIEMPRE_WATCH_CAPABILITY_WATCH_APP";
    public static String TWILIO_ACCESS_TOKEN_SERVER_URL = "http://kumail.org:5002/accessToken?platform=android";
    private static final String MAKE_CALL = "/make_call";
    private static final String DISCONNECT = "/disconnect";
    private static final String INCOMING_CALL = "/incoming_call";
    private static final String ACCEPT_CALL = "/accept_call";
    private static final String REJECT_CALL = "/reject_call";

    public static final String ACTION_FCM_TOKEN = "ACTION_FCM_TOKEN";
    public static final String ACTION_INCOMING_CALL = "ACTION_INCOMING_CALL";
    public static final String ACTION_CANCEL_CALL = "ACTION_CANCEL_CALL";
    public static final String INCOMING_CALL_INVITE = "INCOMING_CALL_INVITE";

    Ringtone ringtone;

    private Context context;
    private String accessToken;
    private RegistrationListener registrationListener = registrationListener();
    private CallInvite callInvite;

    Node node = null;

    private FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = FirebaseFirestore.getInstance();

        context = getApplicationContext();
        SharedPreferences userDetails = context.getSharedPreferences("user", Context.MODE_PRIVATE);
        final SharedPreferences.Editor userEdit = userDetails.edit();
        userEdit.putString("userId", "1234");
        userEdit.commit();

        final Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        ringtone = RingtoneManager.getRingtone(context, notification);

        if (!checkPermissionForMicrophone()) {
            requestPermissionForMicrophone();
        }
        retrieveAccessToken();

        Task<CapabilityInfo> capabilityInfoTask = Wearable.getCapabilityClient(context)
                .getCapability(CAPABILITY_WATCH_APP, CapabilityClient.FILTER_REACHABLE);
        capabilityInfoTask.addOnCompleteListener(new OnCompleteListener<CapabilityInfo>() {
            @Override
            public void onComplete(Task<CapabilityInfo> task) {

                if (task.isSuccessful()) {
                    CapabilityInfo capabilityInfo = task.getResult();
                    Set<Node> nodes = capabilityInfo.getNodes();
                    if (nodes.size() > 0) {
                        node = nodes.toArray(new Node[1])[0];
                    }
                } else {
                    Log.d(TAG, "Capability request failed to return any results.");
                }

            }
        });

        Wearable.getMessageClient(context).addListener(new MessageClient.OnMessageReceivedListener() {
            @Override
            public void onMessageReceived(@NonNull MessageEvent messageEvent) {
                if (messageEvent.getPath().equals(MAKE_CALL)) {
                    String toCallId = new String(messageEvent.getData());
                    Intent intent = new Intent(MainActivity.this, CallService.class);
                    intent.setAction(CallService.MAKE_CALL_ACTION);
                    intent.putExtra("friendID", toCallId);
                    startService(intent);
                } else if (messageEvent.getPath().equals(DISCONNECT)) {
                    Intent intent = new Intent(MainActivity.this, CallService.class);
                    intent.setAction(CallService.DISCONNECT_ACTION);
                    startService(intent);
                } else if (messageEvent.getPath().equals(ACCEPT_CALL)) {
                    if (callInvite != null) {
                        ringtone.stop();
                        answer();
                    }
                } else if (messageEvent.getPath().equals(REJECT_CALL)) {
                    if (callInvite != null) {
                        ringtone.stop();
                        callInvite.reject(context);
                    }
                }
            }
        });

        db.collection("users").document("1234").addSnapshotListener(new EventListener<DocumentSnapshot>() {
            @Override
            public void onEvent(@Nullable DocumentSnapshot documentSnapshot, @Nullable FirebaseFirestoreException e) {
                if (e != null) {
                    Log.d(TAG, "status update listen failed", e);
                    return;
                }

                int status = ((Long)documentSnapshot.getData().get("status")).intValue();
                if (status != 3) {
                    userEdit.putInt("status", status);
                    userEdit.commit();
                }
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Log.d(TAG, "in onNewIntent");
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getAction() != null) {
            if (intent.getAction().equals(ACTION_FCM_TOKEN)) {
                retrieveAccessToken();
            } else if (intent.getAction().equals(ACTION_INCOMING_CALL)) {
                callInvite = intent.getParcelableExtra(INCOMING_CALL_INVITE);
                String actualID = callInvite.getFrom().substring(0, callInvite.getFrom().length() - 1);
                if (node != null) {
                    Task<Integer> sendTask = Wearable.getMessageClient(this.getApplicationContext()).sendMessage(
                            node.getId(), INCOMING_CALL, actualID.getBytes());

                    sendTask.addOnSuccessListener(new OnSuccessListener<Integer>() {
                        @Override
                        public void onSuccess(Integer integer) {
                            Log.d(TAG, "onSuccess incoming_call");
                        }
                    });
                    sendTask.addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            Log.d(TAG, "onFailure incoming_call");
                        }
                    });
                    ringtone.play();
                } else {
                    Log.d(TAG, "node is null");
                }
            }
        }
    }

    private void answer() {
        Intent intent = new Intent(getApplicationContext(), CallService.class);
        intent.setAction(CallService.ACCEPT_CALL_ACTION);
        intent.putExtra("callInvite", callInvite);
        startService(intent);
    }

    /*
     * Get an access token from your Twilio access token server
     */
    private void retrieveAccessToken() {
        Log.d(TAG, "at retreiveAccessToken");
        String accessURL = TWILIO_ACCESS_TOKEN_SERVER_URL + "&identity=" + "1234" + "a";
        Log.d(TAG, "accessURL " + accessURL);
        Ion.with(this).load(accessURL).asString().setCallback(new FutureCallback<String>() {
            @Override
            public void onCompleted(Exception e, String accessToken) {
                if (e == null) {
                    Log.d(TAG, "Access token: " + accessToken);
                    MainActivity.this.accessToken = accessToken;
                    registerForCallInvites();
                } else {
                    Toast.makeText(MainActivity.this,
                            "Error retrieving access token. Unable to make calls",
                            Toast.LENGTH_LONG).show();
                    Log.d(TAG, e.toString());
                }
            }
        });
    }

    private void registerForCallInvites() {
        final String fcmToken = FirebaseInstanceId.getInstance().getToken();
        if (fcmToken != null) {
            Log.d(TAG, "Registering with FCM token: " + fcmToken);
            Voice.register(accessToken, Voice.RegistrationChannel.FCM, fcmToken, registrationListener);
        }
    }

    private RegistrationListener registrationListener() {
        return new RegistrationListener() {
            @Override
            public void onRegistered(String accessToken, String fcmToken) {
                Log.d(TAG, "Successfully registered FCM " + fcmToken);
            }

            @Override
            public void onError(RegistrationException error, String accessToken, String fcmToken) {
                String message = String.format("Registration Error: %d, %s", error.getErrorCode(), error.getMessage());
                Log.e(TAG, message);
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        };
    }

    private boolean checkPermissionForMicrophone() {
        int resultMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        return resultMic == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionForMicrophone() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                MIC_PERMISSION_REQUEST_CODE);
    }
}
