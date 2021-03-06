package com.example.iotmobileapp.workerservice;


import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.example.iotmobileapp.config.Config;
import com.example.iotmobileapp.config.ConfigProvider;
import com.example.iotmobileapp.workerservice.Database.ISharedDatabase;
import com.example.iotmobileapp.workerservice.Definitions.Kinematics;
import com.example.iotmobileapp.workerservice.Definitions.Scan;
import com.example.iotmobileapp.workerservice.Definitions.WifiDevice;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;

import java.util.ArrayList;
import java.util.List;

public class ScannerWorker implements Runnable
{
    private final ISharedDatabase<Scan> m_database;

    private final WifiManager m_wifiManager;

    private final BluetoothAdapter m_bluetoothAdapter;

    private final FusedLocationProviderClient m_locationClient;

    private final SensorManager m_sensorManager;

    private volatile Scan _scan;
    private volatile boolean _isWifiComplete = false;

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    public ScannerWorker(ISharedDatabase<Scan> _database,
                         WifiManager wifiManager,
                         BluetoothManager bluetoothManager,
                         FusedLocationProviderClient locationClient,
                         SensorManager sensorManager)
    {
        m_database = _database;
        m_wifiManager = wifiManager;
        m_bluetoothAdapter = bluetoothManager.getAdapter();
        m_locationClient = locationClient;
        m_sensorManager = sensorManager;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void run()

    {

        while(true)
        {
            m_wifiManager.setWifiEnabled(true);

            _scan = new Scan();
            _scan.kinematics = new Kinematics();

            m_sensorManager.registerListener(accelerometerListener,
                    m_sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                    SensorManager.SENSOR_DELAY_NORMAL);

            m_sensorManager.registerListener(orientationListener,
                    m_sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR),
                    SensorManager.SENSOR_DELAY_NORMAL);


            _scan.bluetoothDevices= new ArrayList<com.example.iotmobileapp.workerservice.Definitions.BluetoothDevice>();
            _isWifiComplete = false;

            if(m_wifiManager.startScan() && m_bluetoothAdapter.startDiscovery())
            {
                Task<Location> locTask = m_locationClient.getLastLocation();
                locTask.addOnSuccessListener(locationListener);

                try {
                    Thread.sleep(Config.ScanningTime.Value());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                m_bluetoothAdapter.cancelDiscovery();
                while(!_isWifiComplete){}

                _scan.Configuration = ConfigProvider.GetSettingModels();

                m_database.insert(_scan);

            }

            try
            {
                Thread.sleep(Config.ScannerSleepTime.Value());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }
    }

    BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @RequiresApi(api = Build.VERSION_CODES.M)
        @Override
        public void onReceive(Context context, Intent intent) {
            List<ScanResult> scanResults = m_wifiManager.getScanResults();

            ArrayList<WifiDevice> wifiScans = new ArrayList<>();
            for (ScanResult scanResult : scanResults)
            {
                WifiDevice wifiDevice= new WifiDevice();
                wifiDevice.Timestamp = scanResult.timestamp;
                wifiDevice.SSID = scanResult.SSID;
                wifiDevice.BSSID = scanResult.BSSID;
                wifiDevice.capabilities = scanResult.capabilities;
                wifiDevice.level = scanResult.level;
                wifiDevice.venueName = scanResult.venueName.toString();
                wifiDevice.operatorFriendlyName = scanResult.operatorFriendlyName.toString();
                wifiScans.add(wifiDevice);
            }

            _scan.wifiDevices = wifiScans;
            _isWifiComplete = true;
        }
    };

    BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                com.example.iotmobileapp.workerservice.Definitions.BluetoothDevice bluetoothDevice
                        = new com.example.iotmobileapp.workerservice.Definitions.BluetoothDevice();
                bluetoothDevice.Timestamp = System.currentTimeMillis();
                bluetoothDevice.Name = device.getName();
                bluetoothDevice.Type = "Type";
                bluetoothDevice.Address = device.getAddress();

                _scan.bluetoothDevices.add(bluetoothDevice);
            }
        }
    };

    SensorEventListener accelerometerListener = new SensorEventListener()
    {
        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {
            _scan.kinematics.acceleration_x = sensorEvent.values[0];
            _scan.kinematics.acceleration_y = sensorEvent.values[1];
            _scan.kinematics.acceleration_z = sensorEvent.values[2];
            m_sensorManager.unregisterListener(this);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {

        }
    };

    SensorEventListener orientationListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {

            _scan.kinematics.azimuth = sensorEvent.values[0];
            _scan.kinematics.pitch = sensorEvent.values[1];
            _scan.kinematics.roll = sensorEvent.values[2];

            m_sensorManager.unregisterListener(this);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {

        }
    };

    OnSuccessListener locationListener = new OnSuccessListener<Location>() {
    @Override
    public void onSuccess(Location location)
    {
        if(location!=null)
        {
            _scan.kinematics.timestamp = location.getTime();
            _scan.kinematics.altitude = location.getAltitude();
            _scan.kinematics.latitude = location.getLatitude();
            _scan.kinematics.longitude = location.getLongitude();
        }
    }
    };




}
