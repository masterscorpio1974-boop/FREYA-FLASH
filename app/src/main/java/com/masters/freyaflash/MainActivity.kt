package com.masters.freyaflash

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var consoleOutput: TextView
    private lateinit var btnSelectFile: Button
    private lateinit var btnFlash: Button
    private lateinit var btnStop: Button
    private lateinit var btnExportLogs: Button
    private lateinit var tvFileName: TextView
    private lateinit var tvModelInfo: TextView

    private var selectedFileUri: Uri? = null
    private var detectedModelFromFile: String? = null
    private val logs = mutableListOf<String>()

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.also { uri ->
                selectedFileUri = uri
                handleFileSelected(uri)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            appendLog("Permiso concedido - puedes seleccionar archivos")
            openFilePicker()
        } else {
            appendLog("Permiso denegado - no se puede acceder a archivos")
            Toast.makeText(this, "Se necesita permiso de almacenamiento", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        setupButtons()
        appendLog("FREYA-FLASH v1.2 iniciada...")
        appendLog("Esperando archivo .kdz...")
    }

    private fun initViews() {
        consoleOutput = findViewById(R.id.consoleOutput)
        btnSelectFile = findViewById(R.id.btnSelectFile)
        btnFlash = findViewById(R.id.btnFlash)
        btnStop = findViewById(R.id.btnStop)
        btnExportLogs = findViewById(R.id.btnExportLogs)
        tvFileName = findViewById(R.id.tvFileName)
        tvModelInfo = findViewById(R.id.tvModelInfo)
        setButtonsEnabled(false)
    }

    private fun setupButtons() {
        btnSelectFile.setOnClickListener { checkPermissionAndOpenFile() }
        btnFlash.setOnClickListener {
            if (selectedFileUri != null) {
                appendLog("FLASH - En desarrollo / simulacion")
                appendLog("Archivo: ${selectedFileUri?.lastPathSegment}")
                appendLog("Modelo: $detectedModelFromFile")
                Toast.makeText(this, "Flasheo iniciado (simulacion)", Toast.LENGTH_SHORT).show()
            }
        }
        btnStop.setOnClickListener {
            appendLog("Proceso detenido por el usuario")
        }
        btnExportLogs.setOnClickListener { exportLogs() }
    }

    private fun checkPermissionAndOpenFile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            openFilePicker()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                openFilePicker()
            } else {
                requestPermissionLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        } else {
            openFilePicker()
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        pickFileLauncher.launch(Intent.createChooser(intent, "Seleccionar archivo .kdz"))
    }

    private fun handleFileSelected(uri: Uri) {
        val fileName = uri.lastPathSegment ?: "Archivo desconocido"
        tvFileName.text = "Archivo: $fileName"
        appendLog("Archivo seleccionado: $fileName")
        if (!fileName.endsWith(".kdz", ignoreCase = true)) {
            appendLog("El archivo no tiene extension .kdz")
            tvModelInfo.text = "Modelo: - Archivo no valido"
            setButtonsEnabled(false)
            return
        }
        detectedModelFromFile = parseModelFromFileName(fileName)
        tvModelInfo.text = "Modelo detectado: $detectedModelFromFile"
        appendLog("Modelo desde archivo: $detectedModelFromFile")
        appendLog("LISTO PARA FLASHEAR")
        setButtonsEnabled(true)
    }

    private fun parseModelFromFileName(name: String): String {
        return when {
            name.contains("K410", ignoreCase = true) -> "LG K410 / K41S"
            name.contains("K420", ignoreCase = true) -> "LG K42"
            name.contains("K50", ignoreCase = true) -> "LG K50/K51"
            name.contains("V50", ignoreCase = true) -> "LG V50"
            name.contains("V60", ignoreCase = true) -> "LG V60"
            else -> "Modelo LG (sin identificar)"
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnFlash.isEnabled = enabled
        btnFlash.alpha = if (enabled) 1.0f else 0.4f
    }

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $message"
        logs.add(line)
        val currentText = consoleOutput.text
        consoleOutput.text = if (currentText.isEmpty()) line else "$currentText\n$line"
    }

    private fun exportLogs() {
        if (logs.isEmpty()) {
            appendLog("No hay registros para exportar")
            return
        }
        val logText = logs.joinToString("\n")
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, logText)
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Exportar logs a..."))
        appendLog("Logs enviados para exportacion")
    }
}
