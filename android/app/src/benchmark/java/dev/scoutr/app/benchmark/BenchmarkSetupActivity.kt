package dev.scoutr.app.benchmark

import android.app.Activity
import android.os.Bundle

/** Seeds only the benchmark build with a local, unreachable bridge pairing. */
class BenchmarkSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSharedPreferences("scoutr_connection", MODE_PRIVATE)
            .edit()
            .putString("host", "http://127.0.0.1:9")
            .putString("token", "benchmark-token")
            .commit()
        finish()
    }
}
