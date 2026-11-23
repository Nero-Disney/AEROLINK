package com.example.vision.domain.entities

enum class ObjectFamily(val labelFr: String) {
    HUMAN("Humain"),
    ANIMAL("Animal"),
    VEHICLE("Véhicule"),
    STRUCTURE("Structure"),
    VEGETATION("Végétation"),
    INFRASTRUCTURE("Infrastructure"),
    TERRAIN("Terrain"),
    SPECIAL("Marqueur spécial"),
    UNKNOWN("Objet inconnu")
}

enum class ObstacleImportance(val labelFr: String, val riskWeight: Float) {
    LARGE_STRUCTURE("Obstacle large", 0.85f),
    THIN_OBSTACLE("Obstacle fin (câble/poteau)", 1.0f),
    DYNAMIC_OBJECT("Objet mobile", 0.90f),
    SURFACE("Surface / Terrain", 0.35f),
    MARKER("Marqueur d'atterrissage", 0.15f),
    UNKNOWN("Obstacle non caractérisé", 0.75f)
}

/**
 * Taxonomie ADVDS indépendante des frameworks : associe un libellé modèle
 * à sa famille sémantique et son importance d'obstacle, ou renvoie UNKNOWN OBJECT en Open-Set.
 */
object AdvdsTaxonomy {

    data class TaxonomyClassification(
        val classId: String,
        val displayLabelFr: String,
        val family: ObjectFamily,
        val importance: ObstacleImportance,
        val isOpenSetUnknown: Boolean
    )

    fun classify(rawLabel: String, confidence: Float): TaxonomyClassification {
        val norm = rawLabel.trim().lowercase()
        if (norm.isBlank() || norm == "unknown" || norm == "objet" || confidence < 0.28f) {
            return TaxonomyClassification(
                classId = "UNKNOWN_OBJECT",
                displayLabelFr = "UNKNOWN OBJECT",
                family = ObjectFamily.UNKNOWN,
                importance = ObstacleImportance.UNKNOWN,
                isOpenSetUnknown = true
            )
        }

        return when {
            norm in setOf("person", "personne", "pedestrian", "human", "face", "visage", "fashion good") ->
                TaxonomyClassification("PERSON", if (norm.contains("face") || norm.contains("visage")) "Visage" else "Personne", ObjectFamily.HUMAN, ObstacleImportance.DYNAMIC_OBJECT, false)

            norm in setOf("bird", "dog", "cat", "oiseau", "chien", "chat", "animal") ->
                TaxonomyClassification("ANIMAL", norm.replaceFirstChar { it.uppercase() }, ObjectFamily.ANIMAL, ObstacleImportance.DYNAMIC_OBJECT, false)

            norm in setOf("car", "voiture", "motorcycle", "moto", "bicycle", "vélo", "bus", "truck", "véhicule") ->
                TaxonomyClassification("VEHICLE", "Véhicule", ObjectFamily.VEHICLE, ObstacleImportance.DYNAMIC_OBJECT, false)

            norm in setOf("building", "wall", "fence", "bridge", "mur", "bâtiment", "pont", "place") ->
                TaxonomyClassification("STRUCTURE", "Structure", ObjectFamily.STRUCTURE, ObstacleImportance.LARGE_STRUCTURE, false)

            norm in setOf("tree", "bush", "plant", "potted plant", "arbre", "végétation") ->
                TaxonomyClassification("VEGETATION", "Végétation", ObjectFamily.VEGETATION, ObstacleImportance.LARGE_STRUCTURE, false)

            norm in setOf("pole", "post", "streetlight", "traffic light", "poteau", "câble", "wire", "branch") ->
                TaxonomyClassification("INFRASTRUCTURE", "Obstacle fin", ObjectFamily.INFRASTRUCTURE, ObstacleImportance.THIN_OBSTACLE, false)

            norm in setOf("road", "ground", "water", "sol", "route", "eau", "sky", "ciel") ->
                TaxonomyClassification("TERRAIN", "Terrain", ObjectFamily.TERRAIN, ObstacleImportance.SURFACE, false)

            norm in setOf("qr", "aruco", "apriltag", "landing_marker", "marqueur", "barcode") ->
                TaxonomyClassification("MARKER", "Marqueur QR/Cible", ObjectFamily.SPECIAL, ObstacleImportance.MARKER, false)

            norm in setOf("home good", "food", "chair", "table", "bottle", "laptop", "cell phone", "backpack") ->
                TaxonomyClassification("OBJECT_${norm.uppercase().replace(' ', '_')}", "Objet (${rawLabel})", ObjectFamily.UNKNOWN, ObstacleImportance.LARGE_STRUCTURE, false)

            else ->
                TaxonomyClassification(
                    classId = "UNKNOWN_OBJECT",
                    displayLabelFr = "UNKNOWN OBJECT ($rawLabel)",
                    family = ObjectFamily.UNKNOWN,
                    importance = ObstacleImportance.UNKNOWN,
                    isOpenSetUnknown = true
                )
        }
    }
}
