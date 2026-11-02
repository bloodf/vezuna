package app.vezuna.domain

/** Pure update/delete transitions shared by every repository implementation. */

fun ProfileData.vaultKeys(): Set<String> = documents.flatMap { listOfNotNull(it.vaultKey) + it.pageVaultKeys }.toSet()

fun ProfileData.withProfile(updated: Profile): ProfileData {
    require(updated.profileId == profile.profileId)
    require(updated.displayName.isNotBlank()) { "Profile name is required" }
    return copy(profile = updated)
}

fun ProfileData.withDocument(updated: SourceDocument): ProfileData {
    require(documents.any { it.documentId == updated.documentId })
    return copy(documents = documents.map { if (it.documentId == updated.documentId) updated else it })
}

fun ProfileData.withReport(updated: LabReport): ProfileData {
    require(reports.any { it.reportId == updated.reportId })
    require(updated.sourceDocumentId == null || documents.any { it.documentId == updated.sourceDocumentId }) { "Source document must exist in this profile" }
    return copy(reports = reports.map { if (it.reportId == updated.reportId) updated else it })
}

/** Removes the report, its observations and, when no other report uses the source, its candidates and document. */
fun ProfileData.withoutReport(reportId: String): ProfileData {
    val report = reports.firstOrNull { it.reportId == reportId } ?: throw IllegalArgumentException("Report not found")
    val remaining = reports.filterNot { it.reportId == reportId }
    val withoutObservations = copy(reports = remaining, observations = observations.filterNot { it.reportId == reportId })
    val sourceId = report.sourceDocumentId ?: return withoutObservations
    if (remaining.any { it.sourceDocumentId == sourceId }) return withoutObservations
    return withoutObservations.copy(
        ocrCandidates = ocrCandidates.filterNot { it.sourceDocumentId == sourceId },
        documents = documents.filterNot { it.documentId == sourceId },
    )
}

fun ProfileData.withoutDocument(documentId: String): ProfileData {
    require(documents.any { it.documentId == documentId }) { "Document not found" }
    val withoutReports = reports.filter { it.sourceDocumentId == documentId }.fold(this) { data, report -> data.withoutReport(report.reportId) }
    return withoutReports.copy(
        documents = withoutReports.documents.filterNot { it.documentId == documentId },
        ocrCandidates = withoutReports.ocrCandidates.filterNot { it.sourceDocumentId == documentId },
    )
}

fun ProfileData.withObservation(updated: LabObservation): ProfileData {
    require(observations.any { it.observationId == updated.observationId })
    require(reports.any { it.reportId == updated.reportId }) { "Report must exist in this profile" }
    require(observations.none { it.observationId != updated.observationId && ClinicalDuplicateDetector.areDuplicates(it, updated) }) { "Duplicate clinical result" }
    return copy(observations = observations.map { if (it.observationId == updated.observationId) updated else it })
}

fun ProfileData.withoutObservation(observationId: String): ProfileData {
    require(observations.any { it.observationId == observationId }) { "Observation not found" }
    return copy(observations = observations.filterNot { it.observationId == observationId })
}

/** Replaces the medication; dose events of schedules it no longer has are dropped. */
fun ProfileData.withMedication(updated: Medication): ProfileData {
    val current = medications.firstOrNull { it.medicationId == updated.medicationId } ?: throw IllegalArgumentException("Medication not found")
    require(updated.schedules.all { it.profileId == updated.profileId && it.medicationId == updated.medicationId })
    val removedSchedules = current.schedules.map { it.scheduleId } - updated.schedules.map { it.scheduleId }.toSet()
    return copy(
        medications = medications.map { if (it.medicationId == updated.medicationId) updated else it },
        doseEvents = doseEvents.filterNot { it.scheduleId in removedSchedules },
    )
}

fun ProfileData.withoutMedication(medicationId: String): ProfileData {
    require(medications.any { it.medicationId == medicationId }) { "Medication not found" }
    return copy(
        medications = medications.filterNot { it.medicationId == medicationId },
        doseEvents = doseEvents.filterNot { it.medicationId == medicationId },
    )
}

fun ProfileData.withSchedule(updated: DoseSchedule): ProfileData {
    val medication = medications.firstOrNull { it.medicationId == updated.medicationId } ?: throw IllegalArgumentException("Medication not found")
    require(medication.schedules.any { it.scheduleId == updated.scheduleId })
    return withMedication(medication.copy(schedules = medication.schedules.map { if (it.scheduleId == updated.scheduleId) updated else it }))
}

fun ProfileData.withoutSchedule(scheduleId: String): ProfileData {
    val medication = medications.firstOrNull { m -> m.schedules.any { it.scheduleId == scheduleId } } ?: throw IllegalArgumentException("Schedule not found")
    return withMedication(medication.copy(schedules = medication.schedules.filterNot { it.scheduleId == scheduleId }))
}

fun ProfileData.withChatMessage(updated: ChatMessage): ProfileData {
    require(chat.any { it.messageId == updated.messageId })
    return copy(chat = chat.map { if (it.messageId == updated.messageId) updated else it })
}

fun ProfileData.withoutChatMessage(messageId: String): ProfileData {
    require(chat.any { it.messageId == messageId }) { "Message not found" }
    return copy(chat = chat.filterNot { it.messageId == messageId })
}
