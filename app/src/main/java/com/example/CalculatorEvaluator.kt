package com.example

import java.util.Locale
import kotlin.math.abs

/**
 * Pure arithmetic expression evaluator supporting operator precedence (`*`, `/`, `%` before `+`, `-`),
 * unary signs, decimals, and live real-time preview evaluation while typing.
 */
object CalculatorEvaluator {

    private sealed class CalcResult {
        data class Value(val number: Double) : CalcResult()
        object DivByZero : CalcResult()
        object Invalid : CalcResult()
    }

    /**
     * Evaluates an expression for live preview while typing.
     * Returns null when the expression is a single number with no operator or is currently
     * incomplete (e.g. trailing operator like `"56*"`), so the preview line only displays
     * meaningful calculated results such as `= 336`.
     */
    fun evaluateLivePreview(expr: String): String? {
        val clean = expr.replace(" ", "")
        if (clean.isEmpty()) return null

        // Require at least one binary operator before showing a live preview result
        val hasBinaryOperator = clean.drop(1).any { it == '+' || it == '-' || it == '*' || it == '/' || it == '%' }
        if (!hasBinaryOperator) return null

        // Ignore trailing binary operators mid-entry unless trailing '%' precedes another operator? Wait: 56% alone has op
        val last = clean.last()
        if (last == '+' || last == '-' || last == '*' || last == '/' || last == '%' || last == '.') {
            return null
        }

        return when (val res = parseAndEvaluate(clean)) {
            is CalcResult.Value -> formatNumber(res.number)
            CalcResult.DivByZero -> "Div By Zero"
            CalcResult.Invalid -> null
        }
    }

    /**
     * Full evaluation used when the user presses `=`.
     */
    fun evaluateFinal(expr: String): String {
        val clean = expr.replace(" ", "")
        if (clean.isEmpty()) return "0"
        return when (val res = parseAndEvaluate(clean)) {
            is CalcResult.Value -> formatNumber(res.number)
            CalcResult.DivByZero -> "Div By Zero"
            CalcResult.Invalid -> "Error"
        }
    }

    private fun parseAndEvaluate(input: String): CalcResult {
        if (input.isEmpty()) return CalcResult.Invalid
        var pos = 0

        fun peek(): Char? = if (pos < input.length) input[pos] else null

        fun parseFactor(): CalcResult {
            val ch = peek() ?: return CalcResult.Invalid
            if (ch == '+') {
                pos++
                return parseFactor()
            }
            if (ch == '-') {
                pos++
                return when (val inner = parseFactor()) {
                    is CalcResult.Value -> CalcResult.Value(-inner.number)
                    else -> inner
                }
            }

            val start = pos
            var seenDigit = false
            var seenDot = false
            while (pos < input.length) {
                val c = input[pos]
                if (c.isDigit()) {
                    seenDigit = true
                    pos++
                } else if (c == '.' && !seenDot) {
                    seenDot = true
                    pos++
                } else {
                    break
                }
            }
            if (!seenDigit) return CalcResult.Invalid
            val num = input.substring(start, pos).toDoubleOrNull() ?: return CalcResult.Invalid
            return CalcResult.Value(num)
        }

        fun parseTerm(): CalcResult {
            var left = parseFactor()
            if (left !is CalcResult.Value) return left

            while (true) {
                val op = peek()
                if (op != '*' && op != '/' && op != '%') break
                pos++
                val right = parseFactor()
                if (right !is CalcResult.Value) return right
                val lVal = (left as CalcResult.Value).number
                val rVal = right.number
                left = when (op) {
                    '*' -> CalcResult.Value(lVal * rVal)
                    '/' -> if (rVal == 0.0) return CalcResult.DivByZero else CalcResult.Value(lVal / rVal)
                    '%' -> if (rVal == 0.0) return CalcResult.DivByZero else CalcResult.Value(lVal % rVal)
                    else -> return CalcResult.Invalid
                }
            }
            return left
        }

        fun parseExpression(): CalcResult {
            var left = parseTerm()
            if (left !is CalcResult.Value) return left

            while (true) {
                val op = peek()
                if (op != '+' && op != '-') break
                pos++
                val right = parseTerm()
                if (right !is CalcResult.Value) return right
                val lVal = (left as CalcResult.Value).number
                val rVal = right.number
                left = if (op == '+') CalcResult.Value(lVal + rVal) else CalcResult.Value(lVal - rVal)
            }
            return left
        }

        val result = parseExpression()
        if (pos != input.length) return CalcResult.Invalid
        if (result is CalcResult.Value && (result.number.isNaN() || result.number.isInfinite())) {
            return CalcResult.Invalid
        }
        return result
    }

    private fun formatNumber(value: Double): String {
        val cleanZero = if (abs(value) < 1e-12) 0.0 else value
        return if (cleanZero % 1.0 == 0.0 && abs(cleanZero) <= Long.MAX_VALUE.toDouble()) {
            cleanZero.toLong().toString()
        } else {
            String.format(Locale.US, "%.6f", cleanZero).trimEnd('0').trimEnd('.')
        }
    }
}
