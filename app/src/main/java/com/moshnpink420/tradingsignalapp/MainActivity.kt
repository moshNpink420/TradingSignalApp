package com.moshnpink420.tradingsignalapp

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())

    private val markets = listOf(
        Market("EUR/USD", "EUR/USD", R.id.eurPrice, R.id.eurSignal),
        Market("GBP/USD", "GBP/USD", R.id.gbpPrice, R.id.gbpSignal),
        Market("BTC/USD", "BTC/USD", R.id.btcPrice, R.id.btcSignal),
        Market("XAU/USD", "XAU/USD", R.id.goldPrice, R.id.goldSignal),
        Market("USTEC", "USTEC", R.id.ustecPrice, R.id.ustecSignal),
        Market("USOIL", "USOIL", R.id.usoilPrice, R.id.usoilSignal)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        loadAllPrices()
    }

    private fun loadAllPrices() {

        for (market in markets) {
            loadPrice(market)
        }

        // Refresh every 60 seconds
        handler.postDelayed(object : Runnable {
            override fun run() {
                loadAllPrices()
                handler.postDelayed(this, 60_000)
            }
        }, 60_000)
    }

    private fun loadPrice(market: Market) {

        Thread {

            try {

                val apiKey = BuildConfig.TWELVE_DATA_API_KEY

                if (apiKey.isBlank()) {
                    runOnUiThread {
                        findViewById<TextView>(market.priceId).text =
                            "Price: API Key missing"
                    }
                    return@Thread
                }

                val url =
                    "https://api.twelvedata.com/price" +
                            "?symbol=${market.symbol}" +
                            "&apikey=$apiKey"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->

                    val body = response.body?.string() ?: ""

                    if (!response.isSuccessful) {
                        showError(market, "HTTP ${response.code}")
                        return@use
                    }

                    val json = JSONObject(body)

                    if (json.has("price")) {

                        val price = json.getString("price")

                        runOnUiThread {

                            findViewById<TextView>(market.priceId).text =
                                "Price: $price"

                            // Signal calculation will be added next
                            findViewById<TextView>(market.signalId).text =
                                "WAIT"
                        }

                    } else {

                        val message =
                            json.optString(
                                "message",
                                "Data unavailable"
                            )

                        showError(market, message)
                    }
                }

            } catch (e: Exception) {

                showError(
                    market,
                    "Connection error"
                )
            }

        }.start()
    }

    private fun showError(
        market: Market,
        message: String
    ) {

        runOnUiThread {

            findViewById<TextView>(market.priceId).text =
                "Price: --"

            findViewById<TextView>(market.signalId).text =
                "WAIT"

        }
    }

    override fun onDestroy() {
        super.onDestroy()

        handler.removeCallbacksAndMessages(null)
    }

    data class Market(
        val name: String,
        val symbol: String,
        val priceId: Int,
        val signalId: Int
    )
}
