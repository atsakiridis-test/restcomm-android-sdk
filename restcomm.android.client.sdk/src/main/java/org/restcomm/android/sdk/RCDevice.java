package org.restcomm.android.sdk;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import org.restcomm.android.sdk.MediaClient.AppRTCAudioManager;
import org.restcomm.android.sdk.SignalingClient.JainSipClient.JainSipConfiguration;
import org.restcomm.android.sdk.SignalingClient.SignalingClient;
import org.restcomm.android.sdk.util.ErrorStruct;
import org.restcomm.android.sdk.util.RCLogger;
import org.restcomm.android.sdk.util.RCUtils;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class RCDevice extends Service implements SignalingClient.SignalingClientListener {
   /**
    * Device state
    */
   static DeviceState state;
   /**
    * Device capabilities (<b>Not Implemented yet</b>)
    */
   HashMap<DeviceCapability, Object> capabilities;
   /**
    * Listener that will be receiving RCDevice events described at RCDeviceListener
    */
   RCDeviceListener listener;
   /**
    * Is sound for incoming connections enabled
    */
   boolean incomingSoundEnabled;
   /**
    * Is sound for outgoing connections enabled
    */
   boolean outgoingSoundEnabled;
   /**
    * Is sound for disconnect enabled
    */
   boolean disconnectSoundEnabled;

   /**
    * Device state
    */
   public enum DeviceState {
      OFFLINE, /**
       * Device is offline
       */
      READY, /**
       * Device is ready to make and receive connections
       */
      BUSY,  /** Device is busy */
   }

   /**
    * Device capability (<b>Not Implemented yet</b>)
    */
   public enum DeviceCapability {
      INCOMING,
      OUTGOING,
      EXPIRATION,
      ACCOUNT_SID,
      APPLICATION_SID,
      APPLICATION_PARAMETERS,
      CLIENT_NAME,
   }

   /**
    * Parameter keys for RCClient.createDevice() and RCDevice.updateParams()
    */
   public static class ParameterKeys {
      public static final String INTENT_INCOMING_CALL = "incoming-call-intent";
      public static final String INTENT_INCOMING_MESSAGE = "incoming-message-intent";
      public static final String SIGNALING_USERNAME = "pref_sip_user";
      public static final String SIGNALING_DOMAIN = "pref_proxy_domain";
      public static final String SIGNALING_PASSWORD = "pref_sip_password";
      public static final String SIGNALING_SECURE_ENABLED = "signaling-secure";
      public static final String SIGNALING_LOCAL_PORT = "signaling-local-port";
      public static final String SIGNALING_JAIN_SIP_LOGGING_ENABLED = "jain-sip-logging-enabled";
      public static final String MEDIA_TURN_ENABLED = "turn-enabled";
      public static final String MEDIA_ICE_URL = "turn-url";
      public static final String MEDIA_ICE_USERNAME = "turn-username";
      public static final String MEDIA_ICE_PASSWORD = "turn-password";
      public static final String RESOURCE_SOUND_CALLING = "sound-calling";
      public static final String RESOURCE_SOUND_RINGING = "sound-ringing";
      public static final String RESOURCE_SOUND_DECLINED = "sound-declined";
      public static final String RESOURCE_SOUND_MESSAGE = "sound-message";
   }

   private static final String TAG = "RCDevice";

   // Service Intent actions
   public static String ACTION_OUTGOING_CALL = "org.restcomm.android.sdk.ACTION_OUTGOING_CALL";
   public static String ACTION_INCOMING_CALL = "org.restcomm.android.sdk.ACTION_INCOMING_CALL";
   public static String ACTION_INCOMING_CALL_ANSWER_AUDIO = "org.restcomm.android.sdk.ACTION_INCOMING_CALL_ANSWER_AUDIO";
   public static String ACTION_INCOMING_CALL_ANSWER_VIDEO = "org.restcomm.android.sdk.ACTION_INCOMING_CALL_ANSWER_VIDEO";
   public static String ACTION_INCOMING_CALL_DECLINE = "org.restcomm.android.sdk.ACTION_INCOMING_CALL_DECLINE";
   public static String ACTION_OPEN_MESSAGE_SCREEN = "org.restcomm.android.sdk.ACTION_OPEN_MESSAGE_SCREEN";
   public static String ACTION_INCOMING_MESSAGE = "org.restcomm.android.sdk.ACTION_INCOMING_MESSAGE";

   // Intents sent by Notification subsystem -> RCDevice Service when user acts on the Notifications
   public static String ACTION_NOTIFICATION_CALL_DEFAULT = "org.restcomm.android.sdk.ACTION_NOTIFICATION_CALL_DEFAULT";
   public static String ACTION_NOTIFICATION_CALL_ACCEPT_VIDEO = "org.restcomm.android.sdk.ACTION_NOTIFICATION_CALL_ACCEPT_VIDEO";
   public static String ACTION_NOTIFICATION_CALL_ACCEPT_AUDIO = "org.restcomm.android.sdk.ACTION_NOTIFICATION_CALL_ACCEPT_AUDIO";
   public static String ACTION_NOTIFICATION_CALL_DECLINE = "org.restcomm.android.sdk.ACTION_NOTIFICATION_CALL_DECLINE";
   public static String ACTION_NOTIFICATION_MESSAGE_DEFAULT = "org.restcomm.android.sdk.ACTION_NOTIFICATION_MESSAGE_DEFAULT";

   public static String EXTRA_MESSAGE_TEXT = "org.restcomm.android.sdk.EXTRA_MESSAGE_TEXT";
   public static String EXTRA_DID = "org.restcomm.android.sdk.EXTRA_DID";
   public static String EXTRA_CUSTOM_HEADERS = "org.restcomm.android.sdk.CUSTOM_HEADERS";
   public static String EXTRA_VIDEO_ENABLED = "org.restcomm.android.sdk.VIDEO_ENABLED";

   // Notification ids for calls and mesages
   private final int NOTIFICATION_ID_CALL = 1;
   private final int NOTIFICATION_ID_MESSAGE = 2;

   //public static String EXTRA_NOTIFICATION_ACTION_TYPE = "org.restcomm.android.sdk.NOTIFICATION_ACTION_TYPE";
   //public static String EXTRA_SDP = "com.telestax.restcomm_messenger.SDP";
   //public static String EXTRA_DEVICE = "com.telestax.restcomm.android.client.sdk.extra-device";
   //public static String EXTRA_CONNECTION = "com.telestax.restcomm.android.client.sdk.extra-connection";

   // Parameters passed in the RCDevice constructor
   private HashMap<String, Object> parameters;
   private Intent callIntent;
   private Intent messageIntent;
   private HashMap<String, RCConnection> connections;
   //private RCConnection incomingConnection;
   private RCDeviceListener.RCConnectivityStatus cachedConnectivityStatus = RCDeviceListener.RCConnectivityStatus.RCConnectivityStatusNone;
   private SignalingClient signalingClient;
   private AppRTCAudioManager audioManager = null;
   //private Context context = null;

   // Binder given to clients
   private final IBinder deviceBinder = new RCDeviceBinder();
   // Has RCDevice been initialized?
   boolean isServiceInitialized = false;
   // Is an activity currently attached to RCDevice service?
   boolean isServiceAttached = false;
   // how many Activities are attached to the Service
   private int serviceReferenceCount = 0;

   public enum NotificationType {
      ACCEPT_CALL_VIDEO,
      ACCEPT_CALL_AUDIO,
      REJECT_CALL,
      NAVIGATE_TO_CALL,
   }

   // Apps must not have access to the constructor, as it is created inside the service
   public RCDevice()
   {
   }

   /**
    * Class used for the client Binder.  Because we know this service always
    * runs in the same process as its clients, we don't need to deal with IPC.
    */
   public class RCDeviceBinder extends Binder {
      public RCDevice getService()
      {
         // Return this instance of LocalService so clients can call public methods
         return RCDevice.this;
      }
   }


   @Override
   public void onCreate()
   {
      // Only runs once, when service is created
      Log.i(TAG, "%% onCreate");

   }

   @Override
   public int onStartCommand(Intent intent, int flags, int startId)
   {
      // Runs whenever the user calls startService()
      Log.i(TAG, "%% onStartCommand");


      if (intent != null && intent.getAction() != null) {
         handleNotification(intent);
      }

      // If we get killed, after returning from here, restart
      return START_STICKY;
   }

   @Override
   public IBinder onBind(Intent intent)
   {
      Log.i(TAG, "%%  onBind");

      // We want the service to be both 'bound' and 'started' so that it lingers on after all clients have been unbound (I know the application is supposed
      // to call startService(), but let's make an exception in order to keep the API simple and easy to use
      startService(intent);

      isServiceAttached = true;

      // provide the binder
      return deviceBinder;
   }

   @Override
   public void onRebind(Intent intent)
   {
      Log.i(TAG, "%%  onRebind");

      isServiceAttached = true;
   }

   @Override
   public void onDestroy()
   {
      Log.i(TAG, "%% onDestroy");
   }

   @Override
   public boolean onUnbind(Intent intent)
   {
      Log.i(TAG, "%%  onUnbind");

      isServiceAttached = false;

      return true;
   }

   /*
    * Methods for clients to use
    */
   public boolean isInitialized()
   {
      return isServiceInitialized;
   }


   public boolean isAttached()
   {
      return isServiceAttached;
   }

   /**
    * Attach and initialize (if not already initialized) the RCDevice Service with parameters
    *
    * @param activityContext Activity context
    * @param parameters      Parameters for the Device entity (prefer using the string constants shown below, i.e. RCDevice.ParameterKeys.*, instead of
    *                        using strings like 'signaling-secure', etc. Possible keys: <br>
    *                        <b>RCDevice.ParameterKeys.SIGNALING_USERNAME</b>: Identity for the client, like <i>'bob'</i> (mandatory) <br>
    *                        <b>RCDevice.ParameterKeys.SIGNALING_PASSWORD</b>: Password for the client (mandatory) <br>
    *                        <b>RCDevice.ParameterKeys.SIGNALING_DOMAIN</b>: Restcomm instance to use, like <i>'cloud.restcomm.com'</i>. Leave empty for registrar-less mode<br>
    *                        <b>RCDevice.ParameterKeys.MEDIA_ICE_URL</b>: ICE url to use, like <i>'https://turn.provider.com/turn'</i> (mandatory) <br>
    *                        <b>RCDevice.ParameterKeys.MEDIA_ICE_USERNAME</b>: ICE username for authentication (mandatory) <br>
    *                        <b>RCDevice.ParameterKeys.MEDIA_ICE_PASSWORD</b>: ICE password for authentication (mandatory) <br>
    *                        <b>RCDevice.ParameterKeys.SIGNALING_SECURE_ENABLED</b>: Should signaling traffic be encrypted? If this is the case, then a key pair is generated when
    *                        signaling facilities are initialized and added to a custom keystore. Also, added to this custom keystore are all the trusted certificates from
    *                        the System Wide Android CA Store, so that we properly accept legit server certificates (optional) <br>
    *                        <b>RCDevice.ParameterKeys.MEDIA_TURN_ENABLED</b>: Should TURN be enabled for webrtc media? (optional) <br>
    *                        <b>RCDevice.ParameterKeys.SIGNALING_LOCAL_PORT</b>: Local port to use for signaling (optional) <br>
    *                        <b>RCDevice.ParameterKeys.RESOURCE_SOUND_CALLING</b>: The SDK provides the user with default sounds for calling, ringing, busy (declined) and message events, but the user can override them
    *                        by providing their own resource files (i.e. .wav, .mp3, etc) at res/raw passing them here with Resource IDs like R.raw.user_provided_calling_sound. This parameter
    *                        configures the sound you will hear when you make a call and until the call is either replied or you hang up<br>
    *                        <b>RCDevice.ParameterKeys.RESOURCE_SOUND_RINGING</b>: The sound you will hear when you receive a call <br>
    *                        <b>RCDevice.ParameterKeys.RESOURCE_SOUND_DECLINED</b>: The sound you will hear when your call is declined <br>
    *                        <b>RCDevice.ParameterKeys.RESOURCE_SOUND_MESSAGE</b>: The sound you will hear when you receive a message <br>
    * @param deviceListener  The listener for upcoming RCDevice events
    * @return True if this is the first time RCDevice Service is attached to and hence initialization took place. False, if the service has already been initialized
    * Remember that once the Service starts in continues to run in the background even if the App doesn't have any activity running
    * @see RCDevice
    */
   public boolean initialize(Context activityContext, HashMap<String, Object> parameters, RCDeviceListener deviceListener)
   {
      if (!isServiceInitialized) {
         isServiceInitialized = true;
         //context = activityContext;

         RCLogger.i(TAG, "RCDevice(): " + parameters.toString());

         ErrorStruct errorStruct = RCUtils.validateParms(parameters);
         if (errorStruct.statusCode != RCClient.ErrorCodes.SUCCESS) {
            throw new RuntimeException(errorStruct.statusText);
         }

         //this.updateCapabilityToken(capabilityToken);
         this.listener = deviceListener;

         if (!parameters.containsKey(RCDevice.ParameterKeys.INTENT_INCOMING_CALL) ||
               !parameters.containsKey(RCDevice.ParameterKeys.INTENT_INCOMING_MESSAGE)) {
            throw new RuntimeException(RCClient.errorText(RCClient.ErrorCodes.ERROR_DEVICE_MISSING_INTENTS));
         }

         setPendingIntents((Intent) parameters.get(RCDevice.ParameterKeys.INTENT_INCOMING_CALL),
               (Intent) parameters.get(ParameterKeys.INTENT_INCOMING_MESSAGE));

         // TODO: check if those headers are needed
         HashMap<String, String> customHeaders = new HashMap<>();
         state = DeviceState.OFFLINE;

         connections = new HashMap<String, RCConnection>();
         // initialize JAIN SIP if we have connectivity
         this.parameters = parameters;

         // check if TURN keys are there
         //params.put(RCDevice.ParameterKeys.MEDIA_TURN_ENABLED, prefs.getBoolean(RCDevice.ParameterKeys.MEDIA_TURN_ENABLED, true));

         signalingClient = SignalingClient.getInstance();
         signalingClient.open(this, getApplicationContext(), parameters);

         // Create and audio manager that will take care of audio routing,
         // audio modes, audio device enumeration etc.
         audioManager = AppRTCAudioManager.create(getApplicationContext(), new Runnable() {
                  // This method will be called each time the audio state (number and
                  // type of devices) has been changed.
                  @Override
                  public void run()
                  {
                     onAudioManagerChangedState();
                  }
               }
         );

         // Store existing audio settings and change audio mode to
         // MODE_IN_COMMUNICATION for best possible VoIP performance.
         RCLogger.d(TAG, "Initializing the audio manager...");
         audioManager.init(parameters);

         return true;
      }

      //serviceReferenceCount++;

      // already initialized
      return false;
   }

   /**
    * Call when App is inactive (i.e. in the background) and hence notifications should be used instead of firing intents right away.
    * This typically needs to be called at onStop() of your Activity
    */
   /*
   public void detach()
   {
      //isServiceAttached = false;
      //serviceReferenceCount--;
   }
   */
   public void setLogLevel(int level)
   {
      RCLogger.setLogLevel(level);
   }

   // TODO: this is for RCConnection, but see if they can figure out the connectivity in a different way, like asking the signaling thread directly?
   public RCDeviceListener.RCConnectivityStatus getConnectivityStatus()
   {
      return cachedConnectivityStatus;
   }

   // 'Copy' constructor
   public RCDevice(RCDevice device)
   {
      this.incomingSoundEnabled = device.incomingSoundEnabled;
      this.outgoingSoundEnabled = device.outgoingSoundEnabled;
      this.disconnectSoundEnabled = device.disconnectSoundEnabled;
      this.listener = device.listener;

      // Not used yet
      this.capabilities = null;
   }

   /**
    * Shut down and release the RCDevice Service
    */
   public void release()
   {
      RCLogger.i(TAG, "release()");
      if (audioManager != null) {
         audioManager.close();
         audioManager = null;
      }

      this.listener = null;

      signalingClient.close();
      state = DeviceState.OFFLINE;

      isServiceAttached = false;
      //detach();
      isServiceInitialized = false;
   }

   /**
    * Start listening for incoming connections
    */
   public void listen()
   {
      RCLogger.i(TAG, "listen()");

      if (state == DeviceState.READY) {
         // TODO: implement with new signaling

      }
   }

   /**
    * Stop listening for incoming connections
    */
   public void unlisten()
   {
      RCLogger.i(TAG, "unlisten()");

      if (state == DeviceState.READY) {
         // TODO: implement with new signaling
      }
   }

   /**
    * Update Device listener to be receiving Device related events. This is
    * usually needed when we switch activities and want the new activity to receive
    * events
    *
    * @param listener New device listener
    */
   public void setDeviceListener(RCDeviceListener listener)
   {
      RCLogger.i(TAG, "setDeviceListener()");

      this.listener = listener;
   }

   /**
    * Retrieves the capability token passed to RCClient.createDevice
    *
    * @return Capability token
    */
   public String getCapabilityToken()
   {
      return "";
   }

   /**
    * Updates the capability token (<b>Not implemented yet</b>)
    */
   public void updateCapabilityToken(String token)
   {

   }

   /**
    * Create an outgoing connection to an endpoint. Important: if you work with Android API 23 or above you will need to handle dynamic Android permissions in your Activity
    * as described at https://developer.android.com/training/permissions/requesting.html. More specifically the Restcomm Client SDK needs RECORD_AUDIO, CAMERA (only if the local user
    * has enabled local video via RCConnection.ParameterKeys.CONNECTION_VIDEO_ENABLED; if not then this permission isn't needed), and USE_SIP permission
    * to be able to connect(). For an example of such permission handling you can check MainActivity of restcomm-hello world sample App. Notice that if any of these permissions
    * are missing, the call will fail with a ERROR_CONNECTION_PERMISSION_DENIED error.
    *
    * @param parameters Parameters such as the endpoint we want to connect to, etc. Possible keys: <br>
    *                   <b>RCConnection.ParameterKeys.CONNECTION_PEER</b>: Who is the called number, like <i>'+1235'</i> or <i>'sip:+1235@cloud.restcomm.com'</i> <br>
    *                   <b>RCConnection.ParameterKeys.CONNECTION_VIDEO_ENABLED</b>: Whether we want WebRTC video enabled or not <br>
    *                   <b>RCConnection.ParameterKeys.CONNECTION_LOCAL_VIDEO</b>: PercentFrameLayout containing the view where we want the local video to be rendered in. You can check res/layout/activity_main.xml
    *                   in hello-world sample to see the structure required <br>
    *                   <b>RCConnection.ParameterKeys.CONNECTION_REMOTE_VIDEO</b>: PercentFrameLayout containing the view where we want the remote video to be rendered. You can check res/layout/activity_main.xml
    *                   in hello-world sample to see the structure required  <br>
    *                   <b>RCConnection.ParameterKeys.CONNECTION_PREFERRED_VIDEO_CODEC</b>: Preferred video codec to use. Default is VP8. Possible values: <i>'VP8', 'VP9'</i> <br>
    *                   <b>RCConnection.ParameterKeys.CONNECTION_CUSTOM_SIP_HEADERS</b>: An optional HashMap<String,String> of custom SIP headers we want to add. For an example
    *                   please check HelloWorld sample or Olympus App. <br>
    * @param listener   The listener object that will receive events when the connection state changes
    * @return An RCConnection object representing the new connection or null in case of error. Error
    * means that RCDevice.state not ready to make a call (this usually means no WiFi available)
    */
   public RCConnection connect(Map<String, Object> parameters, RCConnectionListener listener)
   {
      RCLogger.i(TAG, "connect(): " + parameters.toString());

      if (cachedConnectivityStatus == RCDeviceListener.RCConnectivityStatus.RCConnectivityStatusNone) {
         // Phone state Intents to capture connection failed event
         String username = "";
         if (parameters != null && parameters.get(RCConnection.ParameterKeys.CONNECTION_PEER) != null)
            username = parameters.get(RCConnection.ParameterKeys.CONNECTION_PEER).toString();
         sendQoSNoConnectionIntent(username, this.getConnectivityStatus().toString());
      }

      if (state == DeviceState.READY) {
         RCLogger.i(TAG, "RCDevice.connect(), with connectivity");

         state = DeviceState.BUSY;

         RCConnection connection = new RCConnection.Builder(false, RCConnection.ConnectionState.PENDING, this, signalingClient, audioManager)
               .listener(listener)
               .peer((String) parameters.get(RCConnection.ParameterKeys.CONNECTION_PEER))
               .build();
         connection.open(parameters);

         // keep connection in the connections hashmap
         connections.put(connection.getId(), connection);

         return connection;
      }
      else {
         return null;
      }
   }

   /**
    * Send an instant message to an endpoint
    *
    * @param message    Message text
    * @param parameters Parameters used for the message, such as 'username' that holds the recepient for the message
    */
   public boolean sendMessage(String message, Map<String, String> parameters)
   {
      RCLogger.i(TAG, "sendMessage(): message:" + message + "\nparameters: " + parameters.toString());

      if (state == DeviceState.READY) {
         HashMap<String, Object> messageParameters = new HashMap<>();
         messageParameters.put(RCConnection.ParameterKeys.CONNECTION_PEER, parameters.get(RCConnection.ParameterKeys.CONNECTION_PEER));
         messageParameters.put("text-message", message);
         //RCMessage message = RCMessage.newInstanceOutgoing(messageParameters, listener);
         signalingClient.sendMessage(messageParameters);
         return true;
      }
      else {
         return false;
      }
   }

   /**
    * Disconnect all connections
    */
   public void disconnectAll()
   {
      RCLogger.i(TAG, "disconnectAll()");

      if (state == DeviceState.BUSY) {
         for (Map.Entry<String, RCConnection> entry : connections.entrySet()) {
            RCConnection connection = entry.getValue();
            connection.disconnect();
         }
         connections.clear();
         state = DeviceState.READY;
      }
   }

   /**
    * Retrieve the capabilities
    *
    * @return Capabilities
    */
   public Map<DeviceCapability, Object> getCapabilities()
   {
      HashMap<DeviceCapability, Object> map = new HashMap<DeviceCapability, Object>();
      return map;
   }

   /**
    * Retrieve the Device state
    *
    * @return State
    */
   public DeviceState getState()
   {
      return state;
   }

   /**
    * Set Intents for incoming calls and messages that will later be wrapped in PendingIntents. In order to be notified of RestComm Client
    * events you need to associate your Activities with intents and provide one intent for whichever activity
    * will be receiving calls and another intent for the activity receiving messages. If you use a single Activity
    * for both then you can pass the same intent both as a callIntent as well as a messageIntent
    *
    * @param callIntent    an intent that will be sent on an incoming call
    * @param messageIntent an intent that will be sent on an incoming text message
    */
   public void setPendingIntents(Intent callIntent, Intent messageIntent)
   {
      RCLogger.i(TAG, "setPendingIntents()");

      this.callIntent = callIntent;
      this.messageIntent = messageIntent;
      //pendingCallIntent = PendingIntent.getActivity(context, 0, callIntent, PendingIntent.FLAG_UPDATE_CURRENT);
      //pendingMessageIntent = PendingIntent.getActivity(context, 0, messageIntent, PendingIntent.FLAG_UPDATE_CURRENT);
   }

   // Get incoming ringing connection
   public RCConnection getPendingConnection()
   {
      Iterator it = connections.entrySet().iterator();
      while (it.hasNext()) {
         Map.Entry pair = (Map.Entry) it.next();
         RCConnection connection = (RCConnection) pair.getValue();
         if (connection.incoming && connection.state == RCConnection.ConnectionState.CONNECTING) {
            return connection;
         }
      }

      return null;
   }

   // Get live connection, to reference live calls after we have left the call window
   public RCConnection getLiveConnection()
   {
      Iterator it = connections.entrySet().iterator();
      while (it.hasNext()) {
         Map.Entry pair = (Map.Entry) it.next();
         RCConnection connection = (RCConnection) pair.getValue();
         if (connection.state == RCConnection.ConnectionState.CONNECTED) {
            return connection;
         }
      }

      return null;
   }

   /**
    * Should a ringing sound be played in a incoming connection or message
    *
    * @param incomingSound Whether or not the sound should be played
    */
   public void setIncomingSoundEnabled(boolean incomingSound)
   {
      RCLogger.i(TAG, "setIncomingSoundEnabled()");
      // TODO: implement with new signaling
      //DeviceImpl.GetInstance().soundManager.setIncoming(incomingSound);
   }

   /**
    * Retrieve the incoming sound setting
    *
    * @return Whether the sound will be played
    */
   public boolean isIncomingSoundEnabled()
   {
      // TODO: implement with new signaling
      //return DeviceImpl.GetInstance().soundManager.getIncoming();
      return true;
   }

   /**
    * Should a ringing sound be played in an outgoing connection or message
    *
    * @param outgoingSound Whether or not the sound should be played
    */
   public void setOutgoingSoundEnabled(boolean outgoingSound)
   {
      RCLogger.i(TAG, "setOutgoingSoundEnabled()");
      // TODO: implement with new signaling
      //DeviceImpl.GetInstance().soundManager.setOutgoing(outgoingSound);
   }

   /**
    * Retrieve the outgoint sound setting
    *
    * @return Whether the sound will be played
    */
   public boolean isOutgoingSoundEnabled()
   {
      // TODO: implement with new signaling
      //return DeviceImpl.GetInstance().soundManager.getOutgoing();
      return true;
   }

   /**
    * Should a disconnect sound be played when disconnecting a connection
    *
    * @param disconnectSound Whether or not the sound should be played
    */
   public void setDisconnectSoundEnabled(boolean disconnectSound)
   {
      RCLogger.i(TAG, "setDisconnectSoundEnabled()");
      // TODO: implement with new signaling
      //DeviceImpl.GetInstance().soundManager.setDisconnect(disconnectSound);
   }

   /**
    * Retrieve the disconnect sound setting
    *
    * @return Whether the sound will be played
    */
   public boolean isDisconnectSoundEnabled()
   {
      // TODO: implement with new signaling
      //return DeviceImpl.GetInstance().soundManager.getDisconnect();
      return true;
   }

   /**
    * Update Device parameters such as username, password, domain, etc
    *
    * @param params Parameters for the Device entity (prefer using the string constants shown below, i.e. RCDevice.ParameterKeys.*, instead of using strings
    *               like 'signaling-secure', etc. Possible keys: <br>
    *               <b>RCDevice.ParameterKeys.SIGNALING_USERNAME</b>: Identity for the client, like <i>'bob'</i> (mandatory) <br>
    *               <b>RCDevice.ParameterKeys.SIGNALING_PASSWORD</b>: Password for the client (mandatory) <br>
    *               <b>RCDevice.ParameterKeys.SIGNALING_DOMAIN</b>: Restcomm instance to use, like <i>'cloud.restcomm.com'</i>. Leave empty for registrar-less mode<br>
    *               <b>RCDevice.ParameterKeys.MEDIA_ICE_URL</b>: ICE url to use, like <i>'https://turn.provider.com/turn'</i> (mandatory) <br>
    *               <b>RCDevice.ParameterKeys.MEDIA_ICE_USERNAME</b>: ICE username for authentication (mandatory) <br>
    *               <b>RCDevice.ParameterKeys.MEDIA_ICE_PASSWORD</b>: ICE password for authentication (mandatory) <br>
    *               <b>RCDevice.ParameterKeys.SIGNALING_SECURE_ENABLED</b>: Should signaling traffic be encrypted? If this is the case, then a key pair is generated when
    *               signaling facilities are initialized and added to a custom keystore. Also, added to this custom keystore are all the trusted certificates from
    *               the System Wide Android CA Store, so that we properly accept legit server certificates (optional) <br>
    *               <b>RCDevice.ParameterKeys.MEDIA_TURN_ENABLED</b>: Should TURN be enabled for webrtc media? (optional) <br>
    *               <b>RCDevice.ParameterKeys.SIGNALING_LOCAL_PORT</b>: Local port to use for signaling (optional) <br>
    * @see RCDevice
    */
   public boolean updateParams(HashMap<String, Object> params)
   {
      signalingClient.reconfigure(params);

      // remember that the new parameters can be just a subset of the currently stored in this.parameters, so to update the current parameters we need
      // to merge them with the new (i.e. keep the old and replace any new keys with new values)
      this.parameters = JainSipConfiguration.mergeParameters(this.parameters, params);

      // TODO: need to provide asynchronous status for this
      return true;
   }

   public HashMap<String, Object> getParameters()
   {
      return parameters;
   }

   public SignalingClient.SignalingClientCallListener getConnectionByJobId(String jobId)
   {
      if (connections.containsKey(jobId)) {
         return connections.get(jobId);
      }
      else {
         throw new RuntimeException("No RCConnection exists to handle message with jobid: " + jobId);
      }
   }

   // -- SignalingClientListener events for incoming messages from signaling thread
   // Replies

   public void onOpenReply(String jobId, RCDeviceListener.RCConnectivityStatus connectivityStatus, RCClient.ErrorCodes status, String text)
   {
      RCLogger.i(TAG, "onOpenReply(): id: " + jobId + ", connectivityStatus: " + connectivityStatus + ", status: " + status + ", text: " + text);
      cachedConnectivityStatus = connectivityStatus;
      if (status != RCClient.ErrorCodes.SUCCESS) {
         if (isServiceAttached) {
            listener.onInitialized(this, connectivityStatus, status.ordinal(), text);
         }
         else {
            RCLogger.w(TAG, "RCDeviceListener event suppressed since Restcomm Client Service not attached: onInitialized(): " +
                  RCClient.errorText(status));
         }
         return;
      }

      state = DeviceState.READY;
      if (isServiceAttached) {
         listener.onInitialized(this, connectivityStatus, RCClient.ErrorCodes.SUCCESS.ordinal(), RCClient.errorText(RCClient.ErrorCodes.SUCCESS));
      }
      else {
         RCLogger.w(TAG, "RCDeviceListener event suppressed since Restcomm Client Service not attached: onInitialized(): " +
               RCClient.errorText(status));
      }

   }

   public void onCloseReply(String jobId, RCClient.ErrorCodes status, String text)
   {
      RCLogger.i(TAG, "onCloseReply(): id: " + jobId + ", status: " + status + ", text: " + text);

      if (status == RCClient.ErrorCodes.SUCCESS) {
         // TODO: notify App that device is closed
      }
      else {
      }

      // Shut down the service
      stopSelf();
   }

   public void onReconfigureReply(String jobId, RCDeviceListener.RCConnectivityStatus connectivityStatus, RCClient.ErrorCodes status, String text)
   {
      RCLogger.i(TAG, "onReconfigureReply(): id: " + jobId + ", connectivityStatus: " + connectivityStatus + ", status: " + status + ", text: " + text);
      cachedConnectivityStatus = connectivityStatus;
      if (status == RCClient.ErrorCodes.SUCCESS) {
         state = DeviceState.READY;
         if (isServiceAttached) {
            listener.onStartListening(this, connectivityStatus);
         }
         else {
            RCLogger.w(TAG, "RCDeviceListener event suppressed since Restcomm Client Service not attached: onStartListening(): " +
                  RCClient.errorText(status));
         }

      }
      else {
         state = DeviceState.OFFLINE;
         if (isServiceAttached) {
            listener.onStopListening(this, status.ordinal(), text);
         }
         else {
            RCLogger.w(TAG, "RCDeviceListener event suppressed since Restcomm Client Service not attached: isServiceAttached(): " +
                  RCClient.errorText(status));
         }
      }
   }

   /*
   public void onCallReply(String jobId, RCClient.ErrorCodes status, String text)
   {

   }
   */

   public void onMessageReply(String jobId, RCClient.ErrorCodes status, String text)
   {
      RCLogger.i(TAG, "onMessageReply(): id: " + jobId + ", status: " + status + ", text: " + text);

      if (isServiceAttached) {
         listener.onMessageSent(this, status.ordinal(), text);
      }
      else {
         RCLogger.w(TAG, "RCDeviceListener event suppressed since Restcomm Client Service not attached: onMessageSent(): " +
               RCClient.errorText(status));
      }

   }

   // Unsolicited Events
   public void onCallArrivedEvent(String jobId, String peer, String sdpOffer, HashMap<String, String> customHeaders)
   {
      RCLogger.i(TAG, "onCallArrivedEvent(): id: " + jobId + ", peer: " + peer);

      RCConnection connection = new RCConnection.Builder(true, RCConnection.ConnectionState.CONNECTING, this, signalingClient, audioManager)
            .jobId(jobId)
            .incomingCallSdp(sdpOffer)
            .peer(peer)
            .build();

      // keep connection in the connections hashmap
      connections.put(jobId, connection);

      state = DeviceState.BUSY;

      if (isServiceAttached) {
         // Service is attached to an activity, let's send the intent normally that will open the call activity
         audioManager.playRingingSound();
         try {
            Intent dataIntent = new Intent();
            dataIntent.setAction(ACTION_INCOMING_CALL);
            dataIntent.putExtra(RCDevice.EXTRA_DID, peer);
            dataIntent.putExtra(RCDevice.EXTRA_VIDEO_ENABLED, (connection.getRemoteMediaType() == RCConnection.ConnectionMediaType.AUDIO_VIDEO));
            if (customHeaders != null) {
               dataIntent.putExtra(RCDevice.EXTRA_CUSTOM_HEADERS, customHeaders);
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, callIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            pendingIntent.send(getApplicationContext(), 0, dataIntent);

            // Phone state Intents to capture incoming phone call event
            sendQoSIncomingConnectionIntent(peer, connection);
         }
         catch (PendingIntent.CanceledException e) {
            e.printStackTrace();
         }
      }
      else {
         String text;
         if (connection.getRemoteMediaType() == RCConnection.ConnectionMediaType.AUDIO_VIDEO) {
            text = "Incoming video call";
         }
         else {
            text = "Incoming audio call";
         }

         // Intent to open the call activity (for when tapping on the general notification area)
         Intent serviceIntentDefault = new Intent(ACTION_NOTIFICATION_CALL_DEFAULT, null, getApplicationContext(), RCDevice.class);
         // Intent to directly answer the call as video
         Intent serviceIntentVideo = new Intent(ACTION_NOTIFICATION_CALL_ACCEPT_VIDEO, null, getApplicationContext(), RCDevice.class);
         // Intent to directly answer the call as audio
         Intent serviceIntentAudio = new Intent(ACTION_NOTIFICATION_CALL_ACCEPT_AUDIO, null, getApplicationContext(), RCDevice.class);
         // Intent to decline the call without opening the App Activity
         Intent serviceIntentDecline = new Intent(ACTION_NOTIFICATION_CALL_DECLINE, null, getApplicationContext(), RCDevice.class);

         // Service is not attached to an activity, let's use a notification instead
         NotificationCompat.Builder builder =
               new NotificationCompat.Builder(RCDevice.this)
                     .setSmallIcon(R.drawable.ic_call_24dp)
                     .setContentTitle(peer.replaceAll(".*?sip:", "").replaceAll("@.*$", ""))
                     .setContentText(text)
                     .setSound(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.ringing_sample)) // audioManager.getResourceIdForKey(ParameterKeys.RESOURCE_SOUND_RINGING)))
                     // Need this to show up as Heads-up Notification
                     .setPriority(NotificationCompat.PRIORITY_HIGH)
                     .setAutoCancel(true)  // cancel notification when user acts on it
                     .addAction(R.drawable.ic_videocam_24dp, "", PendingIntent.getService(getApplicationContext(), 0, serviceIntentVideo, PendingIntent.FLAG_ONE_SHOT))
                     .addAction(R.drawable.ic_call_24dp, "", PendingIntent.getService(getApplicationContext(), 0, serviceIntentAudio, PendingIntent.FLAG_ONE_SHOT))
                     .addAction(R.drawable.ic_call_end_24dp, "", PendingIntent.getService(getApplicationContext(), 0, serviceIntentDecline, PendingIntent.FLAG_ONE_SHOT))
                     .setContentIntent(PendingIntent.getService(getApplicationContext(), 0, serviceIntentDefault, PendingIntent.FLAG_ONE_SHOT));

         Notification notification = builder.build();
         // Add FLAG_INSISTENT so that the notification rings repeatedly (FLAG_INSISTENT is not exposed via builder, let's add manually)
         notification.flags = notification.flags | Notification.FLAG_INSISTENT;
         NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
         // mId allows you to update the notification later on.
         notificationManager.notify(NOTIFICATION_ID_CALL, notification);
      }
   }

   void handleNotification(Intent intent)
   {
      // The user has acted on the notification, let's cancel it
      NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
      notificationManager.cancel(NOTIFICATION_ID_CALL);

      String intentAction = intent.getAction();
      if (intentAction.equals(ACTION_NOTIFICATION_CALL_DEFAULT)) {
         callIntent.setAction(ACTION_INCOMING_CALL);
         callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
         startActivity(callIntent);
      }
      else if (intentAction.equals(ACTION_NOTIFICATION_CALL_ACCEPT_VIDEO)) {
         callIntent.setAction(ACTION_INCOMING_CALL_ANSWER_VIDEO);
         callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
         startActivity(callIntent);
      }
      else if (intentAction.equals(ACTION_NOTIFICATION_CALL_ACCEPT_AUDIO)) {
         callIntent.setAction(ACTION_INCOMING_CALL_ANSWER_AUDIO);
         callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
         startActivity(callIntent);
      }
      else if (intentAction.equals(ACTION_NOTIFICATION_CALL_DECLINE)) {
         RCConnection pendingConnection = getPendingConnection();
         if (pendingConnection != null) {
            pendingConnection.reject();
         }
      }
      else if (intentAction.equals(ACTION_NOTIFICATION_MESSAGE_DEFAULT)) {
         messageIntent.setAction(ACTION_INCOMING_MESSAGE);
         messageIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
         startActivity(messageIntent);
      }
   }


   public void onRegisteringEvent(String jobId)
   {
      RCLogger.i(TAG, "onRegisteringEvent(): id: " + jobId);
      state = DeviceState.OFFLINE;
      if (isServiceAttached) {
         listener.onStopListening(this, RCClient.ErrorCodes.SUCCESS.ordinal(), "Trying to register with Service");
      }
      else {
         RCLogger.w(TAG, "RCDeviceListener event suppressed since Restcomm Client Service not attached: onStopListening()");
      }

   }


   public void onMessageArrivedEvent(String jobId, String peer, String messageText)
   {
      RCLogger.i(TAG, "onMessageArrivedEvent(): id: " + jobId + ", peer: " + peer + ", text: " + messageText);

      audioManager.playMessageSound();
      HashMap<String, String> parameters = new HashMap<String, String>();
      // filter out SIP URI stuff and leave just the name
      String from = peer.replaceAll("^<", "").replaceAll(">$", "");
      //parameters.put(RCConnection.ParameterKeys.CONNECTION_PEER, from);

      if (isServiceAttached) {
         try {
            Intent dataIntent = new Intent();
            dataIntent.setAction(ACTION_INCOMING_MESSAGE);
            //dataIntent.putExtra(INCOMING_MESSAGE_PARAMS, parameters);
            dataIntent.putExtra(EXTRA_DID, from);
            dataIntent.putExtra(EXTRA_MESSAGE_TEXT, messageText);

            PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, messageIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            pendingIntent.send(getApplicationContext(), 0, dataIntent);
         }
         catch (PendingIntent.CanceledException e) {
            e.printStackTrace();
         }
      }
      else {
         // Intent to open the call activity (for when tapping on the general notification area)
         Intent serviceIntentDefault = new Intent(ACTION_NOTIFICATION_MESSAGE_DEFAULT, null, getApplicationContext(), RCDevice.class);
         serviceIntentDefault.putExtra(EXTRA_MESSAGE_TEXT, messageText);

         // Service is not attached to an activity, let's use a notification instead
         NotificationCompat.Builder builder =
               new NotificationCompat.Builder(RCDevice.this)
                     .setSmallIcon(R.drawable.ic_chat_24dp)
                     .setContentTitle(peer.replaceAll(".*?sip:", "").replaceAll("@.*$", ""))
                     .setContentText(messageText)
                     .setSound(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.message_sample)) // audioManager.getResourceIdForKey(ParameterKeys.RESOURCE_SOUND_RINGING)))
                     // Need this to show up as Heads-up Notification
                     .setPriority(NotificationCompat.PRIORITY_HIGH)
                     .setAutoCancel(true)  // cancel notification when user acts on it
                     .setContentIntent(PendingIntent.getService(getApplicationContext(), 0, serviceIntentDefault, PendingIntent.FLAG_ONE_SHOT));

         Notification notification = builder.build();
         // Add FLAG_INSISTENT so that the notification rings repeatedly (FLAG_INSISTENT is not exposed via builder, let's add manually)
         //notification.flags = notification.flags | Notification.FLAG_INSISTENT;
         NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
         // mId allows you to update the notification later on.
         notificationManager.notify(NOTIFICATION_ID_MESSAGE, notification);
      }
   }

   public void onErrorEvent(String jobId, RCDeviceListener.RCConnectivityStatus connectivityStatus, RCClient.ErrorCodes status, String text)
   {
      RCLogger.e(TAG, "onErrorEvent(): id: " + jobId + ", connectivityStatus: " + connectivityStatus + ", status: " + status + ", text: " + text);
      cachedConnectivityStatus = connectivityStatus;
      if (status == RCClient.ErrorCodes.SUCCESS) {
      }
      else {
         if (isServiceAttached) {
            listener.onStopListening(this, status.ordinal(), text);
         }
         else {
            RCLogger.w(TAG, "RCDeviceListener event suppressed since Restcomm Client Service not attached: onStopListening(): " +
                  RCClient.errorText(status));
         }

      }
   }

   public void onConnectivityEvent(String jobId, RCDeviceListener.RCConnectivityStatus connectivityStatus)
   {
      RCLogger.i(TAG, "onConnectivityEvent(): id: " + jobId + ", connectivityStatus: " + connectivityStatus);
      cachedConnectivityStatus = connectivityStatus;
      if (state == DeviceState.OFFLINE && connectivityStatus != RCDeviceListener.RCConnectivityStatus.RCConnectivityStatusNone) {
         state = DeviceState.READY;
      }
      if (state != DeviceState.OFFLINE && connectivityStatus == RCDeviceListener.RCConnectivityStatus.RCConnectivityStatusNone) {
         state = DeviceState.OFFLINE;
      }
      if (isServiceAttached) {
         listener.onConnectivityUpdate(this, connectivityStatus);
      }
      else {
         RCLogger.w(TAG, "RCDeviceListener event suppressed since Restcomm Client Service not attached: onConnectivityUpdate(): " +
               connectivityStatus);
      }

   }

   // ------ Helpers

   // -- Notify QoS module of Device related event through intents, if the module is available
   // Phone state Intents to capture incoming call event
   private void sendQoSIncomingConnectionIntent(String user, RCConnection connection)
   {
      Intent intent = new Intent("org.restcomm.android.CALL_STATE");
      intent.putExtra("STATE", "ringing");
      intent.putExtra("INCOMING", true);
      intent.putExtra("FROM", user);
      try {
         // Restrict the Intent to MMC Handler running within the same application
         Class aclass = Class.forName("com.cortxt.app.corelib.Services.Intents.IntentHandler");
         intent.setClass(getApplicationContext(), aclass);
         getApplicationContext().sendBroadcast(intent);
      }
      catch (ClassNotFoundException e) {
         // If there is no MMC class isn't here, no intent
      }
   }

   private void sendQoSNoConnectionIntent(String user, String message)
   {
      Intent intent = new Intent("org.restcomm.android.CONNECT_FAILED");
      intent.putExtra("STATE", "connect failed");
      intent.putExtra("ERRORTEXT", message);
      intent.putExtra("ERROR", RCClient.ErrorCodes.ERROR_DEVICE_NO_CONNECTIVITY);
      intent.putExtra("INCOMING", false);
      intent.putExtra("USER", user);
      try {
         // Restrict the Intent to MMC Handler running within the same application
         Class aclass = Class.forName("com.cortxt.app.corelib.Services.Intents.IntentHandler");
         intent.setClass(getApplicationContext(), aclass);
         getApplicationContext().sendBroadcast(intent);
      }
      catch (ClassNotFoundException e) {
         // If there is no MMC class isn't here, no intent
      }
   }

   void removeConnection(String jobId)
   {
      RCLogger.i(TAG, "removeConnection(): id: " + jobId + ", total connections before removal: " + connections.size());
      connections.remove(jobId);
   }

   private void onAudioManagerChangedState()
   {
      // TODO(henrika): disable video if AppRTCAudioManager.AudioDevice.EARPIECE
      // is active.
   }
}