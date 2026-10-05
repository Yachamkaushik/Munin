package com.munin.app.calc

import java.math.BigDecimal
import java.math.MathContext

enum class Dimension(val label: String) { LENGTH("length"), MASS("mass"), VOLUME("volume"), AREA("area"), TIME("time"), SPEED("speed"), DATA("data size"), TEMPERATURE("temperature") }

class Unit(val symbol: String, val dimension: Dimension, val toBase: BigDecimal)

/** Result of "5 km in miles". [factorNote] is the one-unit relationship ("1 km = 0.621371 mi"). */
data class Conversion(val value: BigDecimal, val from: Unit, val to: Unit, val factorNote: String)

/**
 * Unit conversion with Latin unit names, including the units people in India actually use for land and weight (acre, guntha, cent, gaj, sq ft,
 * quintal, tola). Data sizes use 1,024 (KB = 1,024 bytes). Money is not here: it needs an exchange rate (see [Rates]).
 */
object Units {
    private val MC = MathContext(30)
    private fun b(s: String) = BigDecimal(s)

    private val all = ArrayList<Pair<Unit, List<String>>>()
    private fun u(symbol: String, dim: Dimension, factor: String, vararg names: String) { all += Unit(symbol, dim, b(factor)) to listOf(symbol, *names) }

    init {
        // length (base: metre)
        u("mm", Dimension.LENGTH, "0.001", "millimeter", "millimeters", "millimetre", "millimetres"); u("cm", Dimension.LENGTH, "0.01", "centimeter", "centimeters", "centimetre", "centimetres")
        u("m", Dimension.LENGTH, "1", "meter", "meters", "metre", "metres"); u("km", Dimension.LENGTH, "1000", "kilometer", "kilometers", "kilometre", "kilometres", "kms")
        u("in", Dimension.LENGTH, "0.0254", "inch", "inches"); u("ft", Dimension.LENGTH, "0.3048", "foot", "feet"); u("yd", Dimension.LENGTH, "0.9144", "yard", "yards")
        u("mi", Dimension.LENGTH, "1609.344", "mile", "miles")
        // mass (base: kilogram)
        u("mg", Dimension.MASS, "0.000001", "milligram", "milligrams"); u("g", Dimension.MASS, "0.001", "gram", "grams", "gm", "gms"); u("kg", Dimension.MASS, "1", "kilogram", "kilograms", "kgs", "kilo", "kilos")
        u("quintal", Dimension.MASS, "100", "quintals"); u("tonne", Dimension.MASS, "1000", "tonnes", "ton", "tons", "metric ton"); u("oz", Dimension.MASS, "0.028349523125", "ounce", "ounces")
        u("lb", Dimension.MASS, "0.45359237", "lbs", "pound", "pounds"); u("tola", Dimension.MASS, "0.0116638038", "tolas", "tole")
        // volume (base: litre)
        u("ml", Dimension.VOLUME, "0.001", "milliliter", "milliliters", "millilitre", "millilitres"); u("l", Dimension.VOLUME, "1", "litre", "litres", "liter", "liters", "ltr")
        u("gal", Dimension.VOLUME, "3.785411784", "gallon", "gallons")
        // area (base: square metre)
        u("sq ft", Dimension.AREA, "0.09290304", "sqft", "sq feet", "square feet", "square foot", "sft", "sq.ft"); u("sq m", Dimension.AREA, "1", "sqm", "square meter", "square meters", "square metre", "square metres", "sq meter")
        u("sq yd", Dimension.AREA, "0.83612736", "sqyd", "gaj", "gajam", "square yard", "square yards", "sq yard", "sq yards"); u("sq km", Dimension.AREA, "1000000", "sqkm", "square kilometer", "square kilometre")
        u("acre", Dimension.AREA, "4046.8564224", "acres"); u("hectare", Dimension.AREA, "10000", "hectares", "ha"); u("guntha", Dimension.AREA, "101.17141056", "gunta", "guntas", "gunthas")
        u("cent", Dimension.AREA, "40.468564224", "cents")
        // time (base: second)
        u("s", Dimension.TIME, "1", "sec", "secs", "second", "seconds"); u("min", Dimension.TIME, "60", "mins", "minute", "minutes"); u("h", Dimension.TIME, "3600", "hr", "hrs", "hour", "hours")
        u("day", Dimension.TIME, "86400", "days"); u("week", Dimension.TIME, "604800", "weeks")
        // speed (base: metre per second)
        u("m/s", Dimension.SPEED, "1", "mps"); u("km/h", Dimension.SPEED, "0.277777777777777777777777777778", "kmph", "kph", "kmh"); u("mph", Dimension.SPEED, "0.44704", "miles per hour")
        // data (base: byte)
        u("B", Dimension.DATA, "1", "byte", "bytes"); u("KB", Dimension.DATA, "1024", "kb", "kilobyte", "kilobytes"); u("MB", Dimension.DATA, "1048576", "mb", "megabyte", "megabytes")
        u("GB", Dimension.DATA, "1073741824", "gb", "gigabyte", "gigabytes"); u("TB", Dimension.DATA, "1099511627776", "tb", "terabyte", "terabytes")
        // temperature (affine, handled separately)
        all += Unit("°C", Dimension.TEMPERATURE, BigDecimal.ONE) to listOf("°c", "c", "celsius", "centigrade", "degree celsius", "degrees celsius")
        all += Unit("°F", Dimension.TEMPERATURE, BigDecimal.ONE) to listOf("°f", "f", "fahrenheit", "degree fahrenheit", "degrees fahrenheit")
        all += Unit("K", Dimension.TEMPERATURE, BigDecimal.ONE) to listOf("k", "kelvin")
    }

    private val byName: Map<String, Unit> = HashMap<String, Unit>().also { m -> all.forEach { (unit, names) -> names.forEach { n -> m.putIfAbsent(n.lowercase(), unit) } } }

    fun find(name: String): Unit? = byName[name.trim().lowercase().trimEnd('.')]

    /** Null if either unit is unknown. Throws [CalcError] if both are known but measure different things. */
    fun convert(value: BigDecimal, fromName: String, toName: String): Conversion? {
        val from = find(fromName) ?: return null
        val to = find(toName) ?: return null
        if (from.dimension != to.dimension) throw CalcError("Cannot convert ${from.dimension.label} (${from.symbol}) to ${to.dimension.label} (${to.symbol}).")
        val result = if (from.dimension == Dimension.TEMPERATURE) temperature(value, from.symbol, to.symbol)
        else value.multiply(from.toBase, MC).divide(to.toBase, MC)
        val one = if (from.dimension == Dimension.TEMPERATURE) null else BigDecimal.ONE.multiply(from.toBase, MC).divide(to.toBase, MC)
        val note = if (one == null) "" else "1 ${from.symbol} = ${Numbers.format(one)} ${to.symbol}"
        return Conversion(Numbers.tidy(result), from, to, note)
    }

    private fun temperature(v: BigDecimal, from: String, to: String): BigDecimal {
        val c = when (from) { "°C" -> v; "°F" -> (v - BigDecimal(32)).multiply(BigDecimal(5), MC).divide(BigDecimal(9), MC); else -> v - BigDecimal("273.15") }
        return when (to) { "°C" -> c; "°F" -> c.multiply(BigDecimal(9), MC).divide(BigDecimal(5), MC) + BigDecimal(32); else -> c + BigDecimal("273.15") }
    }
}
