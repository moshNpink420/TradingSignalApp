package com.moshnpink420.tradingsignalapp

import android.app.Activity
import android.app.NotificationChannel
import android.appক.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var btcPrice: TextView
    private lateinit var btcSignal: TextView
    private lateinit var xauPrice: TextView
    private lateinit var xauSignal: TextView

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())

    // আপনার Twelve Data API Key এখানে বসাবেন
    private val API_KEY = "404594e1a458416998da981e69787f31"

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var btcPrice: TextView
    private lateinit var btcSignal: TextView
    private lateinit var xauPrice: TextView
    private lateinit var xauSignal: TextView

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())

    // আপনার Twelve Data API Key এখানে বসাবেন
    private val API_KEY = "YOUR_API_KEY"

    private var lastBtcSignal = "WAIT"
    private var lastXauSignal = "WAIT"

    private val updateRunnable = object : Runnable {
        override fun run() {
            getMarketData("BTC/USD")
            getMarketData("XAU/USD")

            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        btcPrice = findViewById(R.id.btcPrice)
        btcSignal = findViewById(R.id.btcSignal)

        xauPrice = findViewById(R.id.xauPrice)
        xauSignal = findViewById(R.id.xauSignal)

        createNotificationChannel()

        handler.post(updateRunnable)
    }

    private fun getMarketData(symbol: String) {

        Thread {

            try {

                val url =
                    "https://api.twelvedata.com/time_series" +
                            "?symbol=$symbol" +
                            "&interval=5min" +
                            "&outputsize=50" +
                            "&apikey=$API_KEY"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->

                    val body = response.body?.string() ?: ""

                    if (!response.isSuccessful) {
                        showError(symbol, "HTTP ${response.code}")
                        return@use
                    }

                    val json = JSONObject(body)

                    if (json.has("code")) {
                        val message = json.optString(
                            "message",
                            "API Error"
                        )

                        showError(symbol, message)
                        return@use
                    }

                    val values = json.optJSONArray("values")

                    if (values == null || values.length() < 25) {
                        showError(symbol, "Not enough data")
                        return@use
                    }

                    val closes = ArrayList<Double>()

                    for (i in 0 until values.length()) {
                        val candle = values.getJSONObject(i)
                        val close = candle.optDouble("close", Double.NaN)

                        if (!close.isNaN()) {
                            closes.add(close)
                        }
                    }

                    if (closes.size < 25) {
                        showError(symbol, "Not enough candles")
                        return@use
                    }

                    val price = closes[0]

                    val ema9 = calculateEMA(
                        closes.reversed(),
                        9
                    )

                    val ema21 = calculateEMA(
                        closes.reversed(),
                        21
                    )

                    val rsi = calculateRSI(
                        closes.reversed(),
                        14
                    )

                    val momentum =
                        closes[0] - closes[4]

                    val signal = calculateSignal(
                        price,
                        ema9,
                        ema21,
                        rsi,
                        momentum
                    )

                    updateUI(
                        symbol,
                        price,
                        signal
                    )

                }

            } catch (e: Exception) {

                showError(
                    symbol,
                    e.message ?: "Connection error"
                )
            }

        }.start()
    }

    private fun calculateSignal(
        price: Double,
        ema9: Double,
        ema21: Double,
        rsi: Double,
        momentum: Double
    ): String {

        var buyScore = 0
        var sellScore = 0

        // EMA trend
        if (ema9 > ema21) {
            buyScore++
        } else if (ema9 < ema21) {
            sellScore++
        }

        // Price position
        if (price > ema9) {
            buyScore++
        } else if (price < ema9) {
            sellScore++
        }

        // RSI
        if (rsi >= 52 && rsi <= 70) {
            buyScore++
        }

        if (rsi <= 48 && rsi >= 30) {
            sellScore++
        }

        // Momentum
        if (momentum > 0) {
            buyScore++
        } else if (momentum < 0) {
            sellScore++
        }

        return when {
            buyScore >= 3 && buyScore > sellScore ->
                "BUY"

            sellScore >= 3 && sellScore > buyScore ->
                "SELL"

            else ->
                "WAIT"
        }
    }

    private fun calculateEMA(
        prices: List<Double>,
        period: Int
    ): Double {

        if (prices.size < period) {
            return prices.last()
        }

        val multiplier = 2.0 / (period + 1)

        var ema = prices.take(period).average()

        for (i in period until prices.size) {
            ema =
                ((prices[i] - ema) * multiplier) + ema
        }

        return ema
    }

    private fun calculateRSI(
        prices: List<Double>,
        period: Int
    ): Double {

        if (prices.size <= period) {
            return 50.0
        }

        var gain = 0.0
        var loss = 0.0

        for (i in 1..period) {

            val change =
                prices[i] - prices[i - 1]

            if (change > 0) {
                gain += change
            } else {
                loss += abs(change)
            }
        }

        var avgGain = gain / period
        var avgLoss = loss / period

        for (i in period + 1 until prices.size) {

            val change =
                prices[i] - prices[i - 1]

            val currentGain =
                if (change > 0) change else 0.0

            val currentLoss =
                if (change < 0) abs(change) else 0.0

            avgGain =
                ((avgGain * (period - 1)) + currentGain) / period

            avgLoss =
                ((avgLoss * (period - 1)) + currentLoss) / period
        }

        if (avgLoss == 0.0) {
            return 100.0
        }

        val rs = avgGain / avgLoss

        return 100.0 - (100.0 / (1.0 + rs))
    }

    private fun updateUI(
        symbol: String,
        price: Double,
        signal: String
    ) {

        runOnUiThread {

            if (symbol == "BTC/USD") {

                btcPrice.text =
                    String.format(
                        java.util.Locale.US,
                        "Price: %.2f",
                        price
                    )

                btcSignal.text =
                    "Signal: $signal"

                if (
                    signal != "WAIT" &&
                    signal != lastBtcSignal
                ) {
                    sendNotification(
                        "BTC/USD Signal",
                        "BTC/USD: $signal\nPrice: %.2f"
                            .format(price)
                    )
                }

                lastBtcSignal = signal

            } else if (symbol == "XAU/USD") {

                xauPrice.text =
                    String.format(
                        java.util.Locale.US,
                        "Price: %.2f",
                        price
                    )

                xauSignal.text =
                    "Signal: $signal"

                if (
                    signal != "WAIT" &&
                    signal != lastXauSignal
                ) {
                    sendNotification(
                        "XAU/USD Signal",
                        "XAU/USD: $signal\nPrice: %.2f"
                            .format(price)
                    )
                }

                lastXauSignal = signal
            }
        }
    }

    private fun showError(
        symbol: String,
        error: String
    ) {

        runOnUiThread {

            if (symbol == "BTC/USD") {
                btcPrice.text = "Price: --"
                btcSignal.text = "Error: $error"
            } else {
                xauPrice.text = "Price: --"
                xauSignal.text = "Error: $error"
            }
        }
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                "trading_signal",
                "Trading Signals",
                NotificationManager.IMPORTANCE_HIGH
            )

            channel.description =
                "BUY and SELL trading alerts"

            val manager =
                getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun sendNotification(
        title: String,
        message: String
    ) {

        val notification =
            NotificationCompat.Builder(
                this,
                "trading_signal"
            )
                .setSmallIcon(
                    android.R.drawable.ic_dialog_info
                )
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(message)
                )
                .setPriority(
                    NotificationCompat.PRIORITY_HIGH
                )
                .setAutoCancel(true)
                .build()

        val manager =
            getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

        manager.notify(
            (System.currentTimeMillis() % 100000).toInt(),
            notification
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
    }
}YOU"

    private var lastBtcSignal = "WAIT"
    private var lastXauSignal = "WAIT"

    private val updateRunnable = object : Runnable {
        override fun run() {
            getMarketData("BTC/USD")
            getMarketData("XAU/USD")

            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        btcPrice = findViewById(R.id.btcPrice)
        btcSignal = findViewById(R.id.btcSignal)

        xauPrice = findViewById(R.id.xauPrice)
        xauSignal = findViewById(R.id.xauSignal)

        createNotificationChannel()

        handler.post(updateRunnable)
    }

    private fun getMarketData(symbol: String) {

        Thread {

            try {

                val url =
                    "https://api.twelvedata.com/time_series" +
                            "?symbol=$symbol" +
                            "&interval=5min" +
                            "&outputsize=50" +
                            "&apikey=$API_KEY"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->

                    val body = response.body?.string() ?: ""

                    if (!response.isSuccessful) {
                        showError(symbol, "HTTP ${response.code}")
                        return@use
                    }

                    val json = JSONObject(body)

                    if (json.has("code")) {
                        val message = json.optString(
                            "message",
                            "API Error"
                        )

                        showError(symbol, message)
                        return@use
                    }

                    val values = json.optJSONArray("values")

                    if (values == null || values.length() < 25) {
                        showError(symbol, "Not enough data")
                        return@use
                    }

                    val closes = ArrayList<Double>()

                    for (i in 0 until values.length()) {
                        val candle = values.getJSONObject(i)
                        val close = candle.optDouble("close", Double.NaN)

                        if (!close.isNaN()) {
                            closes.add(close)
                        }
                    }

                    if (closes.size < 25) {
                        showError(symbol, "Not enough candles")
                        return@use
                    }

                    val price = closes[0]

                    val ema9 = calculateEMA(
                        closes.reversed(),
                        9
                    )

                    val ema21 = calculateEMA(
                        closes.reversed(),
                        21
                    )

                    val rsi = calculateRSI(
                        closes.reversed(),
                        14
                    )

                    val momentum =
                        closes[0] - closes[4]

                    val signal = calculateSignal(
                        price,
                        ema9,
                        ema21,
                        rsi,
                        momentum
                    )

                    updateUI(
                        symbol,
                        price,
                        signal
                    )

                }

            } catch (e: Exception) {

                showError(
                    symbol,
                    e.message ?: "Connection error"
                )
            }

        }.start()
    }

    private fun calculateSignal(
        price: Double,
        ema9: Double,
        ema21: Double,
        rsi: Double,
        momentum: Double
    ): String {

        var buyScore = 0
        var sellScore = 0

        // EMA trend
        if (ema9 > ema21) {
            buyScore++
        } else if (ema9 < ema21) {
            sellScore++
        }

        // Price position
        if (price > ema9) {
            buyScore++
        } else if (price < ema9) {
            sellScore++
        }

        // RSI
        if (rsi >= 52 && rsi <= 70) {
            buyScore++
        }

        if (rsi <= 48 && rsi >= 30) {
            sellScore++
        }

        // Momentum
        if (momentum > 0) {
            buyScore++
        } else if (momentum < 0) {
            sellScore++
        }

        return when {
            buyScore >= 3 && buyScore > sellScore ->
                "BUY"

            sellScore >= 3 && sellScore > buyScore ->
                "SELL"

            else ->
                "WAIT"
        }
    }

    private fun calculateEMA(
        prices: List<Double>,
        period: Int
    ): Double {

        if (prices.size < period) {
            return prices.last()
        }

        val multiplier = 2.0 / (period + 1)

        var ema = prices.take(period).average()

        for (i in period until prices.size) {
            ema =
                ((prices[i] - ema) * multiplier) + ema
        }

        return ema
    }

    private fun calculateRSI(
        prices: List<Double>,
        period: Int
    ): Double {

        if (prices.size <= period) {
            return 50.0
        }

        var gain = 0.0
        var loss = 0.0

        for (i in 1..period) {

            val change =
                prices[i] - prices[i - 1]

            if (change > 0) {
                gain += change
            } else {
                loss += abs(change)
            }
        }

        var avgGain = gain / period
        var avgLoss = loss / period

        for (i in period + 1 until prices.size) {

            val change =
                prices[i] - prices[i - 1]

            val currentGain =
                if (change > 0) change else 0.0

            val currentLoss =
                if (change < 0) abs(change) else 0.0

            avgGain =
                ((avgGain * (period - 1)) + currentGain) / period

            avgLoss =
                ((avgLoss * (period - 1)) + currentLoss) / period
        }

        if (avgLoss == 0.0) {
            return 100.0
        }

        val rs = avgGain / avgLoss

        return 100.0 - (100.0 / (1.0 + rs))
    }

    private fun updateUI(
        symbol: String,
        price: Double,
        signal: String
    ) {

        runOnUiThread {

            if (symbol == "BTC/USD") {

                btcPrice.text =
                    String.format(
                        java.util.Locale.US,
                        "Price: %.2f",
                        price
                    )

                btcSignal.text =
                    "Signal: $signal"

                if (
                    signal != "WAIT" &&
                    signal != lastBtcSignal
                ) {
                    sendNotification(
                        "BTC/USD Signal",
                        "BTC/USD: $signal\nPrice: %.2f"
                            .format(price)
                    )
                }

                lastBtcSignal = signal

            } else if (symbol == "XAU/USD") {

                xauPrice.text =
                    String.format(
                        java.util.Locale.US,
                        "Price: %.2f",
                        price
                    )

                xauSignal.text =
                    "Signal: $signal"

                if (
                    signal != "WAIT" &&
                    signal != lastXauSignal
                ) {
                    sendNotification(
                        "XAU/USD Signal",
                        "XAU/USD: $signal\nPrice: %.2f"
                            .format(price)
                    )
                }

                lastXauSignal = signal
            }
        }
    }

    private fun showError(
        symbol: String,
        error: String
    ) {

        runOnUiThread {

            if (symbol == "BTC/USD") {
                btcPrice.text = "Price: --"
                btcSignal.text = "Error: $error"
            } else {
                xauPrice.text = "Price: --"
                xauSignal.text = "Error: $error"
            }
        }
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                "trading_signal",
                "Trading Signals",
                NotificationManager.IMPORTANCE_HIGH
            )

            channel.description =
                "BUY and SELL trading alerts"

            val manager =
                getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun sendNotification(
        title: String,
        message: String
    ) {

        val notification =
            NotificationCompat.Builder(
                this,
                "trading_signal"
            )
                .setSmallIcon(
                    android.R.drawable.ic_dialog_info
                )
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(message)
                )
                .setPriority(
                    NotificationCompat.PRIORITY_HIGH
                )
                .setAutoCancel(true)
                .build()

        val manager =
            getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

        manager.notify(
            (System.currentTimeMillis() % 100000).toInt(),
            notification
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
    }
}
