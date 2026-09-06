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
    private lateinit var btcDetails: TextView

    private lateinit var goldPrice: TextView
    private lateinit var goldSignal: TextView
    private lateinit var goldDetails: TextView

    private val handler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // আপনার Twelve Data API Key
    private val API_KEY = "404594e1a458416998da981e69787f31"

    private val CHANNEL_ID = "trading_signal_channel"

    private var lastBtcSignal = "WAIT"
    private var lastGoldSignal = "WAIT"

    private val updateRunnable = object : Runnable {
        override fun run() {

            updateMarket("BTC/USD")
            updateMarket("XAU/USD")

            // প্রতি ১ মিনিটে market data check
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        btcPrice = findViewById(R.id.btcPrice)
        btcSignal = findViewById(R.id.btcSignal)
        btcDetails = findViewById(R.id.btcDetails)

        goldPrice = findViewById(R.id.goldPrice)
        goldSignal = findViewById(R.id.goldSignal)
        goldDetails = findViewById(R.id.goldDetails)

        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            if (
                checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
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
                    btcDetails.text =
                        "Confirmation Details:\nAPI Key needed"

                } else {

                    goldPrice.text = "Price: API KEY needed"
                    goldSignal.text = "Signal: WAIT"
                    goldDetails.text =
                        "Confirmation Details:\nAPI Key needed"
                }
            }

            return
        }

        Thread {

            try {

                val encodedSymbol =
                    symbol.replace("/", "%2F")

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

                val response =
                    client.newCall(request).execute()

                val body =
                    response.body?.string()

                if (body.isNullOrEmpty()) {
                    showError(symbol)
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
                    showError(symbol)
                    return@Thread
                }

                val values =
                    json.optJSONArray("values")

                if (
                    values == null ||
                    values.length() < 30
                ) {
                    showError(symbol)
                    return@Thread
                }

                val closes = ArrayList<Double>()
                val opens = ArrayList<Double>()
                val highs = ArrayList<Double>()
                val lows = ArrayList<Double>()

                /*
                 * Twelve Data newest candle আগে দেয়।
                 * তাই oldest -> newest করা হচ্ছে।
                 */
                for (i in values.length() - 1 downTo 0) {

                    val candle =
                        values.getJSONObject(i)

                    opens.add(
                        candle.getString("open").toDouble()
                    )

                    highs.add(
                        candle.getString("high").toDouble()
                    )

                    lows.add(
                        candle.getString("low").toDouble()
                    )

                    closes.add(
                        candle.getString("close").toDouble()
                    )
                }

                val price = closes.last()

                val ema9 =
                    calculateEMA(closes, 9)

                val ema21 =
                    calculateEMA(closes, 21)

                val rsi =
                    calculateRSI(closes, 14)

                val momentum =
                    calculateMomentum(closes, 5)

                val signal =
                    calculateStrongSignal(
                        closes,
                        opens,
                        highs,
                        lows,
                        ema9,
                        ema21,
                        rsi,
                        momentum
                    )

                val details =
                    calculateSignalDetails(
                        closes,
                        opens,
                        highs,
                        lows,
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

                        btcDetails.text =
                            details

                        if (
                            (signal == "BUY" ||
                                    signal == "SELL") &&
                            signal != lastBtcSignal
                        ) {

                            sendNotification(
                                "BTC/USD $signal",
                                "Price: $formattedPrice\nSignal: $signal"
                            )
                        }

                        lastBtcSignal = signal

                    } else {

                        goldPrice.text =
                            "Price: $formattedPrice"

                        goldSignal.text =
                            "Signal: $signal"

                        goldDetails.text =
                            details

                        if (
                            (signal == "BUY" ||
                                    signal == "SELL") &&
                            signal != lastGoldSignal
                        ) {

                            sendNotification(
                                "XAU/USD $signal",
                                "Price: $formattedPrice\nSignal: $signal"
                            )
                        }

                        lastGoldSignal = signal
                    }
                }

            } catch (e: Exception) {

                showError(symbol)
            }

        }.start()
    }

    /*
     * STRONG SIGNAL ENGINE
     *
     * Confirmation:
     * 1. EMA trend
     * 2. EMA strength
     * 3. Price position
     * 4. RSI
     * 5. Momentum
     * 6. Pullback
     * 7. Candle
     */
    private fun calculateStrongSignal(
        closes: List<Double>,
        opens: List<Double>,
        highs: List<Double>,
        lows: List<Double>,
        ema9: Double,
        ema21: Double,
        rsi: Double,
        momentum: Double
    ): String {

        if (closes.size < 30) {
            return "WAIT"
        }

        val price = closes.last()

        val previousClose =
            closes[closes.size - 2]

        val previousEma9 =
            calculateEMA(
                closes.dropLast(1),
                9
            )

        var buyScore = 0
        var sellScore = 0

        // 1. EMA TREND

        if (ema9 > ema21) {
            buyScore++
        }

        if (ema9 < ema21) {
            sellScore++
        }

        // 2. EMA TREND STRENGTH

        if (
            ema9 > ema21 &&
            ema9 > previousEma9
        ) {
            buyScore++
        }

        if (
            ema9 < ema21 &&
            ema9 < previousEma9
        ) {
            sellScore++
        }

        // 3. PRICE POSITION

        if (price > ema9) {
            buyScore++
        }

        if (price < ema9) {
            sellScore++
        }

        // 4. RSI

        if (rsi >= 52.0 && rsi <= 68.0) {
            buyScore++
        }

        if (rsi <= 48.0 && rsi >= 32.0) {
            sellScore++
        }

        if (rsi > 72.0) {
            buyScore--
        }

        if (rsi < 28.0) {
            sellScore--
        }

        // 5. MOMENTUM

        if (momentum > 0) {
            buyScore++
        }

        if (momentum < 0) {
            sellScore++
        }

        // 6. PULLBACK

        val previousDistance =
            previousClose - previousEma9

        val currentDistance =
            price - ema9

        if (
            ema9 > ema21 &&
            previousDistance <= 0 &&
            currentDistance > 0
        ) {
            buyScore += 2
        }

        if (
            ema9 < ema21 &&
            previousDistance >= 0 &&
            currentDistance < 0
        ) {
            sellScore += 2
        }

        // 7. CANDLE

        val lastOpen = opens.last()
        val lastHigh = highs.last()
        val lastLow = lows.last()
        val lastClose = closes.last()

        val candleBody =
            abs(lastClose - lastOpen)

        val upperWick =
            lastHigh - maxOf(lastOpen, lastClose)

        val lowerWick =
            minOf(lastOpen, lastClose) - lastLow

        if (
            lastClose > lastOpen &&
            candleBody > 0 &&
            lowerWick <= candleBody * 1.5
        ) {
            buyScore++
        }

        if (
            lastClose < lastOpen &&
            candleBody > 0 &&
            upperWick <= candleBody * 1.5
        ) {
            sellScore++
        }

        return when {

            buyScore >= 4 &&
                    buyScore > sellScore ->
                "BUY"

            sellScore >= 4 &&
                    sellScore > buyScore ->
                "SELL"

            else ->
                "WAIT"
        }
    }

    /*
     * SIGNAL DETAILS
     *
     * এখানে signal-এর কারণগুলো দেখানো হবে।
     */
    private fun calculateSignalDetails(
        closes: List<Double>,
        opens: List<Double>,
        highs: List<Double>,
        lows: List<Double>,
        ema9: Double,
        ema21: Double,
        rsi: Double,
        momentum: Double
    ): String {

        if (closes.size < 30) {
            return "Confirmation Details:\nNot enough data"
        }

        val price = closes.last()

        val previousClose =
            closes[closes.size - 2]

        val previousEma9 =
            calculateEMA(
                closes.dropLast(1),
                9
            )

        var buyScore = 0
        var sellScore = 0

        // EMA trend

        val emaTrend: String

        if (ema9 > ema21) {

            emaTrend = "Bullish"
            buyScore++

        } else if (ema9 < ema21) {

            emaTrend = "Bearish"
            sellScore++

        } else {

            emaTrend = "Neutral"
        }

        // EMA strength

        val emaStrength: String

        if (
            ema9 > ema21 &&
            ema9 > previousEma9
        ) {

            emaStrength = "Bullish ↑"
            buyScore++

        } else if (
            ema9 < ema21 &&
            ema9 < previousEma9
        ) {

            emaStrength = "Bearish ↓"
            sellScore++

        } else {

            emaStrength = "Weak/Flat"
        }

        // Price position

        val pricePosition: String

        if (price > ema9) {

            pricePosition = "Above EMA9"
            buyScore++

        } else {

            pricePosition = "Below EMA9"
            sellScore++
        }

        // RSI

        val rsiText =
            String.format(
                Locale.US,
                "%.1f",
                rsi
            )

        val rsiStatus: String

        if (rsi >= 52.0 && rsi <= 68.0) {

            rsiStatus = "BUY zone"
            buyScore++

        } else if (rsi <= 48.0 && rsi >= 32.0) {

            rsiStatus = "SELL zone"
            sellScore++

        } else if (rsi > 72.0) {

            rsiStatus = "Overbought"

            buyScore--

        } else if (rsi < 28.0) {

            rsiStatus = "Oversold"

            sellScore--

        } else {

            rsiStatus = "Neutral"
        }

        // Momentum

        val momentumStatus: String

        if (momentum > 0) {

            momentumStatus = "Positive"
            buyScore++

        } else if (momentum < 0) {

            momentumStatus = "Negative"
            sellScore++

        } else {

            momentumStatus = "Flat"
        }

        // Pullback

        val previousDistance =
            previousClose - previousEma9

        val currentDistance =
            price - ema9

        val pullbackStatus: String

        if (
            ema9 > ema21 &&
            previousDistance <= 0 &&
            currentDistance > 0
        ) {

            pullbackStatus = "Confirmed BUY"
            buyScore += 2

        } else if (
            ema9 < ema21 &&
            previousDistance >= 0 &&
            currentDistance < 0
        ) {

            pullbackStatus = "Confirmed SELL"
            sellScore += 2

        } else {

            pullbackStatus = "No fresh pullback"
        }

        // Candle

        val lastOpen = opens.last()
        val lastHigh = highs.last()
        val lastLow = lows.last()
        val lastClose = closes.last()

        val candleBody =
            abs(lastClose - lastOpen)

        val upperWick =
            lastHigh - maxOf(lastOpen, lastClose)

        val lowerWick =
            minOf(lastOpen, lastClose) - lastLow

        val candleStatus: String

        if (
            lastClose > lastOpen &&
            candleBody > 0 &&
            lowerWick <= candleBody * 1.5
        ) {

            candleStatus = "Bullish"
            buyScore++

        } else if (
            lastClose < lastOpen &&
            candleBody > 0 &&
            upperWick <= candleBody * 1.5
        ) {

            candleStatus = "Bearish"
            sellScore++

        } else {

            candleStatus = "Weak/Neutral"
        }

        val signalStrength =
            maxOf(buyScore, sellScore)

        return String.format(
            Locale.US,
            "Confirmation Details:\n" +
                    "EMA 9/21: %s\n" +
                    "EMA Strength: %s\n" +
                    "Price Position: %s\n" +
                    "RSI 14: %s (%s)\n" +
                    "Momentum: %s\n" +
                    "Pullback: %s\n" +
                    "Candle: %s\n" +
                    "Signal Strength: %d",
            emaTrend,
            emaStrength,
            pricePosition,
            rsiText,
            rsiStatus,
            momentumStatus,
            pullbackStatus,
            candleStatus,
            signalStrength
        )
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
                ((prices[i] - ema) * multiplier) +
                        ema
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

        for (
            i in period + 1 until prices.size
        ) {

            val change =
                prices[i] - prices[i - 1]

            val currentGain =
                if (change > 0) change else 0.0

            val currentLoss =
                if (change < 0) abs(change) else 0.0

            averageGain =
                (
                    averageGain * (period - 1) +
                            currentGain
                    ) / period

            averageLoss =
                (
                    averageLoss * (period - 1) +
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

    private fun showError(symbol: String) {

        runOnUiThread {

            if (symbol == "BTC/USD") {

                btcPrice.text = "Price: --"
                btcSignal.text = "Signal: WAIT"
                btcDetails.text =
                    "Confirmation Details:\nMarket data error"

            } else {

                goldPrice.text = "Price: --"
                goldSignal.text = "Signal: WAIT"
                goldDetails.text =
                    "Confirmation Details:\nMarket data error"
            }
        }
    }

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
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
