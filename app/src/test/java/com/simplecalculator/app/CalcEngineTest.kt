package com.simplecalculator.app

import com.simplecalculator.app.ui.screens.CalcEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class CalcEngineTest {

    @Test
    fun multiplicationWinsOverAddition() {
        assertEquals("14", CalcEngine.evaluate("2+3×4"))
    }

    @Test
    fun unaryMinusAfterAnOperatorIsNotTreatedAsBinary() {
        assertEquals("-6", CalcEngine.evaluate("2×-3"))
        assertEquals("8", CalcEngine.evaluate("5--3"))
        assertEquals("-3", CalcEngine.evaluate("-3"))
    }

    @Test
    fun trailingDecimalIsIgnored() {
        assertEquals("1.2", CalcEngine.evaluate("1.2."))
    }

    @Test
    fun divisionByZeroReturnsError() {
        assertEquals("Error", CalcEngine.evaluate("1÷0"))
    }
}
