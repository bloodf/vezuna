package app.vezuna.domain

/**
 * One published conversion: `value[fromUnit] * factor = value[toUnit]` for one analyte.
 * Only well-established factors belong here; each carries its citation. Never add a factor without one.
 * Display only: stored values and units are never rewritten. docs/UNIT_CONVERSIONS.md mirrors this table.
 */
data class UnitConversion(
    val analyte: String,
    /** [UnitNormalizer.canonicalTestCode] spellings (EN/PT/ES) of the analyte. */
    val aliases: Set<String>,
    val fromUnit: String,
    val toUnit: String,
    val factor: Double,
    val source: String,
) {
    init {
        require(factor.isFinite() && factor > 0.0)
        require(source.isNotBlank())
    }
}

data class ConvertedQuantity(val value: Double, val unit: String, val factor: Double, val source: String)

object AnalyteConversions {
    // Mass concentration to amount-of-substance concentration: factor = 10 / molar mass (g/mol) for mg/dL -> mmol/L.
    private const val SI_REFERENCE =
        "Young DS. Implementation of SI units for clinical laboratory data. Ann Intern Med 1987;106:114-129. " +
            "https://doi.org/10.7326/0003-4819-106-1-114"
    private const val MGH_REFERENCE =
        "Kratz A, et al. Normal reference laboratory values (Case Records of the MGH). N Engl J Med 2004;351:1548-1563. " +
            "https://doi.org/10.1056/NEJMcpc049016"
    private const val IOM_VITAMIN_D =
        "IOM. Dietary Reference Intakes for Calcium and Vitamin D. National Academies Press, 2011 (1 ng/mL = 2.5 nmol/L; " +
            "25-OH vitamin D molar mass 400.6 g/mol). https://www.ncbi.nlm.nih.gov/books/NBK56070/"

    private fun refs(molarMass: String) = "$SI_REFERENCE; $MGH_REFERENCE; factor = 10 / molar mass ($molarMass)"

    val table: List<UnitConversion> = listOf(
        UnitConversion("Glucose", setOf("GLICOSE", "GLUCOSE", "GLUCOSA", "GLICEMIA", "GLU"), "mg/dL", "mmol/L", 0.0555, refs("glucose 180.16 g/mol")),
        UnitConversion("Total cholesterol", setOf("COLESTEROLTOTAL", "TOTALCHOLESTEROL", "CHOLESTEROL", "COLESTEROL", "CHOL"), "mg/dL", "mmol/L", 0.02586, refs("cholesterol 386.65 g/mol")),
        UnitConversion("HDL cholesterol", setOf("HDL", "COLESTEROLHDL", "HDLCOLESTEROL", "HDLC", "HDLCHOLESTEROL"), "mg/dL", "mmol/L", 0.02586, refs("cholesterol 386.65 g/mol")),
        UnitConversion("LDL cholesterol", setOf("LDL", "COLESTEROLLDL", "LDLCOLESTEROL", "LDLC", "LDLCHOLESTEROL"), "mg/dL", "mmol/L", 0.02586, refs("cholesterol 386.65 g/mol")),
        UnitConversion("Triglycerides", setOf("TRIGLICERIDES", "TRIGLICERIDEOS", "TRIGLICERIDOS", "TRIGLYCERIDES", "TG", "TRIG"), "mg/dL", "mmol/L", 0.01129, refs("triolein 885.4 g/mol")),
        UnitConversion("Creatinine", setOf("CREATININA", "CREATININE", "CREA"), "mg/dL", "µmol/L", 88.4, refs("creatinine 113.12 g/mol, x1000 for µmol")),
        UnitConversion("Urea", setOf("UREIA", "UREA"), "mg/dL", "mmol/L", 0.1665, refs("urea 60.06 g/mol")),
        UnitConversion("Urea nitrogen (BUN)", setOf("BUN", "UREANITROGEN", "BLOODUREANITROGEN", "NITROGENIOUREICO"), "mg/dL", "mmol/L", 0.357, refs("nitrogen in urea 28.01 g/mol")),
        UnitConversion("Calcium (total)", setOf("CALCIO", "CALCIUM", "CALCIOTOTAL", "TOTALCALCIUM"), "mg/dL", "mmol/L", 0.2495, refs("calcium 40.08 g/mol")),
        UnitConversion("Magnesium", setOf("MAGNESIO", "MAGNESIUM"), "mg/dL", "mmol/L", 0.4114, refs("magnesium 24.31 g/mol")),
        UnitConversion("Phosphorus (inorganic)", setOf("FOSFORO", "PHOSPHORUS", "FOSFORUS"), "mg/dL", "mmol/L", 0.3229, refs("phosphorus 30.97 g/mol")),
        UnitConversion("Uric acid", setOf("ACIDOURICO", "URICACID"), "mg/dL", "µmol/L", 59.48, refs("uric acid 168.11 g/mol, x1000 for µmol")),
        UnitConversion("Total bilirubin", setOf("BILIRRUBINATOTAL", "BILIRUBINATOTAL", "BILIRUBINTOTAL", "TOTALBILIRUBIN"), "mg/dL", "µmol/L", 17.1, refs("bilirubin 584.66 g/mol, x1000 for µmol")),
        UnitConversion("Hemoglobin", setOf("HEMOGLOBINA", "HEMOGLOBIN", "HGB", "HB"), "g/dL", "g/L", 10.0, "$SI_REFERENCE; 1 dL = 0.1 L, so 1 g/dL = 10 g/L"),
        UnitConversion("Albumin", setOf("ALBUMINA", "ALBUMIN"), "g/dL", "g/L", 10.0, "$SI_REFERENCE; 1 dL = 0.1 L, so 1 g/dL = 10 g/L"),
        UnitConversion("25-OH vitamin D", setOf("VITAMINAD", "VITAMIND", "25OHVITAMINAD", "25OHVITAMIND", "25HIDROXIVITAMINAD", "25OHVITD"), "ng/mL", "nmol/L", 2.496, IOM_VITAMIN_D),
    )

    /** Factor taking [from] to [to] for the analyte, or null when no cited factor exists (never a guess). */
    fun factor(testCode: String, from: String, to: String): Double? {
        val code = UnitNormalizer.canonicalTestCode(testCode)
        val fromKey = unitKey(from)
        val toKey = unitKey(to)
        if (fromKey == toKey) return null
        return table.firstNotNullOfOrNull { entry ->
            if (code !in entry.aliases) return@firstNotNullOfOrNull null
            when {
                unitKey(entry.fromUnit) == fromKey && unitKey(entry.toUnit) == toKey -> entry.factor
                unitKey(entry.toUnit) == fromKey && unitKey(entry.fromUnit) == toKey -> 1.0 / entry.factor
                else -> null
            }
        }
    }

    /** The same quantity in every other unit that has a cited factor; empty when none. */
    fun alternatives(testCode: String, value: Double, unit: String): List<ConvertedQuantity> {
        val code = UnitNormalizer.canonicalTestCode(testCode)
        val key = unitKey(unit)
        return table.filter { code in it.aliases }.mapNotNull { entry ->
            when (key) {
                unitKey(entry.fromUnit) -> ConvertedQuantity(value * entry.factor, entry.toUnit, entry.factor, entry.source)
                unitKey(entry.toUnit) -> ConvertedQuantity(value / entry.factor, entry.fromUnit, 1.0 / entry.factor, entry.source)
                else -> null
            }
        }
    }

    private fun unitKey(unit: String): String =
        unit.trim().lowercase().replace(" ", "").replace('μ', 'µ').replace("ug", "µg").replace("umol", "µmol")
}
