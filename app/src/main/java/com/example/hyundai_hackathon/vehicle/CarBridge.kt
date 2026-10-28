package com.example.hyundai_hackathon.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors

data class VehicleState(
    val automotive: Boolean = false,
    val connected: Boolean = false,
    val speedMps: Double? = null,
    val speedTimestampMs: Long = 0,
    val gear: Int? = null,
    val brake: Boolean? = null,
    val wheelAngle: Double? = null,
    val uxKnown: Boolean = false,
    val uxRestricted: Boolean = true,
    val message: String = "차량 연결을 준비하고 있어요",
    val sdkMessage: String = "차량 SDK 연결 대기"
) {
    val fresh get() = speedTimestampMs > 0 && SystemClock.elapsedRealtime() - speedTimestampMs in 0..5_000
    // PARK is 4 in the AAOS VehicleGear contract; keeping this model loadable on phones.
    val parked get() = connected && gear == 4 && (speedMps == null || speedMps < 0.1) && uxKnown && !uxRestricted
    val interactionAllowed get() = !automotive || parked
    val gearText get() = when (gear) { 4 -> "P"; 1 -> "N"; 2 -> "R"; 8 -> "D"; else -> "—" }
}

/** Read-only AAOS speed/gear + Pleos brake/front-wheel angle, guarded per capability. */
class CarBridge(private val context: Context, private val onState: (VehicleState) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val requests = Executors.newSingleThreadExecutor { task -> Thread(task, "vehicle-requests").apply { isDaemon = true } }
    @Volatile private var active = true
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null
    private var vehicle: Vehicle? = null
    private var brakeRegistered = false
    private var angleRegistered = false
    private var state = VehicleState(automotive = true)

    private fun update(transform: (VehicleState) -> VehicleState) {
        handler.post { if (active) { state = transform(state); onState(state) } }
    }

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.status != CarPropertyValue.STATUS_AVAILABLE) {
                if (value.propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) update { it.copy(speedMps = null, speedTimestampMs = 0, message = "속도 신호를 기다리고 있어요") }
                if (value.propertyId == VehiclePropertyIds.GEAR_SELECTION) update { it.copy(gear = null) }
                return
            }
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                    val speed = (value.value as? Number)?.toDouble()?.let { kotlin.math.abs(it) }
                    if (speed != null && speed.isFinite() && speed <= 75) update {
                        it.copy(speedMps = speed, speedTimestampMs = value.timestamp / 1_000_000,
                            message = "차량 신호 연결됨")
                    }
                }
                VehiclePropertyIds.GEAR_SELECTION -> update { it.copy(gear = (value.value as? Number)?.toInt()) }
            }
        }
        override fun onErrorEvent(propId: Int, areaId: Int) {
            if (propId == VehiclePropertyIds.PERF_VEHICLE_SPEED) update { it.copy(speedMps = null, speedTimestampMs = 0, message = "속도 신호를 읽을 수 없어요") }
            if (propId == VehiclePropertyIds.GEAR_SELECTION) update { it.copy(gear = null) }
        }
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) = update { it.copy(brake = status) }
        override fun onFailed(e: Exception) = update { it.copy(brake = null, sdkMessage = "브레이크 신호 미지원 또는 연결 대기") }
    }
    private val angleListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) = update { it.copy(wheelAngle = angle?.toDouble()) }
        override fun onFailed(e: Exception) = update { it.copy(wheelAngle = null) }
    }

    fun start() {
        try {
            car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                if (!active) return@createCar
                if (!ready) {
                    update { VehicleState(automotive = true, message = "차량 연결이 끊어졌어요") }
                    return@createCar
                }
                car = connectedCar
                update { it.copy(connected = true) }
                requests.execute {
                    if (active) try {
                        connectProperties(connectedCar)
                        connectRestrictions(connectedCar)
                    } catch (_: Exception) { update { it.copy(message = "차량 정보를 다시 연결해주세요", uxKnown = false) } }
                }
            }
        } catch (_: Exception) { update { it.copy(message = "차량 정보에 접근할 수 없어요") } }
        connectPleos()
    }

    private fun connectProperties(car: Car) {
        val manager = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
        properties = manager
        try {
            if (manager.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED) != null) {
                val registered = manager.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, 5f)
                if (!registered) update { it.copy(message = "이 차량은 속도 구독을 지원하지 않아요") }
            } else update { it.copy(message = "속도 권한 또는 차량 지원 여부를 확인해주세요") }
        } catch (_: SecurityException) { update { it.copy(message = "속도 정보 권한이 필요해요") } }
        catch (_: Exception) { update { it.copy(message = "속도 정보를 사용할 수 없어요") } }
        try {
            manager.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            update { it.copy(gear = manager.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0)) }
        } catch (_: Exception) { update { it.copy(gear = null) } }
    }

    private fun connectRestrictions(car: Car) {
        try {
            val manager = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return
            ux = manager
            fun send() { val r = manager.currentCarUxRestrictions; update { it.copy(uxKnown = true, uxRestricted = r.isRequiresDistractionOptimization) } }
            manager.registerListener { restrictions -> update { it.copy(uxKnown = true, uxRestricted = restrictions.isRequiresDistractionOptimization) } }
            send()
        } catch (_: Exception) { update { it.copy(uxKnown = false, uxRestricted = true) } }
    }

    private fun connectPleos() {
        try {
            val sdk = Vehicle(context)
            vehicle = sdk
            sdk.initialize()
            sdk.getBrake().checkBrakePedalStatusCapabilities(onComplete = { cap ->
                if (!active) return@checkBrakePedalStatusCapabilities
                if (cap.accessType == AccessType.READ || cap.accessType == AccessType.READ_WRITE) {
                    sdk.getBrake().registerBrakePedalStatus(brakeListener)
                    brakeRegistered = true
                    sdk.getBrake().getCurrentBrakePedalStatus({ value -> update { it.copy(brake = value) } }, {})
                    update { it.copy(sdkMessage = "Pleos SDK 연결됨") }
                } else update { it.copy(sdkMessage = "브레이크 신호 미지원") }
            }, onFailed = { update { it.copy(sdkMessage = "Pleos 보조 신호 연결 대기") } })
            sdk.getSteeringWheel().checkSteeringWheelAngleCapabilities(onComplete = { cap, _ ->
                if (!active) return@checkSteeringWheelAngleCapabilities
                if (cap.accessType == AccessType.READ || cap.accessType == AccessType.READ_WRITE) {
                    sdk.getSteeringWheel().registerSteeringWheelAngle(angleListener)
                    angleRegistered = true
                    sdk.getSteeringWheel().getCurrentSteeringWheelAngle({ angle -> update { it.copy(wheelAngle = angle?.toDouble()) } }, {})
                }
            }, onFailed = {})
        } catch (_: Exception) { update { it.copy(sdkMessage = "Pleos 보조 신호 미지원") } }
    }

    fun close() {
        if (!active) return
        active = false
        handler.removeCallbacksAndMessages(null)
        // Slow vehicle binder calls must never freeze the screen or lifecycle callbacks.
        requests.execute {
            try { properties?.unregisterCallback(callback) } catch (_: Exception) { }
            try { ux?.unregisterListener() } catch (_: Exception) { }
            try { if (brakeRegistered) vehicle?.getBrake()?.unregisterBrakePedalStatus(brakeListener) } catch (_: Exception) { }
            try { if (angleRegistered) vehicle?.getSteeringWheel()?.unregisterSteeringWheelAngle(angleListener) } catch (_: Exception) { }
            try { vehicle?.release() } catch (_: Exception) { }
            try { car?.disconnect() } catch (_: Exception) { }
        }
        requests.shutdown()
    }
}
