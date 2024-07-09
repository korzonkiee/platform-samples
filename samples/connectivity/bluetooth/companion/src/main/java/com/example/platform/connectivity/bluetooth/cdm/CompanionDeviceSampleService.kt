/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.platform.connectivity.bluetooth.cdm

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.IconCompat
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrBroadcastData
import io.reactivex.rxjava3.disposables.Disposable

@RequiresApi(Build.VERSION_CODES.S)
class CompanionDeviceSampleService : CompanionDeviceService() {
    companion object {
        private const val TAG = "CompanionDeviceSampleService"
        private const val POLAR_TAG = "PolarSDK"
    }
    
    private val notificationManager: DeviceNotificationManager by lazy {
        DeviceNotificationManager(applicationContext)
    }

    private val bluetoothManager: BluetoothManager by lazy {
        applicationContext.getSystemService()!!
    }

    private var broadcastDisposable: Disposable? = null

    private val api: PolarBleApi by lazy {
        val impl = PolarBleApiDefaultImpl.defaultImplementation(applicationContext,
            setOf(PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_SDK_MODE,
                PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_H10_EXERCISE_RECORDING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_OFFLINE_RECORDING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP,
                PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO)
        )

        impl.setApiCallback(object : PolarBleApiCallback() {
            override fun bleSdkFeatureReady(
                identifier: String,
                feature: PolarBleApi.PolarBleSdkFeature
            ) {
                Log.d(TAG, "bleSdkFeatureReady")
            }

            override fun disInformationReceived(identifier: String, disInfo: DisInfo) {
                Log.d(TAG, "disInformationReceived")
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "onDeviceConnected")
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "deviceDisconnected")
            }
        })

        impl.setApiLogger { s: String -> Log.d(POLAR_TAG, s) }

        impl
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        super.onDeviceAppeared(associationInfo)

        Log.d(TAG, "onDeviceAppeared")

        if (missingPermissions()) {
            return
        }

        val address = associationInfo.deviceMacAddress?.toString() ?: return


        notificationManager.onDeviceAppeared(
            address = address,
            status = "status",
        )

        try {
            api.connectToDevice(address)
        } catch (polarInvalidArgument: PolarInvalidArgument) {
            Log.e(TAG, "Failed to connect to polar device $address. Reason $polarInvalidArgument")
        }

        broadcastDisposable?.dispose()
        broadcastDisposable = api.startListenForPolarHrBroadcasts(null)
            .subscribe(
                { polarBroadcastData: PolarHrBroadcastData ->
                    Log.d(TAG, "HR BROADCAST ${polarBroadcastData.polarDeviceInfo.deviceId} HR: ${polarBroadcastData.hr} batt: ${polarBroadcastData.batteryStatus}")
                },
                { error: Throwable ->
                    Log.e(TAG, "Broadcast listener failed. Reason $error")
                },
                { Log.d(TAG, "complete") }
            )
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        super.onDeviceDisappeared(associationInfo)

        Log.d(TAG, "onDeviceDisappeared")

        if (missingPermissions()) {
            return
        }

        notificationManager.onDeviceDisappeared(
            address = associationInfo.deviceMacAddress?.toString() ?: return,
        )

        val macAddress = associationInfo.deviceMacAddress?.toString()
        if (macAddress != null) {
            api.disconnectFromDevice(macAddress)
            broadcastDisposable?.dispose()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        super.onDeviceAppeared(address)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || missingPermissions()) {
            return
        }

        val device = bluetoothManager.adapter.getRemoteDevice(address)
        val status = bluetoothManager.getConnectionState(device, BluetoothProfile.GATT)
        notificationManager.onDeviceAppeared(address, status.toString())
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        super.onDeviceDisappeared(address)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || missingPermissions()) {
            return
        }

        notificationManager.onDeviceDisappeared(address)
    }

    /**
     * Check BLUETOOTH_CONNECT is granted and POST_NOTIFICATIONS is granted for devices running
     * Android 13 and above.
     */
    private fun missingPermissions(): Boolean = ActivityCompat.checkSelfPermission(
        this,
        Manifest.permission.BLUETOOTH_CONNECT,
    ) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED)

    /**
     * Utility class to post notification when CDM notifies that a device appears or disappears
     */
    private class DeviceNotificationManager(context: Context) {

        companion object {
            private const val CDM_CHANNEL = "cdm_channel"
        }

        private val manager = NotificationManagerCompat.from(context)

        private val notificationBuilder = NotificationCompat.Builder(context, CDM_CHANNEL)
            .setSmallIcon(IconCompat.createWithResource(context, context.applicationInfo.icon))
            .setContentTitle("Companion Device Manager Sample")

        init {
            createNotificationChannel()
        }

        @SuppressLint("InlinedApi")
        @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
        fun onDeviceAppeared(address: String, status: String) {
            val notification =
                notificationBuilder.setContentText("Device: $address appeared.\nStatus: $status")
            manager.notify(address.hashCode(), notification.build())
        }

        @SuppressLint("InlinedApi")
        @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
        fun onDeviceDisappeared(address: String) {
            val notification = notificationBuilder.setContentText("Device: $address disappeared")
            manager.notify(address.hashCode(), notification.build())
        }

        private fun createNotificationChannel() {
            val channel =
                NotificationChannelCompat.Builder(CDM_CHANNEL, NotificationManager.IMPORTANCE_HIGH)
                    .setName("CDM Sample")
                    .setDescription("Channel for the CDM sample")
                    .build()
            manager.createNotificationChannel(channel)
        }
    }
}
