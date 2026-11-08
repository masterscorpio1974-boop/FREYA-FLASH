package com.masters.freyaflash
import java.io.File
object KDZParser {
    fun esK410(kdz: File): Boolean {
        val nombre = kdz.name.uppercase()
        if (!nombre.contains("K410") && !nombre.contains("LM-K410")) return false
        if (kdz.length() < 100_000_000) return false
        return true
    }
}
