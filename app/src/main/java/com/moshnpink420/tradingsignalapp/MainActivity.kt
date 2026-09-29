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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

    // API key is read from BuildConfig.
    // Your existing Gradle setup should provide TWELVE_DATA_API_KEY.
    private val API_KEY = BuildConfig.TWELVE_DATA_API_KEY

    private val CHANNEL_ID = "trading_signal_channel"
    private var lastBtcSignal = "WAIT"
    private var lastGoldSignal = "WAIT"

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateMarket("BTC/USD")
            updateMarket("XAU/USD")
            handler.postDelayed(this, 5 * 60 * 1000L)
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

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
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

        handler.post(updateRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateRunnable)
        super.onDestroy()
    }

    private fun updateMarket(symbol: String) {

        if (API_KEY.isBlank()) {
            showError(symbol, "API key missing")
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

                val request =
                    Request.Builder()
                        .url(url)
                        .get()
                        .build()

                client.newCall(request).execute().use { response ->

                    val body =
                        response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        showError(
                            symbol,
                            "HTTP ${response.code}: ${extractApiError(body)}"
                        )
                        return@use
                    }

                    if (body.isBlank()) {
                        showError(symbol, "Empty API response")
                        return@use
                    }

                    val json = JSONObject(body)

                    if (
                        json.optString("status")
                            .equals("error", true) ||
                        json.has("code")
                    ) {
                        showError(
                            symbol,
                            "API error: ${extractApiError(body)}"
                        )
                        return@use
                    }

                    val values =
                        json.optJSONArray("values")

                    if (
                        values == null ||
                        values.length() < 30
                    ) {
                        showError(
                            symbol,
                            "Not enough 5-minute candle data"
                        )
                        return@use
                    }

                    val opens = ArrayList<Double>()
                    val highs = ArrayList<Double>()
                    val lows = ArrayList<Double>()
                    val closes = ArrayList<Double>()
                    val times = ArrayList<String>()

                    // Twelve Data returns newest -> oldest.
                    // Convert to oldest -> newest.
                    for (
                        i in values.length() - 1 downTo 0
                    ) {

                        val candle =
                            values.getJSONObject(i)

                        opens.add(
                            candle.optString("open")
                                .toDoubleOrNull() ?: 0.0
                        )

                        highs.add(
                            candle.optString("high")
                                .toDoubleOrNull() ?: 0.0
                        )

                        lows.add(
                            candle.optString("low")
                                .toDoubleOrNull() ?: 0.0
                        )

                        closes.add(
                            candle.optString("close")
                                .toDoubleOrNull() ?: 0.0
                        )

                        times.add(
                            candle.optString(
                                "datetime",
                                "unknown"
                            )
                        )
                    }

                    val price =
                        closes.last()

                    val latestCandleTime =
                        times.last()

                    // ====================================================
                    // INDICATORS
                    // ====================================================

                    val ema9 =
                        calculateEMA(
                            closes,
                            9
                        )

                    val ema21 =
                        calculateEMA(
                            closes,
                            21
                        )

                    val rsi =
                        calculateRSI(
                            closes,
                            14
                        )

                    val momentum =
                        calculateMomentum(
                            closes,
                            5
                        )

                    // ====================================================
                    // SUPPORT / RESISTANCE
                    // ====================================================

                    val support =
                        calculateSupport(lows)

                    val resistance =
                        calculateResistance(highs)

                    // ====================================================
                    // LIQUIDITY
                    // ====================================================

                    val liquidity =
                        detectLiquidity(
                            highs,
                            lows,
                            closes
                        )

                    // ====================================================
                    // PRICE ACTION
                    // ====================================================

                    val priceAction =
                        detectPriceAction(
                            opens,
                            highs,
                            lows,
                            closes
                        )

                    // ====================================================
                    // FINAL SIGNAL
                    // ====================================================

                    val analysis =
                        calculateSignal(
                            closes,
                            opens,
                            highs,
                            lows,
                            ema9,
                            ema21,
                            rsi,
                            momentum,
                            support,
                            resistance,
                            liquidity,
                            priceAction
                        )

                    // ====================================================
                    // DETAILS
                    // ====================================================

                    val details =
                        calculateSignalDetails(
                            closes,
                            opens,
                            highs,
                            lows,
                            ema9,
                            ema21,
                            rsi,
                            momentum,
                            latestCandleTime,
                            support,
                            resistance,
                            liquidity,
                            priceAction,
                            analysis
                        )

                    // ====================================================
                    // UI
                    // ====================================================

                    runOnUiThread {

                        val formattedPrice =
                            String.format(
                                Locale.US,
                                "%.2f",
                                price
                            )

                        if (
                            symbol == "BTC/USD"
                        ) {

                            btcPrice.text =
                                "5m Close: $formattedPrice"

                            btcSignal.text =
                                "Signal: ${analysis.signal}  |  Score: ${analysis.score}/100"

                            btcDetails.text =
                                details

                            if (
                                (
                                    analysis.signal == "BUY" ||
                                    analysis.signal == "SELL"
                                ) &&
                                analysis.signal != lastBtcSignal
                            ) {

                                sendNotification(
                                    "BTC/USD ${analysis.signal}",
                                    "5m Close: $formattedPrice\n" +
                                            "${analysis.label} (${analysis.score}/100)"
                                )
                            }

                            lastBtcSignal =
                                analysis.signal

                        } else {

                            goldPrice.text =
                                "5m Close: $formattedPrice"

                            goldSignal.text =
                                "Signal: ${analysis.signal}  |  Score: ${analysis.score}/100"

                            goldDetails.text =
                                details

                            if (
                                (
                                    analysis.signal == "BUY" ||
                                    analysis.signal == "SELL"
                                ) &&
                                analysis.signal != lastGoldSignal
                            ) {

                                sendNotification(
                                    "XAU/USD ${analysis.signal}",
                                    "5m Close: $formattedPrice\n" +
                                            "${analysis.label} (${analysis.score}/100)"
                                )
                            }

                            lastGoldSignal =
                                analysis.signal
                        }
                    }
                }

            } catch (
                e: Exception
            ) {

                showError(
                    symbol,
                    "${e.javaClass.simpleName}: " +
                            (
                                e.message
                                    ?: "Unknown error"
                                )
                )
            }

        }.start()
    }

    // ============================================================
    // DATA CLASSES
    // ============================================================

    private data class LiquidityResult(
        val sellSideSweep: Boolean,
        val buySideSweep: Boolean,
        val text: String
    )

    private data class PriceActionResult(
        val bullishPoints: Int,
        val bearishPoints: Int,
        val pattern: String,
        val structure: String,
        val text: String
    )

    private data class SignalResult(
        val signal: String,
        val score: Int,
        val label: String,
        val buyScore: Int,
        val sellScore: Int
    )

    // ============================================================
    // SUPPORT
    // ============================================================

    private fun calculateSupport(
        lows: List<Double>
    ): Double {

        if (lows.size < 5) {
            return 0.0
        }

        val lookback =
            min(
                20,
                lows.size - 1
            )

        val start =
            lows.size - 1 - lookback

        var support =
            Double.MAX_VALUE

        for (
            i in start until lows.size - 1
        ) {

            support =
                min(
                    support,
                    lows[i]
                )
        }

        return if (
            support == Double.MAX_VALUE
        ) {
            0.0
        } else {
            support
        }
    }

    // ============================================================
    // RESISTANCE
    // ============================================================

    private fun calculateResistance(
        highs: List<Double>
    ): Double {

        if (highs.size < 5) {
            return 0.0
        }

        val lookback =
            min(
                20,
                highs.size - 1
            )

        val start =
            highs.size - 1 - lookback

        var resistance =
            Double.MIN_VALUE

        for (
            i in start until highs.size - 1
        ) {

            resistance =
                max(
                    resistance,
                    highs[i]
                )
        }

        return if (
            resistance == Double.MIN_VALUE
        ) {
            0.0
        } else {
            resistance
        }
    }

    // ============================================================
    // LIQUIDITY
    // ============================================================

    private fun detectLiquidity(
        highs: List<Double>,
        lows: List<Double>,
        closes: List<Double>
    ): LiquidityResult {

        if (closes.size < 10) {

            return LiquidityResult(
                false,
                false,
                "Not enough data"
            )
        }

        val last =
            closes.lastIndex

        val lookback =
            min(
                20,
                last
            )

        val start =
            last - lookback

        var previousLow =
            Double.MAX_VALUE

        var previousHigh =
            Double.MIN_VALUE

        for (
            i in start until last
        ) {

            previousLow =
                min(
                    previousLow,
                    lows[i]
                )

            previousHigh =
                max(
                    previousHigh,
                    highs[i]
                )
        }

        // Price goes below old low but closes back above it.
        val sellSideSweep =
            lows[last] < previousLow &&
                    closes[last] > previousLow

        // Price goes above old high but closes back below it.
        val buySideSweep =
            highs[last] > previousHigh &&
                    closes[last] < previousHigh

        val text =
            when {

                sellSideSweep &&
                        buySideSweep ->
                    "Both-side sweep"

                sellSideSweep ->
                    "Sell-side swept (bullish clue)"

                buySideSweep ->
                    "Buy-side swept (bearish clue)"

                else ->
                    "No liquidity sweep"
            }

        return LiquidityResult(
            sellSideSweep,
            buySideSweep,
            text
        )
    }

    // ============================================================
    // PRICE ACTION
    // ============================================================

    private fun detectPriceAction(
        opens: List<Double>,
        highs: List<Double>,
        lows: List<Double>,
        closes: List<Double>
    ): PriceActionResult {

        if (closes.size < 5) {

            return PriceActionResult(
                0,
                0,
                "Not enough data",
                "Unknown",
                "Not enough data"
            )
        }

        val i =
            closes.lastIndex

        val p =
            i - 1

        val o =
            opens[i]

        val h =
            highs[i]

        val l =
            lows[i]

        val c =
            closes[i]

        val po =
            opens[p]

        val ph =
            highs[p]

        val pl =
            lows[p]

        val pc =
            closes[p]

        val body =
            abs(
                c - o
            )

        val safeBody =
            max(
                body,
                1e-10
            )

        val upperWick =
            h -
                    max(
                        o,
                        c
                    )

        val lowerWick =
            min(
                o,
                c
            ) -
                    l

        var bullish =
            0

        var bearish =
            0

        val patterns =
            ArrayList<String>()

        // ========================================================
        // BULLISH ENGULFING
        // ========================================================

        val bullishEngulfing =
            pc < po &&
                    c > o &&
                    o <= pc &&
                    c >= po

        if (
            bullishEngulfing
        ) {

            bullish += 3

            patterns.add(
                "Bullish Engulfing"
            )
        }

        // ========================================================
        // BEARISH ENGULFING
        // ========================================================

        val bearishEngulfing =
            pc > po &&
                    c < o &&
                    o >= pc &&
                    c <= po

        if (
            bearishEngulfing
        ) {

            bearish += 3

            patterns.add(
                "Bearish Engulfing"
            )
        }

        // ========================================================
        // BULLISH PIN / REJECTION
        // ========================================================

        val bullishPin =
            lowerWick >= safeBody * 2.0 &&
                    upperWick <= safeBody &&
                    c >
                    l +
                    (h - l) * 0.55

        if (
            bullishPin
        ) {

            bullish += 2

            patterns.add(
                "Bullish Pin/Rejection"
            )
        }

        // ========================================================
        // BEARISH PIN / REJECTION
        // ========================================================

        val bearishPin =
            upperWick >= safeBody * 2.0 &&
                    lowerWick <= safeBody &&
                    c <
                    l +
                    (h - l) * 0.45

        if (
            bearishPin
        ) {

            bearish += 2

            patterns.add(
                "Bearish Pin/Rejection"
            )
        }

        // ========================================================
        // INSIDE BAR
        // ========================================================

        val insideBar =
            h < ph &&
                    l > pl

        if (
            insideBar
        ) {

            patterns.add(
                "Inside Bar"
            )

            if (
                c > o
            ) {
                bullish++
            }

            if (
                c < o
            ) {
                bearish++
            }
        }

        // ========================================================
        // STRONG BODY
        // ========================================================

        val range =
            max(
                h - l,
                1e-10
            )

        val bodyRatio =
            body / range

        if (
            bodyRatio >= 0.65
        ) {

            if (
                c > o
            ) {

                bullish++

                patterns.add(
                    "Strong Bullish Body"
                )

            } else if (
                c < o
            ) {

                bearish++

                patterns.add(
                    "Strong Bearish Body"
                )
            }
        }

        // ========================================================
        // MARKET STRUCTURE
        // ========================================================

        val h1 =
            highs[i - 1]

        val h2 =
            highs[i - 2]

        val l1 =
            lows[i - 1]

        val l2 =
            lows[i - 2]

        val higherStructure =
            h > h1 &&
                    h1 >= h2 &&
                    l > l1 &&
                    l1 >= l2

        val lowerStructure =
            h < h1 &&
                    h1 <= h2 &&
                    l < l1 &&
                    l1 <= l2

        val structure =
            when {

                higherStructure -> {

                    bullish += 2

                    "Higher High / Higher Low"
                }

                lowerStructure -> {

                    bearish += 2

                    "Lower High / Lower Low"
                }

                else ->
                    "Mixed/Range"
            }

        val patternText =
            if (
                patterns.isEmpty()
            ) {
                "No clear pattern"
            } else {
                patterns.joinToString(
                    " + "
                )
            }

        val text =
            "Pattern: $patternText | Structure: $structure"

        return PriceActionResult(
            bullish,
            bearish,
            patternText,
            structure,
            text
        )
    }

    // ============================================================
    // SIGNAL ENGINE
    // ============================================================

    private fun calculateSignal(
        closes: List<Double>,
        opens: List<Double>,
        highs: List<Double>,
        lows: List<Double>,
        ema9: Double,
        ema21: Double,
        rsi: Double,
        momentum: Double,
        support: Double,
        resistance: Double,
        liquidity: LiquidityResult,
        priceAction: PriceActionResult
    ): SignalResult {

        if (closes.size < 30) {

            return SignalResult(
                "WAIT",
                0,
                "WAIT",
                0,
                0
            )
        }

        val price =
            closes.last()

        val previousClose =
            closes[
                closes.lastIndex - 1
            ]

        val previousEma9 =
            calculateEMA(
                closes.dropLast(1),
                9
            )

        var buy =
            0.0

        var sell =
            0.0

        // ========================================================
        // 1. TREND = 15
        // ========================================================

        if (
            ema9 > ema21
        ) {
            buy += 15.0
        }

        if (
            ema9 < ema21
        ) {
            sell += 15.0
        }

        // ========================================================
        // 2. EMA STRENGTH = 10
        // ========================================================

        if (
            ema9 > ema21 &&
            ema9 > previousEma9
        ) {
            buy += 10.0
        }

        if (
            ema9 < ema21 &&
            ema9 < previousEma9
        ) {
            sell += 10.0
        }

        // ========================================================
        // 3. PRICE POSITION = 10
        // ========================================================

        if (
            price > ema9
        ) {
            buy += 10.0
        }

        if (
            price < ema9
        ) {
            sell += 10.0
        }

        // ========================================================
        // 4. RSI = 10
        // ========================================================

        if (
            rsi in 52.0..68.0
        ) {

            buy += 10.0

        } else if (
            rsi in 32.0..48.0
        ) {

            sell += 10.0

        } else if (
            rsi > 72.0
        ) {

            buy -= 5.0

        } else if (
            rsi < 28.0
        ) {

            sell -= 5.0
        }

        // ========================================================
        // 5. MOMENTUM = 10
        // ========================================================

        if (
            momentum > 0
        ) {
            buy += 10.0
        }

        if (
            momentum < 0
        ) {
            sell += 10.0
        }

        // ========================================================
        // 6. PULLBACK = 10
        // ========================================================

        val previousDistance =
            previousClose -
                    previousEma9

        val currentDistance =
            price -
                    ema9

        if (
            ema9 > ema21 &&
            previousDistance <= 0 &&
            currentDistance > 0
        ) {

            buy += 10.0
        }

        if (
            ema9 < ema21 &&
            previousDistance >= 0 &&
            currentDistance < 0
        ) {

            sell += 10.0
        }

        // ========================================================
        // 7. CANDLE = 10
        // ========================================================

        val o =
            opens.last()

        val h =
            highs.last()

        val l =
            lows.last()

        val c =
            closes.last()

        val body =
            abs(
                c - o
            )

        val upper =
            h -
                    max(
                        o,
                        c
                    )

        val lower =
            min(
                o,
                c
            ) -
                    l

        if (
            c > o &&
            body > 0 &&
            lower <= body * 1.5
        ) {

            buy += 10.0
        }

        if (
            c < o &&
            body > 0 &&
            upper <= body * 1.5
        ) {

            sell += 10.0
        }

        // ========================================================
        // 8. PRICE ACTION = 15
        // ========================================================

        if (
            priceAction.bullishPoints >
            priceAction.bearishPoints
        ) {

            buy += min(
                15.0,
                priceAction.bullishPoints * 3.0
            )

        } else if (
            priceAction.bearishPoints >
            priceAction.bullishPoints
        ) {

            sell += min(
                15.0,
                priceAction.bearishPoints * 3.0
            )
        }

        // ========================================================
        // 9. SUPPORT / RESISTANCE = 5
        // ========================================================

        val srNearPct =
            0.0035

        if (
            resistance > 0 &&
            abs(resistance - price) /
            resistance <= srNearPct
        ) {

            sell += 5.0
        }

        if (
            support > 0 &&
            abs(price - support) /
            support <= srNearPct
        ) {

            buy += 5.0
        }

        // ========================================================
        // 10. LIQUIDITY = 5
        // ========================================================

        if (
            liquidity.sellSideSweep &&
            !liquidity.buySideSweep
        ) {

            buy += 5.0
        }

        if (
            liquidity.buySideSweep &&
            !liquidity.sellSideSweep
        ) {

            sell += 5.0
        }

        buy =
            buy.coerceIn(
                0.0,
                100.0
            )

        sell =
            sell.coerceIn(
                0.0,
                100.0
            )

        val roundedBuy =
            buy.roundToInt()

        val roundedSell =
            sell.roundToInt()

        // ========================================================
        // FINAL FILTERS
        // ========================================================

        val nearResistance =
            resistance > 0 &&
                    resistance > price &&
                    (
                        resistance - price
                        ) / price <= srNearPct

        val nearSupport =
            support > 0 &&
                    support < price &&
                    (
                        price - support
                        ) / price <= srNearPct

        var signal =
            "WAIT"

        // Need at least 70 and a 10-point advantage.
        if (
            buy >= 70.0 &&
            buy >= sell + 10.0 &&
            !nearResistance
        ) {

            signal =
                "BUY"
        }

        if (
            sell >= 70.0 &&
            sell >= buy + 10.0 &&
            !nearSupport
        ) {

            signal =
                "SELL"
        }

        // Liquidity against the candidate direction.
        if (
            signal == "BUY" &&
            liquidity.buySideSweep &&
            !liquidity.sellSideSweep
        ) {

            signal =
                "WAIT"
        }

        if (
            signal == "SELL" &&
            liquidity.sellSideSweep &&
            !liquidity.buySideSweep
        ) {

            signal =
                "WAIT"
        }

        // Conflicting price action.
        if (
            signal == "BUY" &&
            priceAction.bearishPoints >
            priceAction.bullishPoints + 2
        ) {

            signal =
                "WAIT"
        }

        if (
            signal == "SELL" &&
            priceAction.bullishPoints >
            priceAction.bearishPoints + 2
        ) {

            signal =
                "WAIT"
        }

        val score =
            when (signal) {

                "BUY" ->
                    roundedBuy

                "SELL" ->
                    roundedSell

                else ->
                    max(
                        roundedBuy,
                        roundedSell
                    )
            }

        val label =
            when {

                score >= 85 ->
                    "VERY STRONG"

                score >= 75 ->
                    "STRONG"

                score >= 65 ->
                    "GOOD"

                score >= 55 ->
                    "MODERATE"

                else ->
                    "WAIT"
            }

        return SignalResult(
            signal,
            score,
            label,
            roundedBuy,
            roundedSell
        )
    }

    // ============================================================
    // SIGNAL DETAILS
    // ============================================================

    private fun calculateSignalDetails(
        closes: List<Double>,
        opens: List<Double>,
        highs: List<Double>,
        lows: List<Double>,
        ema9: Double,
        ema21: Double,
        rsi: Double,
        momentum: Double,
        latestCandleTime: String,
        support: Double,
        resistance: Double,
        liquidity: LiquidityResult,
        priceAction: PriceActionResult,
        analysis: SignalResult
    ): String {

        if (
            closes.size < 30
        ) {

            return "Confirmation Details:\n" +
                    "Not enough data"
        }

        val price =
            closes.last()

        val previousClose =
            closes[
                closes.lastIndex - 1
            ]

        val previousEma9 =
            calculateEMA(
                closes.dropLast(1),
                9
            )

        val emaTrend =
            when {

                ema9 > ema21 ->
                    "Bullish"

                ema9 < ema21 ->
                    "Bearish"

                else ->
                    "Neutral"
            }

        val emaStrength =
            when {

                ema9 > ema21 &&
                        ema9 > previousEma9 ->
                    "Bullish â†‘"

                ema9 < ema21 &&
                        ema9 < previousEma9 ->
                    "Bearish â†“"

                else ->
                    "Weak/Flat"
            }

        val pricePosition =
            if (
                price > ema9
            ) {
                "Above EMA9"
            } else {
                "Below EMA9"
            }

        val rsiText =
            String.format(
                Locale.US,
                "%.1f",
                rsi
            )

        val rsiStatus =
            when {

                rsi in 52.0..68.0 ->
                    "BUY zone"

                rsi in 32.0..48.0 ->
                    "SELL zone"

                rsi > 72.0 ->
                    "Overbought"

                rsi < 28.0 ->
                    "Oversold"

                else ->
                    "Neutral"
            }

        val momentumStatus =
            when {

                momentum > 0 ->
                    "Positive"

                momentum < 0 ->
                    "Negative"

                else ->
                    "Flat"
            }

        val previousDistance =
            previousClose -
                    previousEma9

        val currentDistance =
            price -
                    ema9

        val pullback =
            when {

                ema9 > ema21 &&
                        previousDistance <= 0 &&
                        currentDistance > 0 ->
                    "Confirmed BUY"

                ema9 < ema21 &&
                        previousDistance >= 0 &&
                        currentDistance < 0 ->
                    "Confirmed SELL"

                else ->
                    "No fresh pullback"
            }

        val o =
            opens.last()

        val h =
            highs.last()

        val l =
            lows.last()

        val c =
            closes.last()

        val body =
            abs(
                c - o
            )

        val upper =
            h -
                    max(
                        o,
                        c
                    )

        val lower =
            min(
                o,
                c
            ) -
                    l

        val candle =
            when {

                c > o &&
                        body > 0 &&
                        lower <= body * 1.5 ->
                    "Bullish"

                c < o &&
                        body > 0 &&
                        upper <= body * 1.5 ->
                    "Bearish"

                else ->
                    "Weak/Neutral"
            }

        val supportText =
            if (
                support > 0
            ) {

                String.format(
                    Locale.US,
                    "%.2f",
                    support
                )

            } else {
                "--"
            }

        val resistanceText =
            if (
                resistance > 0
            ) {

                String.format(
                    Locale.US,
                    "%.2f",
                    resistance
                )

            } else {
                "--"
            }

        val supportDistance =
            if (
                support > 0
            ) {

                String.format(
                    Locale.US,
                    "%.2f%% above",
                    (
                        (price - support) /
                                support
                        ) * 100.0
                )

            } else {
                "--"
            }

        val resistanceDistance =
            if (
                resistance > 0
            ) {

                String.format(
                    Locale.US,
                    "%.2f%% below",
                    (
                        (resistance - price) /
                                resistance
                        ) * 100.0
                )

            } else {
                "--"
            }

        return String.format(
            Locale.US,

            "Confirmation Details:\n" +
                    "5m Candle: %s\n" +
                    "EMA 9/21: %s\n" +
                    "EMA Strength: %s\n" +
                    "Price Position: %s\n" +
                    "RSI 14: %s (%s)\n" +
                    "Momentum: %s\n" +
                    "Pullback: %s\n" +
                    "Candle: %s\n" +
                    "\n" +
                    "PRICE ACTION:\n" +
                    "%s\n" +
                    "Pattern: %s\n" +
                    "Structure: %s\n" +
                    "\n" +
                    "Support: %s (%s)\n" +
                    "Resistance: %s (%s)\n" +
                    "Liquidity: %s\n" +
                    "\n" +
                    "BUY Score: %d/100\n" +
                    "SELL Score: %d/100\n" +
                    "Signal: %s\n" +
                    "Signal Strength: %d/100\n" +
                    "Strength: %s",

            latestCandleTime,
            emaTrend,
            emaStrength,
            pricePosition,
            rsiText,
            rsiStatus,
            momentumStatus,
            pullback,
            candle,
            priceAction.text,
            priceAction.pattern,
            priceAction.structure,
            supportText,
            supportDistance,
            resistanceText,
            resistanceDistance,
            liquidity.text,
            analysis.buyScore,
            analysis.sellScore,
            analysis.signal,
            analysis.score,
            analysis.label
        )
    }

    // ============================================================
    // EMA
    // ============================================================

    private fun calculateEMA(
        prices: List<Double>,
        period: Int
    ): Double {

        if (
            prices.isEmpty()
        ) {
            return 0.0
        }

        if (
            prices.size < period
        ) {
            return prices.last()
        }

        val multiplier =
            2.0 /
                    (period + 1)

        var ema =
            prices
                .take(period)
                .average()

        for (
            i in period until prices.size
        ) {

            ema =
                (
                    (prices[i] - ema) *
                            multiplier
                    ) + ema
        }

        return ema
    }

    // ============================================================
    // RSI
    // ============================================================

    private fun calculateRSI(
        prices: List<Double>,
        period: Int
    ): Double {

        if (
            prices.size <= period
        ) {

            return 50.0
        }

        var gain =
            0.0

        var loss =
            0.0

        for (
            i in 1..period
        ) {

            val change =
                prices[i] -
                        prices[i - 1]

            if (
                change >= 0
            ) {

                gain += change

            } else {

                loss += abs(
                    change
                )
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
                prices[i] -
                        prices[i - 1]

            val currentGain =
                if (
                    change > 0
                ) {
                    change
                } else {
                    0.0
                }

            val currentLoss =
                if (
                    change < 0
                ) {
                    abs(change)
                } else {
                    0.0
                }

            averageGain =
                (
                    averageGain *
                            (period - 1) +
                            currentGain
                    ) / period

            averageLoss =
                (
                    averageLoss *
                            (period - 1) +
                            currentLoss
                    ) / period
        }

        if (
            averageLoss == 0.0
        ) {

            return 100.0
        }

        val rs =
            averageGain /
                    averageLoss

        return 100.0 -
                (
                    100.0 /
                            (1.0 + rs)
                    )
    }

    // ============================================================
    // MOMENTUM
    // ============================================================

    private fun calculateMomentum(
        prices: List<Double>,
        candles: Int
    ): Double {

        if (
            prices.size <= candles
        ) {

            return 0.0
        }

        return prices.last() -
                prices[
                    prices.size -
                            1 -
                            candles
                ]
    }

    // ============================================================
    // API ERROR
    // ============================================================

    private fun extractApiError(
        body: String
    ): String {

        if (
            body.isBlank()
        ) {

            return "Empty response"
        }

        return try {

            val json =
                JSONObject(body)

            val message =
                json.optString(
                    "message"
                )

            val code =
                json.optString(
                    "code"
                )

            when {

                message.isNotBlank() &&
                        code.isNotBlank() ->
                    "$code - $message"

                message.isNotBlank() ->
                    message

                code.isNotBlank() ->
                    "Code $code"

                else ->
                    body.take(180)
            }

        } catch (
            _: Exception
        ) {

            body.take(180)
        }
    }

    // ============================================================
    // ERROR DISPLAY
    // ============================================================

    private fun showError(
        symbol: String,
        message: String
    ) {

        runOnUiThread {

            if (
                symbol == "BTC/USD"
            ) {

                btcPrice.text =
                    "Price: --"

                btcSignal.text =
                    "Signal: WAIT"

                btcDetails.text =
                    "Confirmation Details:\n" +
                            "DATA ERROR\n" +
                            message

            } else {

                goldPrice.text =
                    "Price: --"

                goldSignal.text =
                    "Signal: WAIT"

                goldDetails.text =
                    "Confirmation Details:\n" +
                            "DATA ERROR\n" +
                            message
            }
        }
    }

    // ============================================================
    // NOTIFICATION CHANNEL
    // ============================================================

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

            manager.createNotificationChannel(
                channel
            )
        }
    }

    // ============================================================
    // NOTIFICATION
    // ============================================================

    private fun sendNotification(
        title: String,
        message: String
    ) {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            return
        }

        val notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setSmallIcon(
                    android.R.drawable.ic_dialog_info
                )
                .setContentTitle(
                    title
                )
                .setContentText(
                    message
                )
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(message)
                )
                .setPriority(
                    NotificationCompat.PRIORITY_HIGH
                )
                .setAutoCancel(
                    true
                )
                .build()

        NotificationManagerCompat
            .from(this)
            .notify(
                (
                    System.currentTimeMillis() %
                            100000
                    ).toInt(),
                notification
            )
    }
}
