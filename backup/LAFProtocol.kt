package com.masters.freyaflash
import android.content.Context
import java.io.File
object LAFProtocol {
    fun flashear(ctx: Context, kdz: File, onLog: (String) -> Unit) {
        onLog("LAF: Conectando a Download Mode...")
        onLog("LAF: Enviando HELLO...")
        onLog("LAF: Esperando respuesta del K410...")
        onLog("LAF: OJO - Aqui va el protocolo real LAF, aun no flashea de verdad.")
        onLog("LAF: Por seguridad NO flashea hasta que validemos el HELLO con helo_fd.py")
    }
}
