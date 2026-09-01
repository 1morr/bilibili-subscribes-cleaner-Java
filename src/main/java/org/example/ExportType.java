package org.example;

/**
 * Which accounts an export operation should include. Backs
 * {@code exportTypeComboBox} in {@link MainApp} and is consumed directly by
 * {@link DataExporter} - kept as an enum (rather than matching on the
 * combo box's displayed, locale-dependent text) so the export logic does not
 * depend on which language is currently active.
 */
enum ExportType {
    SELECTED("export.type.selected"),
    ALL("export.type.all"),
    NO_GROUP("export.type.noGroup"),
    HAS_GROUP("export.type.hasGroup");

    private final String messageKey;

    ExportType(String messageKey) {
        this.messageKey = messageKey;
    }

    String messageKey() {
        return messageKey;
    }
}
