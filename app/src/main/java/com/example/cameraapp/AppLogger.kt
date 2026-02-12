package com.example.cameraapp

import android.util.Log

/**
 * Singleton logger that stores logs for display in the UI.
 */
object AppLogger {
    
    private const val MAX_LOGS = 200
    private val logs = mutableListOf<LogEntry>()
    private val listeners = mutableListOf<(List<LogEntry>) -> Unit>()
    
    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    ) {
        fun format(): String {
            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date(timestamp))
            return "[$time] [$level] $tag: $message"
        }
    }
    
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        addLog("D", tag, message)
    }
    
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        addLog("I", tag, message)
    }
    
    fun w(tag: String, message: String) {
        Log.w(tag, message)
        addLog("W", tag, message)
    }
    
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
            addLog("E", tag, "$message\n${throwable.stackTraceToString()}")
        } else {
            Log.e(tag, message)
            addLog("E", tag, message)
        }
    }
    
    private fun addLog(level: String, tag: String, message: String) {
        synchronized(logs) {
            logs.add(LogEntry(System.currentTimeMillis(), level, tag, message))
            if (logs.size > MAX_LOGS) {
                logs.removeAt(0)
            }
        }
        notifyListeners()
    }
    
    fun getLogs(): List<LogEntry> {
        synchronized(logs) {
            return logs.toList()
        }
    }
    
    fun clear() {
        synchronized(logs) {
            logs.clear()
        }
        notifyListeners()
    }
    
    fun addListener(listener: (List<LogEntry>) -> Unit) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: (List<LogEntry>) -> Unit) {
        listeners.remove(listener)
    }
    
    private fun notifyListeners() {
        val currentLogs = getLogs()
        listeners.forEach { it(currentLogs) }
    }
}
