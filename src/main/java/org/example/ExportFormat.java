package org.example;

/**
 * Output format for an export operation. Backs {@code exportFormatComboBox}
 * in {@link MainApp}; see {@link ExportType} for why this is an enum rather
 * than the combo box's displayed text.
 */
enum ExportFormat {
    UID_ONLY("export.format.uidOnly"),
    DETAILED_CSV("export.format.detailedCsv");

    private final String messageKey;

    ExportFormat(String messageKey) {
        this.messageKey = messageKey;
    }

    String messageKey() {
        return messageKey;
    }
}
