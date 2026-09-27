package com.geno.veyra.smarthome

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import com.google.home.ConsentScreenOptions
import com.google.home.HomeDevice
import com.google.home.DeviceType
import com.google.home.DeviceTypeFactory
import com.google.home.FactoryRegistry
import com.google.home.ForcePermissionFlow
import com.google.home.Home
import com.google.home.HomeClient
import com.google.home.HomeConfig
import com.google.home.HomeException
import com.google.home.PermissionsState
import com.google.home.Trait
import com.google.home.TraitFactory
import com.google.home.matter.standard.BooleanState
import com.google.home.matter.standard.ColorTemperatureLightDevice
import com.google.home.matter.standard.ContactSensorDevice
import com.google.home.matter.standard.DimmableLightDevice
import com.google.home.matter.standard.DoorLock
import com.google.home.matter.standard.ExtendedColorLightDevice
import com.google.home.matter.standard.GenericSwitchDevice
import com.google.home.matter.standard.LevelControl
import com.google.home.matter.standard.LevelControlTrait
import com.google.home.matter.standard.OccupancySensing
import com.google.home.matter.standard.OccupancySensorDevice
import com.google.home.matter.standard.OnOff
import com.google.home.matter.standard.OnOffLightDevice
import com.google.home.matter.standard.OnOffLightSwitchDevice
import com.google.home.matter.standard.OnOffPluginUnitDevice
import com.google.home.matter.standard.OnOffSensorDevice
import com.google.home.matter.standard.TemperatureMeasurement
import com.google.home.matter.standard.TemperatureSensorDevice
import com.google.home.matter.standard.Thermostat
import com.google.home.matter.standard.ThermostatDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A snapshot of one Google Home device, simplified for the assistant tools.
 */
data class HomeDeviceInfo(
    val id: String,
    val name: String,
    val structureName: String,
    val typeName: String,
    val canTurnOnOff: Boolean,
    val canDim: Boolean,
    val isThermostat: Boolean,
    val isLock: Boolean,
    val stateSummary: String,
)

/**
 * Owns the Google Home [HomeClient] and exposes one-shot suspend helpers for
 * the assistant's smart-home tools. The OAuth client is resolved by Play
 * Services from the app's package name + signing certificate, so no client
 * ID is embedded in the app.
 */
@Singleton
class SmartHomeController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: HomeClient? = null
    private var registeredActivity: ComponentActivity? = null

    private fun getClient(): HomeClient {
        return client ?: run {
            val registry = FactoryRegistry(
                types = SUPPORTED_DEVICE_TYPES,
                traits = SUPPORTED_TRAITS,
            )
            val config = HomeConfig(
                coroutineContext = Dispatchers.IO,
                factoryRegistry = registry,
                homePlatformScope =
                    HomeConfig.HomePlatformScope.HOME_PLATFORM_SCOPE_VERSION_2,
            )
            Home.getClient(context, homeConfig = config).also { client = it }
        }
    }

    /**
     * Emits true when the user has granted Google Home permissions.
     * Emits false when the client can't even be built or permissions fail.
     */
    fun isConnected(): Flow<Boolean> {
        return try {
            getClient().hasPermissions().map { it == PermissionsState.GRANTED }
        } catch (e: Exception) {
            Log.w(TAG, "hasPermissions failed", e)
            flowOf(false)
        }
    }

    /**
     * Registers the host activity for the Home permission result. Must be
     * called from the activity's `onCreate` — the Activity Result API
     * throws if registration happens after the activity is STARTED, so
     * this cannot be done lazily when the user taps "Connect".
     * Re-registers if the activity instance changed (e.g. after recreate()).
     */
    fun registerForPermissions(activity: ComponentActivity) {
        val homeClient = getClient()
        if (registeredActivity !== activity) {
            homeClient.registerActivityResultCallerForPermissions(activity)
            registeredActivity = activity
        }
    }

    /**
     * Starts the Google Home permission flow: the account picker, the OAuth
     * consent screen, and the Home device-permission dialog. The activity
     * must have been registered via [registerForPermissions] first
     * (done in MainActivity.onCreate).
     */
    fun connect() {
        val homeClient = getClient()
        scope.launch {
            try {
                val result = homeClient.requestPermissions(
                    ForcePermissionFlow.FORCE_LAUNCH,
                    consentScreenOptions = ConsentScreenOptions(
                        structureId = null,
                        allowedStructureIds = emptyList(),
                        isAllowStructureChange = false,
                    ),
                )
                Log.i(TAG, "requestPermissions result: ${result.status}")
            } catch (e: HomeException) {
                Log.e(TAG, "requestPermissions failed", e)
            }
        }
    }

    /** Lists every device across all structures the user granted access to. */
    suspend fun listDevices(): List<HomeDeviceInfo> {
        val homeClient = getClient()
        val structures = withTimeoutOrNull(STRUCTURE_TIMEOUT_MS) {
            homeClient.structures().first { it.isNotEmpty() }
        } ?: return emptyList()
        val out = mutableListOf<HomeDeviceInfo>()
        for (structure in structures) {
            val devices = runCatching { structure.devices().first() }
                .getOrElse { emptySet() }
            for (device in devices) {
                runCatching { describeDevice(device, structure.name) }
                    .getOrNull()?.let { out.add(it) }
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private suspend fun describeDevice(
        device: HomeDevice,
        structureName: String,
    ): HomeDeviceInfo {
        val types = device.types().first()
        val primary = types.firstOrNull { it.metadata.isPrimaryType }
            ?: types.firstOrNull()
        val id = device.id.id
        val name = device.name.ifBlank { "Unnamed device" }
        if (primary == null) {
            return HomeDeviceInfo(
                id = id, name = name, structureName = structureName,
                typeName = "Unknown", canTurnOnOff = false, canDim = false,
                isThermostat = false, isLock = false,
                stateSummary = "unknown type",
            )
        }
        val liveType = device.type(primary.factory).first()
        val traits = liveType.traits()
        val onOff = traits.filterIsInstance<OnOff>().firstOrNull()
        val level = traits.filterIsInstance<LevelControl>().firstOrNull()
        val thermostat = traits.filterIsInstance<Thermostat>().firstOrNull()
        val lock = traits.filterIsInstance<DoorLock>().firstOrNull()
        val typeName = TYPE_NAMES[primary.factory]
            ?: primary.factory.toString().substringAfterLast('.')
        val summary = buildString {
            if (onOff != null) append(if (onOff.onOff == true) "on" else "off")
            if (level?.currentLevel != null) {
                if (isNotEmpty()) append(", ")
                append("brightness ${(level.currentLevel!!.toInt() * 100) / 254}%")
            }
            if (thermostat != null) {
                if (isNotEmpty()) append(", ")
                append("mode ${thermostat.systemMode}")
            }
            if (isEmpty()) append(typeName)
        }
        return HomeDeviceInfo(
            id = id,
            name = name,
            structureName = structureName,
            typeName = typeName,
            canTurnOnOff = onOff != null,
            canDim = level != null,
            isThermostat = thermostat != null,
            isLock = lock != null,
            stateSummary = summary,
        )
    }

    /** Finds a device by id, exact name, or unambiguous partial name. */
    suspend fun findDevice(query: String): DeviceMatch {
        val devices = listDevices()
        if (devices.isEmpty()) return DeviceMatch.None("no_devices")
        val q = query.trim().lowercase()
        devices.firstOrNull { it.id == query.trim() }?.let {
            return DeviceMatch.Single(it)
        }
        devices.firstOrNull { it.name.lowercase() == q }?.let {
            return DeviceMatch.Single(it)
        }
        val partial = devices.filter { it.name.lowercase().contains(q) }
        return when {
            partial.size == 1 -> DeviceMatch.Single(partial[0])
            partial.size > 1 -> DeviceMatch.Ambiguous(
                partial.map { it.name },
            )
            else -> DeviceMatch.None("not_found")
        }
    }

    sealed interface DeviceMatch {
        data class Single(val device: HomeDeviceInfo) : DeviceMatch
        data class Ambiguous(val names: List<String>) : DeviceMatch
        data class None(val reason: String) : DeviceMatch
    }

    /** Turns a device on or off. Returns false when the trait is missing. */
    suspend fun setOnOff(deviceId: String, on: Boolean): Boolean {
        val trait = findTrait<OnOff>(deviceId) ?: return false
        return runCatching {
            if (on) trait.on() else trait.off()
            true
        }.getOrElse {
            Log.e(TAG, "setOnOff failed", it)
            false
        }
    }

    /** Sets brightness 0-100. Returns false when the trait is missing. */
    suspend fun setBrightness(deviceId: String, percent: Int): Boolean {
        val trait = findTrait<LevelControl>(deviceId) ?: return false
        val level = (percent.coerceIn(0, 100) * 254 / 100).toUByte()
        return runCatching {
            trait.moveToLevelWithOnOff(
                level = level,
                transitionTime = 0u,
                optionsMask = LevelControlTrait.OptionsBitmap(),
                optionsOverride = LevelControlTrait.OptionsBitmap(),
            )
            true
        }.getOrElse {
            Log.e(TAG, "setBrightness failed", it)
            false
        }
    }

    private suspend inline fun <reified T : Trait> findTrait(
        deviceId: String,
    ): T? {
        val homeClient = getClient()
        val structures = withTimeoutOrNull(STRUCTURE_TIMEOUT_MS) {
            homeClient.structures().first { it.isNotEmpty() }
        } ?: return null
        for (structure in structures) {
            val devices = runCatching { structure.devices().first() }
                .getOrElse { emptySet() }
            val device = devices.firstOrNull { it.id.id == deviceId }
                ?: continue
            val types = runCatching { device.types().first() }
                .getOrElse { emptySet() }
            val primary = types.firstOrNull { it.metadata.isPrimaryType }
                ?: types.firstOrNull() ?: continue
            val liveType = runCatching { device.type(primary.factory).first() }
                .getOrNull() ?: continue
            return liveType.traits().filterIsInstance<T>().firstOrNull()
        }
        return null
    }

    private companion object {
        const val TAG = "SmartHomeController"
        const val STRUCTURE_TIMEOUT_MS = 10_000L

        val SUPPORTED_DEVICE_TYPES: List<DeviceTypeFactory<out DeviceType>> = listOf(
            OnOffLightDevice,
            DimmableLightDevice,
            ColorTemperatureLightDevice,
            ExtendedColorLightDevice,
            OnOffPluginUnitDevice,
            OnOffLightSwitchDevice,
            OnOffSensorDevice,
            GenericSwitchDevice,
            ThermostatDevice,
            TemperatureSensorDevice,
            OccupancySensorDevice,
            ContactSensorDevice,
        )

        val SUPPORTED_TRAITS: List<TraitFactory<out Trait>> = listOf(
            OnOff,
            LevelControl,
            Thermostat,
            TemperatureMeasurement,
            OccupancySensing,
            BooleanState,
            DoorLock,
        )

        val TYPE_NAMES: Map<com.google.home.DeviceTypeFactory<*>, String> = mapOf(
            OnOffLightDevice to "Light",
            DimmableLightDevice to "Dimmable light",
            ColorTemperatureLightDevice to "Light",
            ExtendedColorLightDevice to "Color light",
            OnOffPluginUnitDevice to "Plug",
            OnOffLightSwitchDevice to "Switch",
            OnOffSensorDevice to "Sensor",
            GenericSwitchDevice to "Switch",
            ThermostatDevice to "Thermostat",
            TemperatureSensorDevice to "Temperature sensor",
            OccupancySensorDevice to "Occupancy sensor",
            ContactSensorDevice to "Contact sensor",
        )
    }
}
