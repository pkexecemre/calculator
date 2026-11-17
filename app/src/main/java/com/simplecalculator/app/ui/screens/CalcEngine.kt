package com.simplecalculator.app.ui.screens

/** Small expression evaluator for the decoy calculator: + - × ÷ with precedence. */
object CalcEngine {

    fun evaluate(expression: String): String {
        if (expression.isBlank()) return "0"
        val numbers = ArrayList<Double>()
        val operators = ArrayList<Char>()
        val buffer = StringBuilder()
        var pendingNegative = false

        fun flush() {
            if (buffer.isEmpty()) return
            val value = buffer.toString().toDoubleOrNull() ?: return
            numbers.add(if (pendingNegative) -value else value)
            pendingNegative = false
            buffer.setLength(0)
        }

        for (index in expression.indices) {
            val ch = expression[index]
            val prev = expression.getOrNull(index - 1)
            val unaryContext = prev == null || prev in "+-×÷%("
            when {
                ch.isDigit() -> buffer.append(ch)
                ch == '.' && !buffer.contains('.') -> buffer.append(ch)
                ch == '-' && buffer.isEmpty() && unaryContext -> pendingNegative = true
                ch == '+' || ch == '-' || ch == '×' || ch == '÷' || ch == '%' -> {
                    flush()
                    operators.add(ch)
                }
            }
        }
        flush()

        if (numbers.isEmpty()) return "0"
        if (operators.size != numbers.size - 1) {
            // Incomplete expression such as "12+" - evaluate what we have.
            while (operators.size > numbers.size - 1) operators.removeAt(operators.size - 1)
        }

        // First pass: × ÷ %
        val passNumbers = ArrayList<Double>()
        val passOperators = ArrayList<Char>()
        passNumbers.add(numbers[0])
        for (index in operators.indices) {
            val op = operators[index]
            val next = numbers.getOrNull(index + 1) ?: break
            when (op) {
                '×' -> passNumbers[passNumbers.size - 1] = passNumbers.last() * next
                '÷' -> {
                    if (next == 0.0) return "Error"
                    passNumbers[passNumbers.size - 1] = passNumbers.last() / next
                }
                '%' -> {
                    if (next == 0.0) return "Error"
                    passNumbers[passNumbers.size - 1] = passNumbers.last() % next
                }
                else -> {
                    passOperators.add(op)
                    passNumbers.add(next)
                }
            }
        }

        var result = passNumbers.firstOrNull() ?: 0.0
        for (index in passOperators.indices) {
            val next = passNumbers.getOrNull(index + 1) ?: break
            result = when (passOperators[index]) {
                '+' -> result + next
                '-' -> result - next
                else -> result
            }
        }
        return format(result)
    }

    fun format(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "Error"
        val rounded = Math.round(value * 1_000_000.0) / 1_000_000.0
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }
}
