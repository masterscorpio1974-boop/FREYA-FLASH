package com.masters.freyaflash
import android.app.Activity
import android.os.Bundle
import android.widget.*
import java.io.File
class MainActivity : Activity() {
    lateinit var txtEstado: TextView
    lateinit var txtArchivo: TextView
    lateinit var txtModelo: TextView
    lateinit var btnIniciar: Button
    lateinit var txtConsola: TextView
    var kdzEncontrado: File? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        txtEstado = findViewById(R.id.txtEstado)
        txtArchivo = findViewById(R.id.txtArchivo)
        txtModelo = findViewById(R.id.txtModelo)
        btnIniciar = findViewById(R.id.btnIniciar)
        txtConsola = findViewById(R.id.txtConsola)
        btnIniciar.isEnabled = false
        log("FREYA-FLASH V1.0 iniciada...")
        buscarKDZ()
        btnIniciar.setOnClickListener { kdzEncontrado?.let { flashear(it) } }
    }
    fun buscarKDZ() {
        val carpeta = File("/storage/emulated/0/Download/FREYA-FLASH")
        if (!carpeta.exists()) { carpeta.mkdirs(); log("Carpeta creada. Mete tu .kdz ahi, we."); return }
        val archivos = carpeta.listFiles { f -> f.name.lowercase().contains(".kdz") }
        if (archivos.isNullOrEmpty()) { txtEstado.text = "SIN ARCHIVO"; log("No hay .kdz."); return }
        val kdz = archivos[0]
        kdzEncontrado = kdz
        txtArchivo.text = kdz.name
        if (KDZParser.esK410(kdz)) {
            txtModelo.text = "Modelo: LG K410 - VALIDO"
            txtEstado.text = "LISTO PARA FLASHEAR"
            btnIniciar.isEnabled = true
            log("Archivo VALIDO: ${kdz.name}")
        } else {
            txtModelo.text = "Modelo: NO ES K410 - RECHAZADO"
            txtEstado.text = "ARCHIVO NO VALIDO"
            btnIniciar.isEnabled = false
            log("ERROR: Este .kdz NO es para K410.")
        }
    }
    fun flashear(kdz: File) {
        log("Iniciando flash como LGUP...")
        LAFProtocol.flashear(this, kdz) { mensaje -> runOnUiThread { log(mensaje) } }
    }
    fun log(msg: String) { txtConsola.append("\n> $msg") }
}
