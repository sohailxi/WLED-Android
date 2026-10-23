package ca.cgagnier.wlednativeandroid.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.RemoteViews
import ca.cgagnier.wlednativeandroid.R
import ca.cgagnier.wlednativeandroid.model.Device
import ca.cgagnier.wlednativeandroid.model.wledapi.State
import ca.cgagnier.wlednativeandroid.repository.DeviceRepository
import ca.cgagnier.wlednativeandroid.service.api.DeviceApiFactory
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import retrofit2.Response
import javax.inject.Inject

@AndroidEntryPoint
open class PresetWidgetProvider : AppWidgetProvider() {

    @Inject
    lateinit var deviceApiFactory: DeviceApiFactory

    @Inject
    lateinit var deviceRepository: DeviceRepository

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface PresetWidgetProviderEntryPoint {
        fun deviceRepository(): DeviceRepository
    }

    private data class TriggerParams(
        val context: Context,
        val deviceAddress: String?,
        val macAddress: String?,
        val presetId: Int,
        val appWidgetId: Int,
        val listId: Int,
    )

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TRIGGER_PRESET) {
            val appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            val presetId = intent.getIntExtra(EXTRA_PRESET_ID, -1)
            val deviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
            val macAddress = intent.getStringExtra(EXTRA_MAC_ADDRESS)
            val listId = intent.getIntExtra(EXTRA_LIST_ID, R.id.preset_list)

            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && presetId != -1) {
                val params = TriggerParams(
                    context = context,
                    deviceAddress = deviceAddress,
                    macAddress = macAddress,
                    presetId = presetId,
                    appWidgetId = appWidgetId,
                    listId = listId,
                )
                triggerPreset(params)
            }
        }

        if (intent.action == ACTION_REFRESH) {
            val appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            val listId = intent.getIntExtra(EXTRA_LIST_ID, R.id.preset_list)
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                PresetWidgetRemoteViewsFactory.forceRefresh(appWidgetId)
                val appWidgetManager = AppWidgetManager.getInstance(context)
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, listId)
            }
        }
    }

    private fun triggerPreset(params: TriggerParams) {
        Log.i(
            TAG,
            "triggerPreset: [Widget ${params.appWidgetId}] Triggering preset ${params.presetId}. " +
                "Initial address: ${params.deviceAddress}, MAC: ${params.macAddress}",
        )
        CoroutineScope(Dispatchers.IO).launch {
            try {
                var targetAddress = resolveAddress(params.deviceAddress, params.macAddress)
                if (targetAddress.isNullOrEmpty()) {
                    Log.e(TAG, "triggerPreset: [Widget ${params.appWidgetId}] Target address is null or empty")
                    return@launch
                }

                var response = sendPresetState(targetAddress, params.presetId, params.appWidgetId, 1)

                if (response == null || !response.isSuccessful) {
                    val refreshedAddress = resolveAddress(null, params.macAddress)
                    if (!refreshedAddress.isNullOrEmpty()) {
                        targetAddress = refreshedAddress
                    }
                    response = sendPresetState(targetAddress, params.presetId, params.appWidgetId, 2)
                }

                if (response == null || !response.isSuccessful) {
                    response = sendPresetState(
                        targetAddress = targetAddress,
                        presetId = params.presetId,
                        appWidgetId = params.appWidgetId,
                        attempt = 3,
                        timeoutSeconds = EXTENDED_TIMEOUT_SECONDS,
                    )
                }

                if (response != null && response.isSuccessful) {
                    onPresetTriggerSuccess(params)
                } else {
                    Log.e(
                        TAG,
                        "triggerPreset: [Widget ${params.appWidgetId}] " +
                            "Failed after all retries. Code: ${response?.code()}",
                    )
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.e(TAG, "triggerPreset: [Widget ${params.appWidgetId}] Critical error", e)
            }
        }
    }

    private suspend fun resolveAddress(deviceAddress: String?, macAddress: String?): String? {
        if (!macAddress.isNullOrEmpty()) {
            val device = deviceRepository.findDeviceByMacAddress(macAddress)
            if (device != null) {
                return device.address
            }
        }
        return deviceAddress
    }

    private suspend fun sendPresetState(
        targetAddress: String,
        presetId: Int,
        appWidgetId: Int,
        attempt: Int,
        timeoutSeconds: Long? = null,
    ): Response<State>? {
        val api = if (timeoutSeconds != null) {
            deviceApiFactory.create(targetAddress, timeoutSeconds)
        } else {
            deviceApiFactory.create(targetAddress)
        }
        return try {
            Log.d(TAG, "triggerPreset: [Widget $appWidgetId] Attempt $attempt at $targetAddress")
            api.postState(State(selectedPresetId = presetId, isOn = true))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "triggerPreset: [Widget $appWidgetId] Attempt $attempt failed: ${e.message}")
            null
        }
    }

    private fun onPresetTriggerSuccess(params: TriggerParams) {
        Log.i(TAG, "triggerPreset: [Widget ${params.appWidgetId}] Success for preset ${params.presetId}")
        val prefs = params.context.getSharedPreferences(
            PREFS_CACHE_NAME,
            Context.MODE_PRIVATE,
        ).edit()
        prefs.putInt(PREF_CACHE_SELECTED_PREFIX + params.appWidgetId, params.presetId)
        prefs.apply()

        PresetWidgetRemoteViewsFactory.forceRefresh(params.appWidgetId)

        val appWidgetManager = AppWidgetManager.getInstance(params.context)
        appWidgetManager.notifyAppWidgetViewDataChanged(params.appWidgetId, params.listId)
    }

    companion object {
        const val ACTION_TRIGGER_PRESET = "ca.cgagnier.wlednativeandroid.widget.ACTION_TRIGGER_PRESET"
        const val ACTION_REFRESH = "ca.cgagnier.wlednativeandroid.widget.ACTION_REFRESH"
        const val EXTRA_PRESET_ID = "ca.cgagnier.wlednativeandroid.widget.EXTRA_PRESET_ID"
        const val EXTRA_DEVICE_ADDRESS = "ca.cgagnier.wlednativeandroid.widget.EXTRA_DEVICE_ADDRESS"
        const val EXTRA_MAC_ADDRESS = "ca.cgagnier.wlednativeandroid.widget.EXTRA_MAC_ADDRESS"
        const val EXTRA_LIST_LIMIT = "ca.cgagnier.wlednativeandroid.widget.EXTRA_LIST_LIMIT"
        const val EXTRA_LAYOUT_ID = "ca.cgagnier.wlednativeandroid.widget.EXTRA_LAYOUT_ID"
        const val EXTRA_LIST_ID = "ca.cgagnier.wlednativeandroid.widget.EXTRA_LIST_ID"
        private const val TAG = "PresetWidgetProvider"
        private const val EXTENDED_TIMEOUT_SECONDS = 15L
        private const val PREFS_CACHE_NAME = "ca.cgagnier.wlednativeandroid.widget.PresetWidgetCache"
        private const val PREF_CACHE_SELECTED_PREFIX = "cache_selected_id_"

        @Suppress("LongParameterList")
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            limit: Int = 0,
            layoutId: Int = R.layout.widget_preset,
            listId: Int = R.id.preset_list,
            itemLayoutId: Int = R.layout.widget_preset_item,
            titleViewId: Int? = R.id.appwidget_text,
            providerClass: Class<out AppWidgetProvider> = PresetWidgetProvider::class.java,
        ) {
            val (deviceName, deviceAddress) = resolveConfiguredDevice(context, appWidgetId)
            val views = RemoteViews(context.packageName, layoutId)

            val configureIntent = Intent(context, PresetWidgetConfigureActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            val configurePendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                configureIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            if (deviceAddress == null) {
                titleViewId?.let { views.setTextViewText(it, context.getString(R.string.widget_please_configure)) }
                views.setEmptyView(listId, R.id.empty_view)
                views.setOnClickPendingIntent(R.id.empty_view, configurePendingIntent)
                titleViewId?.let { views.setOnClickPendingIntent(it, configurePendingIntent) }
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }

            titleViewId?.let {
                views.setTextViewText(it, deviceName)
                views.setOnClickPendingIntent(it, configurePendingIntent)
            }

            val intent = Intent(context, PresetWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(EXTRA_LIST_LIMIT, limit)
                putExtra(EXTRA_LAYOUT_ID, itemLayoutId)
                putExtra(EXTRA_LIST_ID, listId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(listId, intent)
            views.setEmptyView(listId, R.id.empty_view)

            setupWidgetIntents(context, views, appWidgetId, listId, providerClass)

            appWidgetManager.updateAppWidget(appWidgetId, views)
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, listId)
        }

        private fun resolveConfiguredDevice(context: Context, appWidgetId: Int): Pair<String, String?> {
            var deviceName = PresetWidgetConfigureActivity.loadDeviceName(context, appWidgetId)
            var deviceAddress = PresetWidgetConfigureActivity.loadDeviceAddress(context, appWidgetId)
            val deviceMac = PresetWidgetConfigureActivity.loadDeviceMacAddress(context, appWidgetId)

            if (!deviceMac.isNullOrEmpty()) {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context,
                    PresetWidgetProviderEntryPoint::class.java,
                )
                val deviceRepository = entryPoint.deviceRepository()
                runBlocking {
                    val device = deviceRepository.findDeviceByMacAddress(deviceMac)
                    if (device != null) {
                        if (device.address != deviceAddress || device.name != deviceName) {
                            Log.d(TAG, "Device details changed for $deviceMac")
                            deviceName = device.name
                            deviceAddress = device.address
                            PresetWidgetConfigureActivity.saveDevicePref(context, appWidgetId, device)
                        }
                    }
                }
            }
            return Pair(deviceName, deviceAddress)
        }

        private fun setupWidgetIntents(
            context: Context,
            views: RemoteViews,
            appWidgetId: Int,
            listId: Int,
            providerClass: Class<out AppWidgetProvider>,
        ) {
            val refreshIntent = Intent(context, providerClass).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(EXTRA_LIST_ID, listId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            views.setOnClickPendingIntent(R.id.empty_view, refreshPendingIntent)

            val toastIntent = Intent(context, providerClass).apply {
                action = ACTION_TRIGGER_PRESET
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(EXTRA_LIST_ID, listId)
                data = Uri.parse("widget://preset/trigger/$appWidgetId")
            }
            val toastPendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                toastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            views.setPendingIntentTemplate(listId, toastPendingIntent)
        }
    }
}
