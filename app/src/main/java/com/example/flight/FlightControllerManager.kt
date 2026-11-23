package com.example.flight

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.example.connectivity.diagnostics.DiagnosticLogger
import com.example.connectivity.diagnostics.LogSeverity
import com.example.connectivity.diagnostics.LogSubsystem
import com.example.core.models.DroneCapabilities
import com.example.core.models.FlightState
import com.example.core.models.StickInput
import com.example.protocols.api.FlightController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Contrôleur de vol indépendant avec machine à états stricte et garde-fous de sécurité (Section 9).
 * L'IA ne peut jamais envoyer directement de commandes de vol ici.
 */
class FlightControllerManager(
    private val logger: DiagnosticLogger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _flightState = MutableStateFlow(FlightState.Disconnected)
    val flightState: StateFlow<FlightState> = _flightState.asStateFlow()

    private val _stickInput = MutableStateFlow(StickInput())
    val stickInput: StateFlow<StickInput> = _stickInput.asStateFlow()

    private val _speedMode = MutableStateFlow(1)
    val speedMode: StateFlow<Int> = _speedMode.asStateFlow()

    private val _headlessEnabled = MutableStateFlow(false)
    val headlessEnabled: StateFlow<Boolean> = _headlessEnabled.asStateFlow()

    private val _safetyStatusMessage = MutableStateFlow<String?>("Pilotage verrouillé : mode test vidéo")
    val safetyStatusMessage: StateFlow<String?> = _safetyStatusMessage.asStateFlow()

    private var activeAdapterController: FlightController? = null
    private var activeCapabilities: DroneCapabilities = DroneCapabilities()
    private var videoOnlyLock: Boolean = true
    private var protocolVerified: Boolean = false
    private var sendRateHz: Int = 25
    private var sensitivity: Float = 0.75f
    private var deadzone: Float = 0.08f
    private var invertPitch: Boolean = false
    private var invertRoll: Boolean = false
    private var invertYaw: Boolean = false

    private var txLoopJob: Job? = null

    fun attachProtocol(
        controller: FlightController?,
        capabilities: DroneCapabilities,
        isConnected: Boolean,
        isProtocolVerified: Boolean
    ) {
        activeAdapterController = controller
        activeCapabilities = capabilities
        protocolVerified = isProtocolVerified
        if (!isConnected) {
            transitionTo(FlightState.Disconnected, "Aucune liaison drone active")
            stopTxLoop()
        } else {
            refreshReadyState()
        }
    }

    fun updateSafetyConfig(
        videoOnlyLock: Boolean,
        protocolVerified: Boolean,
        sendRateHz: Int,
        sensitivity: Float,
        deadzone: Float,
        invertPitch: Boolean,
        invertRoll: Boolean,
        invertYaw: Boolean
    ) {
        this.videoOnlyLock = videoOnlyLock
        this.protocolVerified = protocolVerified
        this.sendRateHz = sendRateHz.coerceIn(10, 50)
        this.sensitivity = sensitivity.coerceIn(0.2f, 1.0f)
        this.deadzone = deadzone.coerceIn(0f, 0.25f)
        this.invertPitch = invertPitch
        this.invertRoll = invertRoll
        this.invertYaw = invertYaw

        if (videoOnlyLock && _flightState.value.isAirborneOrActive) {
            neutralizeSticks()
        }
        refreshReadyState()
    }

    private fun refreshReadyState() {
        val current = _flightState.value
        if (current == FlightState.Emergency || current == FlightState.Flying) return

        when {
            activeAdapterController == null -> {
                transitionTo(FlightState.Disconnected, "Déconnecté")
            }
            videoOnlyLock -> {
                transitionTo(FlightState.Connected, "Pilotage verrouillé (Mode test vidéo actif)")
            }
            !protocolVerified -> {
                transitionTo(FlightState.Connected, "Protocole de vol non vérifié — déverrouillez dans Paramètres")
            }
            !activeCapabilities.supportsFlightControl -> {
                transitionTo(FlightState.Connected, "Ce profil expose uniquement la vidéo")
            }
            else -> {
                transitionTo(FlightState.Ready, "Prêt pour armement")
            }
        }
    }

    fun canArm(): Boolean {
        return activeAdapterController != null &&
            !videoOnlyLock &&
            protocolVerified &&
            activeCapabilities.supportsFlightControl &&
            (_flightState.value == FlightState.Ready || _flightState.value == FlightState.Emergency)
    }

    private var armingJob: Job? = null

    fun armAndTakeOff(requireConfirmationPassed: Boolean) {
        if (!requireConfirmationPassed || !canArm()) {
            logger.log(LogSubsystem.FLIGHT, LogSeverity.WARN, "Tentative d'armement refusée par SafetyGuard")
            return
        }
        val controller = activeAdapterController ?: return
        armingJob?.cancel()
        armingJob = scope.launch {
            transitionTo(FlightState.Arming, "Armement des moteurs (annulation possible)...")
            delay(1100L)
            if (!isActive || _flightState.value != FlightState.Arming) return@launch
            controller.takeOff()
            transitionTo(FlightState.Flying, "Vol manuel actif")
            startTxLoop()
        }
    }

    fun cancelArming() {
        if (_flightState.value == FlightState.Arming) {
            armingJob?.cancel()
            armingJob = null
            neutralizeSticks()
            refreshReadyState()
            logger.log(LogSubsystem.FLIGHT, LogSeverity.INFO, "Armement annulé par le pilote")
        }
    }

    fun normalLand() {
        val controller = activeAdapterController ?: return
        if (!activeCapabilities.supportsTakeOffLand) return
        scope.launch {
            transitionTo(FlightState.Landing, "Atterrissage normal en cours...")
            neutralizeSticks()
            controller.land()
            delay(800L)
            stopTxLoop()
            refreshReadyState()
        }
    }

    /**
     * Arrêt d'urgence distinct de l'atterrissage normal : coupe immédiatement les moteurs.
     */
    fun emergencyStop() {
        armingJob?.cancel()
        armingJob = null
        neutralizeSticks()
        stopTxLoop()
        val controller = activeAdapterController
        scope.launch {
            controller?.emergencyStop()
        }
        transitionTo(FlightState.Emergency, "ARRÊT D'URGENCE DÉCLENCHÉ — Moteurs coupés")
        logger.log(LogSubsystem.FLIGHT, LogSeverity.ERROR, "Arrêt d'urgence activé par le pilote")
    }

    fun resetEmergency() {
        if (_flightState.value == FlightState.Emergency || _flightState.value == FlightState.Fault) {
            refreshReadyState()
        }
    }

    fun calibrateGyro() {
        val controller = activeAdapterController ?: return
        if (!activeCapabilities.supportsGyroCalibration || _flightState.value == FlightState.Flying) return
        scope.launch {
            controller.calibrateGyro()
            logger.log(LogSubsystem.FLIGHT, LogSeverity.INFO, "Calibration gyroscope déclenchée")
        }
    }

    fun cycleSpeedMode() {
        if (!activeCapabilities.supportsSpeedModes) return
        val next = (_speedMode.value % 3) + 1
        _speedMode.value = next
        scope.launch { activeAdapterController?.setSpeedMode(next) }
    }

    fun toggleHeadless() {
        if (!activeCapabilities.supportsHeadless) return
        val next = !_headlessEnabled.value
        _headlessEnabled.value = next
        scope.launch { activeAdapterController?.toggleHeadless(next) }
    }

    fun triggerFlip() {
        if (!activeCapabilities.supportsFlip || _flightState.value != FlightState.Flying) return
        scope.launch { activeAdapterController?.triggerFlip() }
    }

    fun updateLeftStick(yaw: Float, throttle: Float) {
        val y = applyDeadzoneAndScale(if (invertYaw) -yaw else yaw)
        val t = applyDeadzoneAndScale(throttle)
        _stickInput.value = _stickInput.value.copy(
            yaw = y,
            throttle = t,
            timestampMs = System.currentTimeMillis()
        )
    }

    fun updateRightStick(roll: Float, pitch: Float) {
        val r = applyDeadzoneAndScale(if (invertRoll) -roll else roll)
        val p = applyDeadzoneAndScale(if (invertPitch) -pitch else pitch)
        _stickInput.value = _stickInput.value.copy(
            roll = r,
            pitch = p,
            timestampMs = System.currentTimeMillis()
        )
    }

    fun handleGamepadMotion(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false
        val lx = event.getAxisValue(MotionEvent.AXIS_X)
        val ly = -event.getAxisValue(MotionEvent.AXIS_Y)
        val rx = event.getAxisValue(MotionEvent.AXIS_Z)
        val ry = -event.getAxisValue(MotionEvent.AXIS_RZ)
        updateLeftStick(yaw = lx, throttle = ly)
        updateRightStick(roll = rx, pitch = ry)
        return true
    }

    fun handleGamepadKey(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (_flightState.value == FlightState.Flying) normalLand() else armAndTakeOff(true)
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_SELECT -> {
                emergencyStop()
                true
            }
            else -> false
        }
    }

    fun neutralizeSticks() {
        _stickInput.value = StickInput(0f, 0f, 0f, 0f, System.currentTimeMillis())
    }

    fun onConnectionLost() {
        neutralizeSticks()
        stopTxLoop()
        transitionTo(FlightState.Fault, "Perte Wi-Fi : commandes neutralisées")
    }

    private fun startTxLoop() {
        stopTxLoop()
        val intervalMs = (1000L / sendRateHz).coerceIn(20L, 100L)
        txLoopJob = scope.launch {
            while (isActive && _flightState.value == FlightState.Flying) {
                val controller = activeAdapterController ?: break
                if (videoOnlyLock || !protocolVerified) {
                    neutralizeSticks()
                    break
                }
                controller.sendStickInput(_stickInput.value)
                delay(intervalMs)
            }
        }
    }

    private fun stopTxLoop() {
        txLoopJob?.cancel()
        txLoopJob = null
    }

    private fun applyDeadzoneAndScale(raw: Float): Float {
        val clamped = raw.coerceIn(-1f, 1f)
        if (abs(clamped) < deadzone) return 0f
        return (clamped * sensitivity).coerceIn(-1f, 1f)
    }

    private fun transitionTo(next: FlightState, reason: String) {
        _flightState.value = next
        _safetyStatusMessage.value = reason
    }
}
