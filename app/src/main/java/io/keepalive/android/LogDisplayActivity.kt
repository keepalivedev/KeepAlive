package io.keepalive.android

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class LogDisplayActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_display)

        val logTextView: TextView = findViewById(R.id.logTextView)
        logTextView.text = DebugLogger.getLogs().joinToString("\n")

        val swipeRefreshLayout: SwipeRefreshLayout = findViewById(R.id.logDisplaySwipeRefreshLayout)
        swipeRefreshLayout.setOnRefreshListener {
            logTextView.text = DebugLogger.getLogs().joinToString("\n")
            swipeRefreshLayout.isRefreshing = false
        }
    }
}