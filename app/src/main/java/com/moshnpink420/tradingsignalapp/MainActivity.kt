
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
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private lateinit var container: LinearLayout

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())

    private val API_KEY = BuildConfig.TWELVE_DATA_API_KEY

    private var lastGoldSignal = ""

    private val SYMBOL = "XAU/USD"

    private val UPDATE_INTERVAL = 5 * 60 * 1000L

    private val STRONG_ZONE_MIN_SCORE = 75

    private val updateRunnable = object : Runnable {
        override fun run() {
            loadGold()
            handler.postDelayed(this, UPDATE_INTERVAL)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()
        requestNotificationPermission()

        container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(25, 20, 25, 30)

        val scrollView = ScrollView(this)
        scrollView.addView(container)

        setContentView(scrollView)

        addTitle()

        handler.post(updateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        client.dispatcher.cancelAll()
    }

    private fun addTitle() {
        val title = TextView(this)

        title.text = "TRADING SIGNAL APP\nGOLD EDITION"
        title.textSize = 24f
        title.setPadding(0, 10, 0, 25)

        container.addView(title)
    }

    private fun refreshScreen() {
        container.removeAllViews()
        addTitle()
    }

    // ==========================================
    // GOLD MARKET DATA
    // ==========================================

    private fun loadGold() {

        if (API_KEY.isBlank()) {
            showError("API key missing")
            return
        }

        val url =
            "https://api.twelvedata.com/time_series" +
                    "?symbol=XAU%2FUSD" +
                    "&interval=5min" +
                    "&outputsize=50" +
                    "&apikey=$API_KEY"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    showError(
                        "Network error: ${e.message ?: "Unknown error"}"
                    )
                }
            }

            override fun onResponse(
                call: Call,
                response: Response
            ) {

                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    runOnUiThread {
                        showError("Data error Http ${response.code}")
                    }
                    return
                }

                try {

                    val json = JSONObject(body)

                    if (
                        json.has("status") &&
                        json.optString("status") == "error"
                    ) {
                        runOnUiThread {
                            showError(
                                json.optString(
                                    "message",
                                    "Market data error"
                                )
                            )
                        }
                        return
                    }

                    if (
                        json.has("code") &&
                        json.optInt("code", 200) != 200
                    ) {
                        runOnUiThread {
                            showError(
                                json.optString(
                                    "message",
                                    "Market data error"
                                )
                            )
                        }
                        return
                    }

                    if (!json.has("values")) {
                        runOnUiThread {
                            showError("Market data error")
                        }
                        return
                    }

                    val values = json.getJSONArray("values")

                    if (values.length() < 25) {
                        runOnUiThread {
                            showError("Not enough candle data")
                        }
                        return
                    }

                    val candles = ArrayList<Candle>()

                    // Twelve Data newest first.
                    // Reverse to oldest -> newest.

                    for (i in values.length() - 1 downTo 0) {

                        val item = values.getJSONObject(i)

                        val time = item.optString("datetime")

                        val open = item.optDouble("open", Double.NaN)
                        val high = item.optDouble("high", Double.NaN)
                        val low = item.optDouble("low", Double.NaN)
                        val close = item.optDouble("close", Double.NaN)

                        if (
                            open.isFinite() &&
                            high.isFinite() &&
                            low.isFinite() &&
                            close.isFinite() &&
                            high >= low &&
                            open > 0 &&
                            close > 0
                        ) {

                            candles.add(
                                Candle(
                                    time,
                                    open,
                                    high,
                                    low,
                                    close
                                )
                            )
                        }
                    }

                    if (candles.size < 25) {
                        runOnUiThread {
                            showError("Not enough valid candles")
                        }
                        return
                    }

                    val analysis = analyze(candles)

                    runOnUiThread {
                        updateUI(analysis)
                    }

                } catch (e: Exception) {

                    runOnUiThread {
                        showError(
                            "Parse error: ${e.message ?: "Unknown error"}"
                        )
                    }
                }
            }
        })
    }

    // ==========================================
    // MAIN ANALYSIS
    // ==========================================

    private fun analyze(candles: List<Candle>): Analysis {

        val closes = candles.map { it.close }

        val current = candles.last()

        val ema9 = calculateEMA(closes, 9)
        val ema21 = calculateEMA(closes, 21)
        val rsi = calculateRSI(closes, 14)

        val momentum =
            current.close - candles[candles.size - 4].close

        val bullishMomentum = momentum > 0
        val bearishMomentum = momentum < 0

        val bullishTrend = ema9 > ema21
        val bearishTrend = ema9 < ema21

        val priceAboveEMA9 = current.close > ema9
        val priceBelowEMA9 = current.close < ema9

        // Pullback

        val recentPullbackCandles = candles.takeLast(5)

        val bullishPullback =
            recentPullbackCandles.any {
                it.low <= ema9
            } && current.close > ema9

        val bearishPullback =
            recentPullbackCandles.any {
                it.high >= ema9
            } && current.close < ema9

        // Candle analysis

        val candleRange = current.high - current.low

        val candleBody =
            abs(current.close - current.open)

        val bullishCandle =
            current.close > current.open

        val bearishCandle =
            current.close < current.open

        val strongBullishCandle =
            bullishCandle &&
                    candleRange > 0 &&
                    candleBody / candleRange >= 0.60

        val strongBearishCandle =
            bearishCandle &&
                    candleRange > 0 &&
                    candleBody / candleRange >= 0.60

        // ATR

        val atr = calculateATR(candles, 14)

        // Support / Resistance

        val srCandles =
            candles.dropLast(1).takeLast(20)

        val support = srCandles.minOf { it.low }
        val resistance = srCandles.maxOf { it.high }

        // Liquidity sweep

        val previousCandles =
            candles.dropLast(1).takeLast(20)

        val previousLow =
            previousCandles.minOf { it.low }

        val previousHigh =
            previousCandles.maxOf { it.high }

        val sellSideSweep =
            current.low < previousLow &&
                    current.close > previousLow

        val buySideSweep =
            current.high > previousHigh &&
                    current.close < previousHigh

        // Combined Supply / Demand Detection

        val zones = detectStrongZones(candles, atr)

        // Select nearest valid strong zones

        val demandZone = zones
            .filter { it.type == "DEMAND" }
            .minByOrNull {
                zoneDistance(current.close, it)
            }

        val supplyZone = zones
            .filter { it.type == "SUPPLY" }
            .minByOrNull {
                zoneDistance(current.close, it)
            }

        val demandTouched =
            demandZone != null &&
                    current.low <= demandZone.high &&
                    current.high >= demandZone.low

        val supplyTouched =
            supplyZone != null &&
                    current.high >= supplyZone.low &&
                    current.low <= supplyZone.high

        val demandNear =
            demandZone != null &&
                    zoneDistancePercent(
                        current.close,
                        demandZone
                    ) <= 0.20

        val supplyNear =
            supplyZone != null &&
                    zoneDistancePercent(
                        current.close,
                        supplyZone
                    ) <= 0.20

        val demandConfirmation =
            demandZone != null &&
                    demandTouched &&
                    bullishCandle &&
                    current.close > demandZone.low

        val supplyConfirmation =
            supplyZone != null &&
                    supplyTouched &&
                    bearishCandle &&
                    current.close < supplyZone.high

        // ======================================
        // BUY SCORE
        // ======================================

        var buyScore = 0

        if (bullishTrend) buyScore += 15
        if (ema9 > ema21) buyScore += 10
        if (priceAboveEMA9) buyScore += 10

        if (rsi in 52.0..68.0) buyScore += 12
        if (rsi < 28) buyScore -= 5

        if (bullishMomentum) buyScore += 10
        if (bullishPullback) buyScore += 12

        if (strongBullishCandle) buyScore += 10
        else if (bullishCandle) buyScore += 5

        if (sellSideSweep) buyScore += 8

        // Demand zone confirmation

        if (demandConfirmation) buyScore += 12
        else if (demandNear && bullishCandle) buyScore += 6

        // Strong supply resistance

        if (supplyNear) buyScore -= 8

        val distanceToResistance =
            if (resistance != 0.0)
                abs(resistance - current.close) /
                        current.close * 100.0
            else 999.0

        if (distanceToResistance < 0.10) {
            buyScore -= 8
        }

        // ======================================
        // SELL SCORE
        // ======================================

        var sellScore = 0

        if (bearishTrend) sellScore += 15
        if (ema9 < ema21) sellScore += 10
        if (priceBelowEMA9) sellScore += 10

        if (rsi in 32.0..48.0) sellScore += 12
        if (rsi > 72) sellScore -= 5

        if (bearishMomentum) sellScore += 10
        if (bearishPullback) sellScore += 12

        if (strongBearishCandle) sellScore += 10
        else if (bearishCandle) sellScore += 5

        if (buySideSweep) sellScore += 8

        // Supply zone confirmation

        if (supplyConfirmation) sellScore += 12
        else if (supplyNear && bearishCandle) sellScore += 6

        // Strong demand support

        if (demandNear) sellScore -= 8

        val distanceToSupport =
            if (support != 0.0)
                abs(current.close - support) /
                        current.close * 100.0
            else 999.0

        if (distanceToSupport < 0.10) {
            sellScore -= 8
        }

        buyScore = buyScore.coerceIn(0, 100)
        sellScore = sellScore.coerceIn(0, 100)

        // ======================================
        // FINAL SIGNAL
        // ======================================

        val difference = abs(buyScore - sellScore)

        val signal = when {

            buyScore >= 70 &&
                    buyScore > sellScore &&
                    difference >= 15 &&
                    bullishTrend &&
                    priceAboveEMA9 ->
                "BUY"

            sellScore >= 70 &&
                    sellScore > buyScore &&
                    difference >= 15 &&
                    bearishTrend &&
                    priceBelowEMA9 ->
                "SELL"

            else ->
                "WAIT"
        }

        val signalScore = maxOf(buyScore, sellScore)

        val strength = when {

            signal == "WAIT" && signalScore < 40 ->
                "WEAK"

            signal == "WAIT" ->
                "MODERATE"

            signalScore >= 85 ->
                "VERY STRONG"

            signalScore >= 70 ->
                "STRONG"

            signalScore >= 55 ->
                "GOOD"

            else ->
                "WEAK"
        }

        val pattern = when {

            strongBullishCandle ->
                "Strong Bullish Body"

            strongBearishCandle ->
                "Strong Bearish Body"

            bullishCandle ->
                "Bullish Candle"

            bearishCandle ->
                "Bearish Candle"

            else ->
                "Doji / Neutral"
        }

        val structure = when {

            current.close > resistance ->
                "Bullish Breakout"

            current.close < support ->
                "Bearish Breakdown"

            bullishTrend && current.close > ema21 ->
                "Bullish Trend"

            bearishTrend && current.close < ema21 ->
                "Bearish Trend"

            else ->
                "Mixed / Range"
        }

        return Analysis(
            currentPrice = current.close,
            candleTime = current.time,
            ema9 = ema9,
            ema21 = ema21,
            rsi = rsi,
            momentumPositive = bullishMomentum,
            momentumNegative = bearishMomentum,
            pullbackBullish = bullishPullback,
            pullbackBearish = bearishPullback,
            bullishCandle = bullishCandle,
            bearishCandle = bearishCandle,
            strongBullishCandle = strongBullishCandle,
            strongBearishCandle = strongBearishCandle,
            support = support,
            resistance = resistance,
            sellSideSweep = sellSideSweep,
            buySideSweep = buySideSweep,
            pattern = pattern,
            structure = structure,
            buyScore = buyScore,
            sellScore = sellScore,
            signal = signal,
            signalScore = signalScore,
            strength = strength,
            atr = atr,
            demandZone = demandZone,
            supplyZone = supplyZone,
            demandConfirmation = demandConfirmation,
            supplyConfirmation = supplyConfirmation
        )
    }

    // ==========================================
    // COMBINED SUPPLY / DEMAND DETECTION
    // ==========================================

    private fun detectStrongZones(
        candles: List<Candle>,
        atr: Double
    ): List<Zone> {

        val result = ArrayList<Zone>()

        if (candles.size < 12 || atr <= 0) {
            return result
        }

        // Analyze recent historical candidates.
        val startIndex = max(2, candles.size - 40)

        for (i in startIndex until candles.size - 2) {

            val base = candles[i]
            val impulse = candles[i + 1]

            val impulseRange = impulse.high - impulse.low

            if (impulseRange <= 0) continue

            val impulseBody =
                abs(impulse.close - impulse.open)

            val bodyRatio =
                impulseBody / impulseRange

            val beforeStart = max(0, i - 2)

            val before = candles.subList(
                beforeStart,
                i
            )

            if (before.isEmpty()) continue

            // Swing High / Swing Low

            val swingHigh =
                base.high >= before.maxOf { it.high } &&
                        base.high > candles[i + 1].high

            val swingLow =
                base.low <= before.minOf { it.low } &&
                        base.low < candles[i + 1].low

            // Base + Impulsive Move

            val bullishImpulse =
                impulse.close > impulse.open &&
                        impulse.close > base.high &&
                        bodyRatio >= 0.55 &&
                        impulse.close - base.close >= atr * 0.60

            val bearishImpulse =
                impulse.close < impulse.open &&
                        impulse.close < base.low &&
                        bodyRatio >= 0.55 &&
                        base.close - impulse.close >= atr * 0.60

            // ==================================
            // DEMAND ZONE
            // ==================================

            if (bullishImpulse) {

                val zoneLow = base.low

                val zoneHigh = max(
                    base.open,
                    base.close
                )

                if (zoneHigh <= zoneLow) continue

                val displacement =
                    (impulse.close - base.close) / atr

                var score = 30

                if (swingLow) score += 25

                if (displacement >= 1.20) {
                    score += 20
                } else if (displacement >= 0.80) {
                    score += 12
                }

                val laterCandles =
                    candles.subList(
                        i + 2,
                        candles.size
                    )

                val retests = laterCandles.count {
                    it.low <= zoneHigh &&
                            it.high >= zoneLow
                }

                val invalid =
                    laterCandles.any {
                        it.close < zoneLow - atr * 0.10
                    }

                val cleanMove =
                    laterCandles.take(3).none {
                        it.close < zoneLow
                    }

                if (retests == 0) score += 15
                else if (retests == 1) score += 8

                if (cleanMove) score += 10

                if (!invalid) {

                    score = score.coerceIn(0, 100)

                    if (score >= STRONG_ZONE_MIN_SCORE) {

                        result.add(
                            Zone(
                                type = "DEMAND",
                                low = zoneLow,
                                high = zoneHigh,
                                strength = score,
                                fresh = retests == 0,
                                retests = retests,
                                createdAt = base.time
                            )
                        )
                    }
                }
            }

            // ==================================
            // SUPPLY ZONE
            // ==================================

            if (bearishImpulse) {

                val zoneLow = min(
                    base.open,
                    base.close
                )

                val zoneHigh = base.high

                if (zoneHigh <= zoneLow) continue

                val displacement =
                    (base.close - impulse.close) / atr

                var score = 30

                if (swingHigh) score += 25

                if (displacement >= 1.20) {
                    score += 20
                } else if (displacement >= 0.80) {
                    score += 12
                }

                val laterCandles =
                    candles.subList(
                        i + 2,
                        candles.size
                    )

                val retests = laterCandles.count {
                    it.high >= zoneLow &&
                            it.low <= zoneHigh
                }

                val invalid =
                    laterCandles.any {
                        it.close > zoneHigh + atr * 0.10
                    }

                val cleanMove =
                    laterCandles.take(3).none {
                        it.close > zoneHigh
                    }

                if (retests == 0) score += 15
                else if (retests == 1) score += 8

                if (cleanMove) score += 10

                if (!invalid) {

                    score = score.coerceIn(0, 100)

                    if (score >= STRONG_ZONE_MIN_SCORE) {

                        result.add(
                            Zone(
                                type = "SUPPLY",
                                low = zoneLow,
                                high = zoneHigh,
                                strength = score,
                                fresh = retests == 0,
                                retests = retests,
                                createdAt = base.time
                            )
                        )
                    }
                }
            }
        }

        // Remove duplicate / overlapping zones.

        return result
            .sortedByDescending { it.strength }
            .distinctBy {
                "${it.type}_${(it.low * 100).toLong()}_${(it.high * 100).toLong()}"
            }
    }

    private fun zoneDistance(
        price: Double,
        zone: Zone
    ): Double {

        return when {

            price in zone.low..zone.high ->
                0.0

            price < zone.low ->
                zone.low - price

            else ->
                price - zone.high
        }
    }

    private fun zoneDistancePercent(
        price: Double,
        zone: Zone
    ): Double {

        if (price <= 0) return 999.0

        return zoneDistance(price, zone) /
                price * 100.0
    }

    // ==========================================
    // ATR
    // ==========================================

    private fun calculateATR(
        candles: List<Candle>,
        period: Int
    ): Double {

        if (candles.size < 2) return 0.0

        val start = max(1, candles.size - period)

        val ranges = ArrayList<Double>()

        for (i in start until candles.size) {

            val current = candles[i]
            val previous = candles[i - 1]

            val trueRange = max(
                current.high - current.low,
                max(
                    abs(current.high - previous.close),
                    abs(current.low - previous.close)
                )
            )

            ranges.add(trueRange)
        }

        return if (ranges.isEmpty()) 0.0 else ranges.average()
    }

    // ==========================================
    // USER INTERFACE
    // ==========================================

    private fun updateUI(a: Analysis) {

        refreshScreen()

        addText("XAU/USD (GOLD)", 24f)

        addText(
            String.format(
                Locale.US,
                "5M Close: %.2f",
                a.currentPrice
            ),
            21f
        )

        addText(
            "Signal: ${a.signal} | ${a.signalScore}/100",
            23f
        )

        addText(
            "Strength: ${a.strength}",
            19f
        )

        addText(
            """
            MARKET ANALYSIS

            Candle Time: ${a.candleTime}

            EMA 9: ${formatPrice(a.ema9)}
            EMA 21: ${formatPrice(a.ema21)}

            EMA Direction: ${
                if (a.ema9 > a.ema21) "Bullish"
                else if (a.ema9 < a.ema21) "Bearish"
                else "Neutral"
            }

            RSI 14: ${formatPrice(a.rsi)}
            Momentum: ${
                if (a.momentumPositive) "Positive"
                else if (a.momentumNegative) "Negative"
                else "Neutral"
            }

            Pullback:
            ${
                if (a.pullbackBullish) "Bullish Confirmed"
                else if (a.pullbackBearish) "Bearish Confirmed"
                else "No Fresh Pullback"
            }

            Candle:
            ${a.pattern}

            Price Structure:
            ${a.structure}
            """.trimIndent(),
            16f
        )

        // ======================================
        // SUPPLY / DEMAND DISPLAY
        // ======================================

        addText("SUPPLY / DEMAND ZONES", 21f)

        if (a.demandZone != null) {

            val z = a.demandZone

            addText(
                """
                DEMAND ZONE
                BUY AREA

                Low: ${formatPrice(z.low)}
                High: ${formatPrice(z.high)}

                Strength: ${z.strength}/100
                Status: ${if (z.fresh) "FRESH" else "TESTED"}
                Retests: ${z.retests}
                Created: ${z.createdAt}

                Distance: ${
                    formatPrice(
                        zoneDistance(
                            a.currentPrice,
                            z
                        )
                    )
                }

                Confirmation: ${
                    if (a.demandConfirmation)
                        "BULLISH CONFIRMED"
                    else
                        "Waiting for confirmation"
                }
                """.trimIndent(),
                16f
            )

        } else {

            addText(
                "DEMAND ZONE: No Strong Zone Found",
                16f
            )
        }

        if (a.supplyZone != null) {

            val z = a.supplyZone

            addText(
                """
                SUPPLY ZONE
                SELL AREA

                Low: ${formatPrice(z.low)}
                High: ${formatPrice(z.high)}

                Strength: ${z.strength}/100
                Status: ${if (z.fresh) "FRESH" else "TESTED"}
                Retests: ${z.retests}
                Created: ${z.createdAt}

                Distance: ${
                    formatPrice(
                        zoneDistance(
                            a.currentPrice,
                            z
                        )
                    )
                }

                Confirmation: ${
                    if (a.supplyConfirmation)
                        "BEARISH CONFIRMED"
                    else
                        "Waiting for confirmation"
                }
                """.trimIndent(),
                16f
            )

        } else {

            addText(
                "SUPPLY ZONE: No Strong Zone Found",
                16f
            )
        }

        addText(
            """
            Zone Detection:
            Combined Swing + Impulsive Move

            Minimum Strong Zone:
            $STRONG_ZONE_MIN_SCORE/100

            Display:
            Strong Zones Only
            """.trimIndent(),
            15f
        )

        // ======================================
        // SUPPORT / RESISTANCE
        // ======================================

        val supportPercent =
            abs(a.currentPrice - a.support) /
                    a.currentPrice * 100.0

        val resistancePercent =
            abs(a.resistance - a.currentPrice) /
                    a.currentPrice * 100.0

        addText(
            """
            SUPPORT / RESISTANCE

            Support: ${formatPrice(a.support)}
            Distance: ${formatPrice(supportPercent)}% below

            Resistance: ${formatPrice(a.resistance)}
            Distance: ${formatPrice(resistancePercent)}% above
            """.trimIndent(),
            16f
        )

        // ======================================
        // LIQUIDITY
        // ======================================

        val liquidityText = when {

            a.sellSideSweep ->
                "Sell-side Liquidity Swept"

            a.buySideSweep ->
                "Buy-side Liquidity Swept"

            else ->
                "No Liquidity Sweep"
        }

        addText(
            """
            LIQUIDITY ANALYSIS

            $liquidityText
            """.trimIndent(),
            16f
        )

        // ======================================
        // FINAL SCORE
        // ======================================

        addText(
            """
            FINAL SIGNAL ANALYSIS

            BUY Score: ${a.buyScore}/100
            SELL Score: ${a.sellScore}/100

            Final Signal: ${a.signal}
            Signal Score: ${a.signalScore}/100
            Signal Strength: ${a.strength}
            """.trimIndent(),
            19f
        )

        // ======================================
        // NOTIFICATION
        // ======================================

        if (a.signal == "BUY" || a.signal == "SELL") {

            if (a.signal != lastGoldSignal) {

                sendNotification(
                    SYMBOL,
                    a.signal,
                    a.signalScore
                )

                lastGoldSignal = a.signal
            }

        } else {

            lastGoldSignal = ""
        }
    }

    private fun addText(
        text: String,
        size: Float
    ) {

        val view = TextView(this)

        view.text = text
        view.textSize = size

        view.setPadding(
            0,
            10,
            0,
            18
        )

        container.addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun formatPrice(value: Double): String {

        return String.format(
            Locale.US,
            "%.2f",
            value
        )
    }

    private fun showError(message: String) {

        refreshScreen()

        addText("XAU/USD (GOLD)", 24f)

        addText(
            """
            Price: --
            Signal: WAIT

            $message

            Next update:
            Approximately 5 minutes
            """.trimIndent(),
            17f
        )
    }

    // ==========================================
    // EMA
    // ==========================================

    private fun calculateEMA(
        values: List<Double>,
        period: Int
    ): Double {

        if (values.size < period) {
            return values.last()
        }

        val multiplier = 2.0 / (period + 1)

        var ema = values.take(period).average()

        for (i in period until values.size) {

            ema =
                (values[i] - ema) *
                        multiplier +
                        ema
        }

        return ema
    }

    // ==========================================
    // RSI
    // ==========================================

    private fun calculateRSI(
        closes: List<Double>,
        period: Int
    ): Double {

        if (closes.size <= period) return 50.0

        var gains = 0.0
        var losses = 0.0

        for (i in 1..period) {

            val change = closes[i] - closes[i - 1]

            if (change > 0) {
                gains += change
            } else {
                losses += abs(change)
            }
        }

        var averageGain = gains / period
        var averageLoss = losses / period

        for (i in period + 1 until closes.size) {

            val change = closes[i] - closes[i - 1]

            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) abs(change) else 0.0

            averageGain =
                (averageGain * (period - 1) + gain) /
                        period

            averageLoss =
                (averageLoss * (period - 1) + loss) /
                        period
        }

        if (averageLoss == 0.0) return 100.0

        val rs = averageGain / averageLoss

        return 100.0 - (100.0 / (1.0 + rs))
    }

    // ==========================================
    // NOTIFICATION CHANNEL
    // ==========================================

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                "trading_signal_channel",
                "Trading Signals",
                NotificationManager.IMPORTANCE_HIGH
            )

            channel.description =
                "Gold BUY and SELL trading notifications"

            val manager =
                getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            if (
                checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.POST_NOTIFICATIONS
                    ),
                    1001
                )
            }
        }
    }

    private fun sendNotification(
        symbol: String,
        signal: String,
        score: Int
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

        val manager =
            getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

        val notification =
            NotificationCompat.Builder(
                this,
                "trading_signal_channel"
            )
                .setSmallIcon(
                    android.R.drawable.ic_dialog_info
                )
                .setContentTitle(
                    "$symbol $signal SIGNAL"
                )
                .setContentText(
                    "Signal Strength: $score/100"
                )
                .setPriority(
                    NotificationCompat.PRIORITY_HIGH
                )
                .setAutoCancel(true)
                .build()

        manager.notify(
            102,
            notification
        )
    }

    // ==========================================
    // DATA CLASSES
    // ==========================================

    data class Candle(
        val time: String,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double
    )

    data class Zone(
        val type: String,
        val low: Double,
        val high: Double,
        val strength: Int,
        val fresh: Boolean,
        val retests: Int,
        val createdAt: String
    )

    data class Analysis(
        val currentPrice: Double,
        val candleTime: String,

        val ema9: Double,
        val ema21: Double,
        val rsi: Double,

        val momentumPositive: Boolean,
        val momentumNegative: Boolean,

        val pullbackBullish: Boolean,
        val pullbackBearish: Boolean,

        val bullishCandle: Boolean,
        val bearishCandle: Boolean,

        val strongBullishCandle: Boolean,
        val strongBearishCandle: Boolean,

        val support: Double,
        val resistance: Double,

        val sellSideSweep: Boolean,
        val buySideSweep: Boolean,

        val pattern: String,
        val structure: String,

        val buyScore: Int,
        val sellScore: Int,

        val signal: String,
        val signalScore: Int,
        val strength: String,

        val atr: Double,

        val demandZone: Zone?,
        val supplyZone: Zone?,

        val demandConfirmation: Boolean,
        val supplyConfirmation: Boolean
    )
}
