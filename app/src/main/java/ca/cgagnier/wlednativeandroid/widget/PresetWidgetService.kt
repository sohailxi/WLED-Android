package ca.cgagnier.wlednativeandroid.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import ca.cgagnier.wlednativeandroid.R
import ca.cgagnier.wlednativeandroid.model.wledapi.Preset
import ca.cgagnier.wlednativeandroid.service.api.DeviceApiFactory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

class PresetWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return PresetWidgetRemoteViewsFactory(this.applicationContext, intent)
    }
}

class PresetWidgetRemoteViewsFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val appWidgetId: Int = intent.getIntExtra(
        android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
        android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
    )
    private val limit: Int = intent.getIntExtra(PresetWidgetProvider.EXTRA_LIST_LIMIT, 0)
    private val itemLayoutId: Int = intent.getIntExtra(PresetWidgetProvider.EXTRA_LAYOUT_ID, R.layout.widget_preset_item)
    private var presets: List<Pair<String, Preset>> = emptyList()
    private val deviceAddress = PresetWidgetConfigureActivity.loadDeviceAddress(context, appWidgetId)

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PresetWidgetServiceEntryPoint {
        fun deviceApiFactory(): DeviceApiFactory
    }

    override fun onCreate() {
        // Data loading should ideally happen in onDataSetChanged
    }

    private var selectedPresetId: Int = -1

    override fun onDataSetChanged() {
        if (deviceAddress == null) {
            Log.e(TAG, "Device address is null")
            return
        }

        val entryPoint = EntryPointAccessors.fromApplication(
            context,
            PresetWidgetServiceEntryPoint::class.java
        )
        val deviceApiFactory = entryPoint.deviceApiFactory()

        runBlocking {
            try {
                Log.d(TAG, "Fetching presets for $deviceAddress")
                val api = deviceApiFactory.create(deviceAddress)
                
                // Fetch state to know selected preset
                val stateResponse = api.getState()
                if (stateResponse.isSuccessful) {
                    val state = stateResponse.body()
                    if (state != null) {
                        selectedPresetId = state.selectedPresetId ?: -1
                        // If playlist is active, maybe show that? 
                        // For now just preset.
                        if (selectedPresetId == -1 && state.selectedPlaylistId != null && state.selectedPlaylistId > 0) {
                             selectedPresetId = state.selectedPlaylistId
                        }
                    }
                }

                // Fetch Presets
                val response = api.getPresets()
                if (response.isSuccessful) {
                    val presetsMap = response.body()
                    Log.d(TAG, "Presets fetched: ${presetsMap?.size}")
                    if (presetsMap != null) {
                         // Filter out "0" if it exists, as it's usually not a real preset or handled differently?
                         // Actually presets.json keys are usually "1", "2", etc.
                         var sortedPresets = presetsMap.entries
                             .filter { it.key != "0" }
                             .map { it.key to it.value }
                             .sortedBy { it.first.toIntOrNull() ?: Int.MAX_VALUE }
                        
                        if (limit > 0) {
                            sortedPresets = sortedPresets.take(limit)
                        }
                        presets = sortedPresets
                        

                    }
                } else {
                    Log.e(TAG, "Error fetching presets: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching presets", e)
            }
        }
    }
    
    // ... (rest of the file)


    override fun onDestroy() {
        presets = emptyList()
    }

    override fun getCount(): Int {
        return presets.size
    }



    // ... (rest of class)

    override fun getViewAt(position: Int): RemoteViews {
        if (position >= presets.size) return RemoteViews(context.packageName, itemLayoutId)

        val (id, preset) = presets[position]
        val views = RemoteViews(context.packageName, itemLayoutId)
        
        var name = preset.name
        if (name.isEmpty()) {
            name = "Preset $id"
        }
        
        views.setTextViewText(R.id.preset_name, name)
        
        if (id.toIntOrNull() == selectedPresetId) {
            if (itemLayoutId == R.layout.widget_preset_button_item) {
                views.setInt(R.id.widget_item, "setBackgroundResource", R.drawable.widget_button_selected)
            } else {
                views.setViewVisibility(R.id.preset_indicator, android.view.View.VISIBLE)
            }
        } else {
            if (itemLayoutId == R.layout.widget_preset_button_item) {
                views.setInt(R.id.widget_item, "setBackgroundResource", R.drawable.widget_background)
            } else {
                views.setViewVisibility(R.id.preset_indicator, android.view.View.GONE)
            }
        }

        val fillInIntent = Intent().apply {
            putExtra(PresetWidgetProvider.EXTRA_PRESET_ID, id.toIntOrNull() ?: -1)
            putExtra(PresetWidgetProvider.EXTRA_DEVICE_ADDRESS, deviceAddress)
        }
        views.setOnClickFillInIntent(R.id.widget_item, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? {
        return null
    }

    override fun getViewTypeCount(): Int {
        return 1
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun hasStableIds(): Boolean {
        return true
    }

    companion object {
        private const val TAG = "PresetWidgetService"
    }
}
