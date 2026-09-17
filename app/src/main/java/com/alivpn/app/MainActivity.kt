package com.alivpn.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var serverView: TextView
    private lateinit var button: Button
    private lateinit var logView: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var candidates: List<ProxyNode> = emptyList()
    private var candidateIndex = 0
    private var waitingForVerification = false
    private var connecting = false

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                startSelectedCandidate()
            } else {
                showError("Разрешение VPN отменено")
            }
        }

    private val notificationsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestVpnPermission()
        }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(AliVpnService.EXTRA_STATE)) {
                AliVpnService.STATE_CONNECTED -> {
                    if (!waitingForVerification) {
                        statusView.text = "● ПОДКЛЮЧЕНО"
                        statusView.setTextColor(getColor(R.color.green))
                        connecting = false
                        button.text = "ОТКЛЮЧИТЬ"
                    }
                }

                AliVpnService.STATE_DISCONNECTED -> {
                    if (connecting) return
                    statusView.text = "● ОТКЛЮЧЕНО"
                    statusView.setTextColor(getColor(R.color.muted))
                    button.text = "ПОДКЛЮЧИТЬ"
                }

                AliVpnService.STATE_ERROR -> {
                    if (connecting && waitingForVerification) {
                        tryNextCandidate("Ядро не запустилось")
                    } else {
                        val message = intent.getStringExtra("message").orEmpty()
                        showError(message.ifBlank { "Ошибка VPN" })
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(AliVpnService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(stateReceiver)
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 34, 28, 24)
            setBackgroundColor(getColor(R.color.black))
        }

        val title = TextView(this).apply {
            text = "AliVPN"
            textSize = 30f
            setTextColor(getColor(R.color.white))
        }

        statusView = TextView(this).apply {
            text = "● ОТКЛЮЧЕНО"
            textSize = 18f
            setTextColor(getColor(R.color.muted))
            setPadding(0, 18, 0, 24)
        }

        button = Button(this).apply {
            text = "ПОДКЛЮЧИТЬ"
            textSize = 17f
            setTextColor(getColor(R.color.black))
            background = getDrawable(R.drawable.bg_button)
            setOnClickListener { toggle() }
        }

        serverView = TextView(this).apply {
            text = "Сервер: Авто"
            textSize = 17f
            setTextColor(getColor(R.color.white))
            setPadding(0, 26, 0, 10)
        }

        logView = TextView(this).apply {
            text = "Готово. AliVPN подберёт публичный узел и проверит внешний HTTPS-трафик."
            textSize = 14f
            setTextColor(getColor(R.color.muted))
            setPadding(0, 18, 0, 0)
        }

        root.addView(title)
        root.addView(statusView)
        root.addView(button, LinearLayout.LayoutParams(-1, 62))
        root.addView(serverView)
        root.addView(logView)

        setContentView(root)
    }

    private fun toggle() {
        if (connecting) return

        val connected = statusView.text.toString().contains("ПОДКЛЮЧЕНО")
        if (connected) {
            connecting = false
            waitingForVerification = false
            stopVpn()
            return
        }

        connect()
    }

    private fun connect() {
        connecting = true
        waitingForVerification = false
        candidateIndex = 0

        statusView.text = "● ЗАГРУЗКА"
        statusView.setTextColor(getColor(R.color.white))
        button.text = "ПОДОЖДИТЕ…"
        logView.text = "Скачиваю публичные VPN-конфигурации…"

        scope.launch {
            try {
                candidates = withContext(Dispatchers.IO) {
                    PublicConfigRepository.load().shuffled()
                }

                if (candidates.isEmpty()) {
                    error("Не найдено поддерживаемых конфигураций")
                }

                tryNextCandidate("Выбираю узел…")
            } catch (e: Exception) {
                showError(e.message ?: "Не удалось получить список узлов")
            }
        }
    }

    private fun tryNextCandidate(reason: String) {
        scope.launch {
            stopVpn(silent = true)
            delay(800)

            if (candidateIndex >= candidates.size || candidateIndex >= 12) {
                showError("Не найден рабочий узел среди первых ${minOf(12, candidates.size)} кандидатов")
                return@launch
            }

            val node = candidates[candidateIndex++]
            serverView.text = "Сервер: ${node.name}"
            logView.text = "$reason Проверяю узел ${candidateIndex}/12…"
            waitingForVerification = true
            requestVpnPermission()
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startSelectedCandidate()
        }
    }

    private fun startSelectedCandidate() {
        val node = candidates.getOrNull(candidateIndex - 1)
        if (node == null) {
            showError("Сервер не выбран")
            return
        }

        val config = SingBoxConfig.build(node)

        statusView.text = "● ПОДКЛЮЧЕНИЕ"
        logView.text = "Запускаю sing-box TUN и проверяю реальный внешний IP…"

        ContextCompat.startForegroundService(
            this,
            Intent(this, AliVpnService::class.java).putExtra("config", config)
        )

        scope.launch {
            delay(4500)

            if (!waitingForVerification) return@launch

            val ip = withContext(Dispatchers.IO) {
                PublicConfigRepository.publicIp()
            }

            if (ip != null) {
                waitingForVerification = false
                connecting = false
                statusView.text = "● ПОДКЛЮЧЕНО"
                statusView.setTextColor(getColor(R.color.green))
                button.text = "ОТКЛЮЧИТЬ"
                logView.text = "Реальный HTTPS-трафик проходит через VPN.\nВнешний IP: $ip"
            } else {
                tryNextCandidate("Внешний HTTPS-трафик не прошёл.")
            }
        }
    }

    private fun stopVpn(silent: Boolean = false) {
        runCatching {
            stopService(Intent(this, AliVpnService::class.java))
        }
        if (!silent) {
            waitingForVerification = false
            connecting = false
            statusView.text = "● ОТКЛЮЧЕНО"
            statusView.setTextColor(getColor(R.color.muted))
            button.text = "ПОДКЛЮЧИТЬ"
            logView.text = "VPN отключён."
            serverView.text = "Сервер: Авто"
        }
    }

    private fun showError(text: String) {
        connecting = false
        waitingForVerification = false
        stopVpn(silent = true)

        statusView.text = "● ОШИБКА"
        statusView.setTextColor(getColor(android.R.color.holo_red_light))
        button.text = "ПОДКЛЮЧИТЬ"
        logView.text = text
    }
}
