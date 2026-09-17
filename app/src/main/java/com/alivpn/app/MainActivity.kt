package com.alivpn.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var serverView: TextView
    private lateinit var button: Button
    private lateinit var logView: TextView

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Main.immediate
        )

    private var candidates:
        List<ProxyNode> =
        emptyList()

    private var candidateIndex =
        0

    private var waitingForVerification =
        false

    private var connecting =
        false

    private var originalIp:
        String? = null

    // ---------------------------------------------------------
    // VPN permission
    // ---------------------------------------------------------

    private val vpnPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {

            if (it.resultCode == Activity.RESULT_OK) {

                startSelectedCandidate()

            } else {

                showError(
                    "Разрешение VPN отменено"
                )
            }
        }

    // ---------------------------------------------------------
    // Notification permission
    // ---------------------------------------------------------

    private val notificationsLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            // Ничего не запускаем.
            //
            // VPN permission должен появляться только
            // после нажатия кнопки "ПОДКЛЮЧИТЬ".
        }

    // ---------------------------------------------------------
    // VPN state receiver
    // ---------------------------------------------------------

    private val stateReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context,
                intent: Intent
            ) {

                when (
                    intent.getStringExtra(
                        AliVpnService.EXTRA_STATE
                    )
                ) {

                    AliVpnService.STATE_CONNECTING -> {

                        if (connecting) {

                            statusView.text =
                                "● ПОДКЛЮЧЕНИЕ"

                            logView.text =
                                "Запускаю VPN-ядро…"
                        }
                    }

                    AliVpnService.STATE_CONNECTED -> {

                        // Core started.
                        //
                        // Actual external IP verification
                        // is still performed separately.
                        if (waitingForVerification) {

                            statusView.text =
                                "● ПРОВЕРКА"

                            logView.text =
                                "Проверяю внешний IP…"
                        }
                    }

                    AliVpnService.STATE_DISCONNECTED -> {

                        if (connecting) {
                            return
                        }

                        statusView.text =
                            "● ОТКЛЮЧЕНО"

                        statusView.setTextColor(
                            getColor(R.color.muted)
                        )

                        button.text =
                            "ПОДКЛЮЧИТЬ"
                    }

                    AliVpnService.STATE_ERROR -> {

                        if (
                            connecting &&
                            waitingForVerification
                        ) {

                            tryNextCandidate(
                                "Ядро не запустилось"
                            )

                        } else {

                            val message =
                                intent.getStringExtra(
                                    "message"
                                ).orEmpty()

                            showError(
                                message.ifBlank {
                                    "Ошибка VPN"
                                }
                            )
                        }
                    }
                }
            }
        }

    // ---------------------------------------------------------
    // Activity
    // ---------------------------------------------------------

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        buildUi()

        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(
                AliVpnService.ACTION_STATE
            ),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Ask notification permission only.
        // It does NOT start VPN permission.
        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {

            notificationsLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    override fun onDestroy() {

        runCatching {
            unregisterReceiver(
                stateReceiver
            )
        }

        scope.cancel()

        super.onDestroy()
    }

    // ---------------------------------------------------------
    // UI
    // ---------------------------------------------------------

    private fun buildUi() {

        val root =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    28,
                    34,
                    28,
                    24
                )

                setBackgroundColor(
                    getColor(R.color.black)
                )
            }

        val title =
            TextView(this).apply {

                text =
                    "AliVPN"

                textSize =
                    30f

                setTextColor(
                    getColor(R.color.white)
                )
            }

        statusView =
            TextView(this).apply {

                text =
                    "● ОТКЛЮЧЕНО"

                textSize =
                    18f

                setTextColor(
                    getColor(R.color.muted)
                )

                setPadding(
                    0,
                    18,
                    0,
                    24
                )
            }

        button =
            Button(this).apply {

                text =
                    "ПОДКЛЮЧИТЬ"

                textSize =
                    17f

                setTextColor(
                    getColor(R.color.black)
                )

                background =
                    getDrawable(
                        R.drawable.bg_button
                    )

                setOnClickListener {
                    toggle()
                }
            }

        serverView =
            TextView(this).apply {

                text =
                    "Сервер: Авто"

                textSize =
                    17f

                setTextColor(
                    getColor(R.color.white)
                )

                setPadding(
                    0,
                    26,
                    0,
                    10
                )
            }

        logView =
            TextView(this).apply {

                text =
                    "Готово. AliVPN подберёт публичный узел и проверит внешний HTTPS-трафик."

                textSize =
                    14f

                setTextColor(
                    getColor(R.color.muted)
                )

                setPadding(
                    0,
                    18,
                    0,
                    0
                )
            }

        root.addView(
            title
        )

        root.addView(
            statusView
        )

        root.addView(
            button,
            LinearLayout.LayoutParams(
                -1,
                62
            )
        )

        root.addView(
            serverView
        )

        root.addView(
            logView
        )

        setContentView(
            root
        )
    }

    // ---------------------------------------------------------
    // Connect / Disconnect
    // ---------------------------------------------------------

    private fun toggle() {

        if (connecting) {
            return
        }

        val connected =
            statusView.text
                .toString()
                .contains(
                    "ПОДКЛЮЧЕНО"
                )

        if (connected) {

            connecting =
                false

            waitingForVerification =
                false

            stopVpn()

            return
        }

        connect()
    }

    private fun connect() {

        connecting =
            true

        waitingForVerification =
            false

        candidateIndex =
            0

        originalIp =
            null

        statusView.text =
            "● ЗАГРУЗКА"

        statusView.setTextColor(
            getColor(R.color.white)
        )

        button.text =
            "ПОДОЖДИТЕ…"

        logView.text =
            "Получаю текущий внешний IP…"

        scope.launch {

            try {

                // -------------------------------------------------
                // Save current IP BEFORE VPN
                // -------------------------------------------------

                originalIp =
                    withContext(
                        Dispatchers.IO
                    ) {
                        PublicConfigRepository.publicIp()
                    }

                logView.text =
                    "Скачиваю публичные VPN-конфигурации…"

                candidates =
                    withContext(
                        Dispatchers.IO
                    ) {
                        PublicConfigRepository
                            .load()
                            .shuffled()
                    }

                if (
                    candidates.isEmpty()
                ) {

                    error(
                        "Не найдено поддерживаемых конфигураций"
                    )
                }

                tryNextCandidate(
                    "Выбираю узел…"
                )

            } catch (e: Exception) {

                showError(
                    e.message
                        ?: "Не удалось получить список узлов"
                )
            }
        }
    }

    private fun tryNextCandidate(
        reason: String
    ) {

        scope.launch {

            stopVpn(
                silent = true
            )

            delay(800)

            if (
                candidateIndex >=
                candidates.size ||
                candidateIndex >= 12
            ) {

                showError(
                    "Не найден рабочий узел среди первых " +
                        "${minOf(12, candidates.size)} кандидатов"
                )

                return@launch
            }

            val node =
                candidates[
                    candidateIndex++
                ]

            serverView.text =
                "Сервер: ${node.name}"

            logView.text =
                "$reason Проверяю узел " +
                    "$candidateIndex/12…"

            waitingForVerification =
                true

            requestVpnPermission()
        }
    }

    // ---------------------------------------------------------
    // VPN permission
    // ---------------------------------------------------------

    private fun requestVpnPermission() {

        val intent =
            VpnService.prepare(
                this
            )

        if (intent != null) {

            vpnPermissionLauncher.launch(
                intent
            )

        } else {

            startSelectedCandidate()
        }
    }

    // ---------------------------------------------------------
    // Start selected node
    // ---------------------------------------------------------

    private fun startSelectedCandidate() {

        val node =
            candidates.getOrNull(
                candidateIndex - 1
            )

        if (node == null) {

            showError(
                "Сервер не выбран"
            )

            return
        }

        val config =
            SingBoxConfig.build(
                node
            )

        statusView.text =
            "● ПОДКЛЮЧЕНИЕ"

        statusView.setTextColor(
            getColor(R.color.white)
        )

        logView.text =
            "Запускаю sing-box TUN и проверяю внешний IP…"

        ContextCompat.startForegroundService(
            this,
            Intent(
                this,
                AliVpnService::class.java
            ).putExtra(
                "config",
                config
            )
        )

        scope.launch {

            // Give sing-box time to establish TUN.
            delay(4500)

            if (!waitingForVerification) {
                return@launch
            }

            statusView.text =
                "● ПРОВЕРКА"

            logView.text =
                "Проверяю реальный внешний IP…"

            val vpnIp =
                withContext(
                    Dispatchers.IO
                ) {
                    PublicConfigRepository.publicIp()
                }

            // -----------------------------------------------------
            // We need a successful request AND preferably a changed
            // external IP.
            //
            // If the initial IP wasn't available, a successful
            // request still proves connectivity, but we label it
            // as a weaker verification.
            // -----------------------------------------------------

            if (
                vpnIp != null &&
                (
                    originalIp == null ||
                    vpnIp != originalIp
                )
            ) {

                waitingForVerification =
                    false

                connecting =
                    false

                statusView.text =
                    "● ПОДКЛЮЧЕНО"

                statusView.setTextColor(
                    getColor(R.color.green)
                )

                button.text =
                    "ОТКЛЮЧИТЬ"

                logView.text =
                    if (
                        originalIp != null &&
                        vpnIp != originalIp
                    ) {
                        "VPN работает.\n" +
                            "IP до: $originalIp\n" +
                            "IP после: $vpnIp"
                    } else {
                        "HTTPS-трафик работает через VPN.\n" +
                            "Внешний IP: $vpnIp"
                    }

            } else {

                tryNextCandidate(
                    "Узел не прошёл проверку."
                )
            }
        }
    }

    // ---------------------------------------------------------
    // Stop VPN
    // ---------------------------------------------------------

    private fun stopVpn(
        silent: Boolean = false
    ) {

        runCatching {

            stopService(
                Intent(
                    this,
                    AliVpnService::class.java
                )
            )
        }

        if (!silent) {

            waitingForVerification =
                false

            connecting =
                false

            statusView.text =
                "● ОТКЛЮЧЕНО"

            statusView.setTextColor(
                getColor(R.color.muted)
            )

            button.text =
                "ПОДКЛЮЧИТЬ"

            logView.text =
                "VPN отключён."

            serverView.text =
                "Сервер: Авто"
        }
    }

    // ---------------------------------------------------------
    // Error
    // ---------------------------------------------------------

    private fun showError(
        text: String
    ) {

        connecting =
            false

        waitingForVerification =
            false

        stopVpn(
            silent = true
        )

        statusView.text =
            "● ОШИБКА"

        statusView.setTextColor(
            getColor(
                android.R.color.holo_red_light
            )
        )

        button.text =
            "ПОДКЛЮЧИТЬ"

        logView.text =
            text
    }
}
