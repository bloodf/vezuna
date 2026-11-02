package app.vezuna

import app.vezuna.domain.AnalyteConversions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** docs/UNIT_CONVERSIONS.md is what the maintainer reviews: it must list exactly the table in code. */
class UnitConversionsDocTest {
    @Test
    fun docListsEveryConversionWithItsFactor() {
        val doc = File("../docs/UNIT_CONVERSIONS.md").readText()
        AnalyteConversions.table.forEach { entry ->
            val row = "| ${entry.analyte} | ${entry.fromUnit} | ${entry.toUnit} | ${entry.factor} |"
            assertTrue(row in doc, "docs/UNIT_CONVERSIONS.md is missing: $row")
        }
        assertTrue(doc.lines().count { it.startsWith("| ") } == AnalyteConversions.table.size + 1)
    }
}
