package com.moshnpink420.tradingsignalapp

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var btcPrice: TextView
    private lateinit var btcSignal: TextView
    private lateinit var goldPrice: TextView
    private lateinit var goldSignal: TextView

    private val handler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // এখানে আপনার Twelve Data API Key বসান
    private val API_KEY = "404594e1a458416998da981e69787f31"

    private val CHANNEL_ID = "trading_signal_channel"

    private var lastBtcSignal = "WAIT"
    private var lastGoldSignal = "WAIT"

    private val updateRunnable = object : Runnable {
        override fun run() {

            updateMarket("BTC/USD")
            updateMarket("XAU/USD")

            // প্রতি ১ মিনিটে update
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        btcPrice = findViewById(R.id.btcPrice)
        btcSignal = findViewById(R.id.btcSignal)

        // আপনার XML-এ XAU-এর ID goldPrice / goldSignal
        goldPrice = findViewById(R.id.goldPrice)
        goldSignal = findViewById(R.id.goldSignal)

        createNotificationChannel()

        // Android 13+ notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            if (
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }

        handler.post(updateRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateRunnable)
        super.onDestroy()
    }

    private fun updateMarket(symbol: String) {

        if (API_KEY == "YOUR_API_KEY") {

            runOnUiThread {

                if (symbol == "BTC/USD") {

                    btcPrice.text = "Price: API KEY needed"
                    btcSignal.text = "Signal: WAIT"

                } else {

                    goldPrice.text = "Price: API KEY needed"
                    goldSignal.text = "Signal: WAIT"
                }
            }

            return
        }

        Thread {

            try {

                val encodedSymbol = symbol.replace("/", "%2F")

                val url =
                    "https://api.twelvedata.com/time_series" +
                            "?symbol=$encodedSymbol" +
                            "&interval=5min" +
                            "&outputsize=50" +
                            "&apikey=$API_KEY"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = client.newCall(request).execute()

                val body = response.body?.string()

                if (body.isNullOrEmpty()) {

                    showError(symbol, "No data")
                    return@Thread
                }

                val json = JSONObject(body)

                if (
                    json.has("code") ||
                    (
                        json.has("status") &&
                        json.optString("status") == "error"
                    )
                ) {

                    val message =
                        json.optString("message", "API Error")

                    showError(symbol, message)
                    return@Thread
                }

                val values = json.optJSONArray("values")

                if (values == null || values.length() < 25) {

                    showError(symbol, "Not enough data")
                    return@Thread
                }

                val closes = ArrayList<Double>()

                for (i in values.length() - 1 downTo 0) {

                    val candle = values.getJSONObject(i)

                    closes.add(
                        candle.getString("close").toDouble()
                    )
                }

                val price = closes.last()

                val ema9 = calculateEMA(closes, 9)
                val ema21 = calculateEMA(closes, 21)
                val rsi = calculateRSI(closes, 14)
                val momentum = calculateMomentum(closes, 5)

                val signal = calculateSignal(
                    price,
                    ema9,
                    ema21,
                    rsi,
                    momentum
                )

                runOnUiThread {

                    val formattedPrice =
                        String.format(
                            Locale.US,
                            "%.2f",
                            price
                        )

                    if (symbol == "BTC/USD") {

                        btcPrice.text =
                            "Price: $formattedPrice"

                        btcSignal.text =
                            "Signal: $signal"

                        if (
                            signal == "BUY" ||
                            signal == "SELL"
                        ) {

                            if (signal != lastBtcSignal) {

                                sendNotification(
                                    "BTC/USD $signal",
                                    "Price: $formattedPrice\nSignal: $signal"
                                )
                            }

                            lastBtcSignal = signal

                        } else {

                            lastBtcSignal = "WAIT"
                        }

                    } else {

                        goldPrice.text =
                            "Price: $formattedPrice"

                        goldSignal.text =
                            "Signal: $signal"

                        if (
                            signal == "BUY" ||
                            signal == "SELL"
                        ) {

                            if (signal != lastGoldSignal) {

                                sendNotification(
                                    "XAU/USD $signal",
                                    "Price: $formattedPrice\nSignal: $signal"
                                )
                            }

                            lastGoldSignal = signal

                        } else {

                            lastGoldSignal = "WAIT"
                        }
                    }
                }

            } catch (e: Exception) {

                showError(
                    symbol,
                    e.message ?: "Connection error"
                )
            }

        }.start()
    }

    private fun calculateEMA(
        prices: List<Double>,
        period: Int
    ): Double {

        if (prices.size < period) {
            return prices.last()
        }

        val multiplier =
            2.0 / (period + 1)

        var ema =
            prices.take(period).average()

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

            if (change >= 0) {
                gain += change
            } else {
                loss += abs(change)
            }
        }

        var averageGain =
            gain / period

        var averageLoss =
            loss / period

        for (i in period + 1 until prices.size) {

            val change =
                prices[i] - prices[i - 1]

            val currentGain =
                if (change > 0) change else 0.0

            val currentLoss =
                if (change < 0) abs(change) else 0.0

            averageGain =
                (
                    (averageGain * (period - 1)) +
                            currentGain
                    ) / period

            averageLoss =
                (
                    (averageLoss * (period - 1)) +
                            currentLoss
                    ) / period
        }

        if (averageLoss == 0.0) {
            return 100.0
        }

        val rs =
            averageGain / averageLoss

        return 100.0 -
                (100.0 / (1.0 + rs))
    }

    private fun calculateMomentum(
        prices: List<Double>,
        candles: Int
    ): Double {

        if (prices.size <= candles) {
            return 0.0
        }

        return prices.last() -
                prices[prices.size - 1 - candles]
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
        }

        if (ema9 < ema21) {
            sellScore++
        }

        // Price vs EMA 9
        if (price > ema9) {
            buyScore++
        }

        if (price < ema9) {
            sellScore++
        }

        // RSI confirmation
        if (rsi >= 50.0 && rsi <= 70.0) {
            buyScore++
        }

        if (rsi <= 50.0 && rsi >= 30.0) {
            sellScore++
        }

        // Momentum confirmation
        if (momentum > 0) {
            buyScore++
        }

        if (momentum < 0) {
            sellScore++
        }

        return when {

            buyScore >= 3 &&
                    buyScore > sellScore ->
                "BUY"

            sellScore >= 3 &&
                    sellScore > buyScore ->
                "SELL"

            else ->
                "WAIT"
        }
    }

    private fun showError(
        symbol: String,
        message: String
    ) {

        runOnUiThread {

            if (symbol == "BTC/USD") {

                btcPrice.text = "Price: --"
                btcSignal.text = "Signal: WAIT"

            } else {

                goldPrice.text = "Price: --"
                goldSignal.text = "Signal: WAIT"
            }
        }
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Trading Signals",
                NotificationManager.IMPORTANCE_HIGH
            )

            channel.description =
                "BTC/USD and XAU/USD BUY/SELL signals"

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

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            if (
                checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
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

        NotificationManagerCompat
            .from(this)
            .notify(
                (System.currentTimeMillis() % 100000).toInt(),
                notification
            )
    }
}
