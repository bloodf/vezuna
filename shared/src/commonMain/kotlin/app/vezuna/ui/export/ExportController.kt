package app.vezuna.ui.export

import app.vezuna.export.CsvDoctorExporter
import app.vezuna.export.DoctorExportContent
import app.vezuna.export.ExportOptions
import app.vezuna.export.ExportSectionText
import app.vezuna.export.NativePdfExporter
import app.vezuna.localization.ContentLanguage
import app.vezuna.localization.L10nKey
import app.vezuna.localization.message
import app.vezuna.ui.ControllerCore

internal class ExportController(private val core: ControllerCore, private val pdfExporter: NativePdfExporter) {
    /** [language] is the per-export override; null follows the app/device language. */
    suspend fun exportCsv(language: ContentLanguage? = null, options: ExportOptions = ExportOptions()) = core.operation {
        core.mutableState.value = core.mutableState.value.copy(
            csvExport = CsvDoctorExporter().export(core.requireData(), language ?: ContentLanguage.device(), options),
            notice = message(L10nKey.CSV_EXPORT_PREPARED),
        )
    }

    suspend fun exportPdf(language: ContentLanguage? = null, options: ExportOptions = ExportOptions()) = core.operation {
        val data = core.requireData()
        val chosen = language ?: ContentLanguage.device()
        val document = DoctorExportContent.document(data, ExportSectionText.titleOf(options.family, chosen), chosen, options, core.timeZone())
        core.mutableState.value = core.mutableState.value.copy(
            pdfExport = pdfExporter.export(document),
            notice = message(L10nKey.PDF_EXPORT_PREPARED),
        )
    }

    /** Drops prepared files before a new preview is built, so the preview never shows the previous selection's bytes. */
    fun clearExports() {
        core.mutableState.value = core.mutableState.value.copy(csvExport = null, pdfExport = null)
    }
}
