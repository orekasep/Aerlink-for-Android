package com.codegy.aerlink;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.*;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.util.Log;
import com.codegy.aerlink.battery.BASConstants;
import com.codegy.aerlink.battery.BatteryServiceHandler;
import com.codegy.aerlink.cameraremote.CameraRemoteServiceHandler;
import com.codegy.aerlink.connection.*;
import com.codegy.aerlink.currenttime.CTSConstants;
import com.codegy.aerlink.currenttime.CurrentTimeServiceHandler;
import com.codegy.aerlink.media.AMSConstants;
import com.codegy.aerlink.media.MediaServiceHandler;
import com.codegy.aerlink.notifications.ANCSConstants;
import com.codegy.aerlink.notifications.NotificationServiceHandler;
import com.codegy.aerlink.reminders.ReminderServiceHandler;
import com.codegy.aerlink.utils.ServiceHandler;
import com.codegy.aerlink.utils.ServiceUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


public class MainService extends Service implements ServiceUtils, ConnectionHandlerCallback {

    private static final String LOG_TAG = MainService.class.getSimpleName();

    private static final long SCREEN_TIME_OUT = 1000;


    private IBinder mBinder = new ServiceBinder();

    private ConnectionHelper connectionHelper;
    private ConnectionHandler connectionHandler;
    private NotificationManager notificationManager;

    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;

    private boolean colorBackgrounds;

    private List<ServiceHandler> mServiceHandlers;


    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(LOG_TAG, "onCreate ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        colorBackgrounds = sp.getBoolean(Constants.SPK_COLOR_BACKGROUNDS, true);


        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.IA_TRY_CONNECTING);
        intentFilter.addAction(Constants.IA_COLOR_BACKGROUNDS_CHANGED);
        registerReceiver(mBroadcastReceiver, intentFilter);


        start();
    }

    @Override
    public void onDestroy() {
        Log.d(LOG_TAG, "~~~~~~~~ service onDestroy");

        unregisterReceiver(mBroadcastReceiver);

        stop();

        getNotificationManager().cancelAll();

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.v(LOG_TAG, "in onBind");
        return mBinder;
    }


    private void start() {
        // Just in case, try to close everything
        stop();

        mServiceHandlers = new ArrayList<>();

        connectionHandler = new ConnectionHandler(this, this);
    }

    private void stop() {
        try {
            if (mServiceHandlers != null) {
                for (ServiceHandler serviceHandler : mServiceHandlers) {
                    serviceHandler.close();
                }

                mServiceHandlers.clear();
                mServiceHandlers = null;
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }


        try {
            if (connectionHandler != null) {
                connectionHandler.close();
                connectionHandler = null;
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean isConnectionReady() {
        return (connectionHandler != null && connectionHandler.getState() == ConnectionHandler.ConnectionState.Ready);
    }

    public ServiceHandler getServiceHandler(Class serviceHandlerClass) {
        ServiceHandler serviceHandler = null;

        if (mServiceHandlers != null) {
            for (ServiceHandler handler : mServiceHandlers) {
                if (handler.getClass().equals(serviceHandlerClass)) {
                    serviceHandler = handler;
                    break;
                }
            }
        }

        return serviceHandler;
    }


    private NotificationManager getNotificationManager() {
        if (notificationManager == null) {
            notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        }

        return notificationManager;
    }

    @Override
    public void addCommandToQueue(Command command) {
        if (connectionHandler != null) {
            connectionHandler.addCommandToQueue(command);
        }
    }

    @Override
    public void notify(String tag, int id, Notification notification) {
        getNotificationManager().notify(tag, id, notification);
    }

    @Override
    public void cancelNotification(String tag, int id) {
        getNotificationManager().cancel(tag, id);
    }

    @Override
    public void vibrate(long[] pattern, int repeat) {
        if (vibrator == null) {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        }

        vibrator.vibrate(pattern, repeat);
    }

    @Override
    public void wakeScreen() {
        if (wakeLock == null) {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = powerManager.newWakeLock((PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP), "Aerlink_TAG");
        }

        if (!wakeLock.isHeld()) {
            Log.i(LOG_TAG, "Waking Screen");
            wakeLock.acquire(SCREEN_TIME_OUT);
        }
    }

    @Override
    public boolean getColorBackgrounds() {
        return colorBackgrounds;
    }


    @Override
    public void onConnectionStateChange(ConnectionHandler.ConnectionState state) {
        if (state == null || state == ConnectionHandler.ConnectionState.NoBluetooth) {
            connectionHandler = null;

            stop();
        }

        if (state != ConnectionHandler.ConnectionState.Ready) {
            Intent stateIntent = new Intent(Constants.IA_SERVICE_NOT_READY);
            sendBroadcast(stateIntent);
        }
        else {
            Intent stateIntent = new Intent(Constants.IA_SERVICE_READY);
            sendBroadcast(stateIntent);
        }

        if (connectionHelper == null) {
            connectionHelper = new ConnectionHelper(this, this);
        }

        connectionHelper.showHelpForState(state);
    }

    @Override
    public void onReadyToSubscribe(BluetoothGatt bluetoothGatt) {
        Log.i(LOG_TAG, "Ready to Subscribe");
        for (ServiceHandler handler : mServiceHandlers) {
            handler.reset();
        }


        BluetoothGattService notificationService = bluetoothGatt.getService(ANCSConstants.SERVICE_UUID);
        if (notificationService != null) {
            if (getServiceHandler(NotificationServiceHandler.class) == null) {
                mServiceHandlers.add(new NotificationServiceHandler(this, this));
            }
        }
        BluetoothGattService mediaService = bluetoothGatt.getService(AMSConstants.SERVICE_UUID);
        if (mediaService != null) {
            if (getServiceHandler(MediaServiceHandler.class) == null) {
                mServiceHandlers.add(new MediaServiceHandler(this, this));
            }

            Command trackCommand = new Command(AMSConstants.SERVICE_UUID, AMSConstants.CHARACTERISTIC_ENTITY_UPDATE, new byte[] {
                    AMSConstants.EntityIDTrack,
                    AMSConstants.TrackAttributeIDTitle,
                    AMSConstants.TrackAttributeIDArtist
            });
            trackCommand.setImportance(Command.IMPORTANCE_MAX);

            Command playerCommand = new Command(AMSConstants.SERVICE_UUID, AMSConstants.CHARACTERISTIC_ENTITY_UPDATE, new byte[] {
                    AMSConstants.EntityIDPlayer,
                    AMSConstants.PlayerAttributeIDPlaybackInfo
            });
            playerCommand.setImportance(Command.IMPORTANCE_MAX);


            connectionHandler.addCommandToQueue(trackCommand);
            connectionHandler.addCommandToQueue(playerCommand);
        }
        BluetoothGattService batteryService = bluetoothGatt.getService(BASConstants.SERVICE_UUID);
        if (batteryService != null) {
            if (getServiceHandler(BatteryServiceHandler.class) == null) {
                mServiceHandlers.add(new BatteryServiceHandler(this, this));
            }
        }
        BluetoothGattService currentTimeService = bluetoothGatt.getService(CTSConstants.SERVICE_UUID);
        if (currentTimeService != null) {
            if (getServiceHandler(CurrentTimeServiceHandler.class) == null) {
                mServiceHandlers.add(new CurrentTimeServiceHandler(this, this));
            }
        }
        BluetoothGattService aerlinkService = bluetoothGatt.getService(ALSConstants.SERVICE_UUID);
        if (aerlinkService != null) {
            if (getServiceHandler(ReminderServiceHandler.class) == null) {
                mServiceHandlers.add(new ReminderServiceHandler(this, this));
            }
            if (getServiceHandler(CameraRemoteServiceHandler.class) == null) {
                mServiceHandlers.add(new CameraRemoteServiceHandler(this, this));
            }
        }

        List<CharacteristicIdentifier> requests = new ArrayList<>();

        for (ServiceHandler serviceHandler : mServiceHandlers) {
            UUID serviceUUID = serviceHandler.getServiceUUID();
            List<String> characteristics = serviceHandler.getCharacteristicsToSubscribe();

            if (serviceUUID != null && characteristics != null) {
                Log.i(LOG_TAG, "Adding characteristics: " + serviceUUID.toString());

                for (String characteristic : characteristics) {
                    requests.add(new CharacteristicIdentifier(serviceUUID, characteristic));
                }
            }
        }

        connectionHandler.addSubscribeRequests(requests);
    }

    @Override
    public void onCharacteristicChanged(BluetoothGattCharacteristic characteristic) {
        if (mServiceHandlers != null) {
            for (ServiceHandler serviceHandler : mServiceHandlers) {
                if (serviceHandler.canHandleCharacteristic(characteristic)) {
                    serviceHandler.handleCharacteristic(characteristic);
                    break;
                }
            }
        }
    }
    

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            
            if (action.equals(Constants.IA_COLOR_BACKGROUNDS_CHANGED)) {
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
                colorBackgrounds = sp.getBoolean(Constants.SPK_COLOR_BACKGROUNDS, true);
            }
            else if (action.equals(Constants.IA_TRY_CONNECTING)) {
                start();
            }
        }

    };

    public class ServiceBinder extends Binder {
        public MainService getService() {
            return MainService.this;
        }
    }

}
