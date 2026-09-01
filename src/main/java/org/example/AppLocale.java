package org.example;

import java.util.Locale;

/**
 * The UI languages this application ships with, for the language selector
 * combo box. Each option's display text is its own native name ("English",
 * "繁體中文") rather than a message-bundle lookup - a language picker names
 * every option in itself, independent of whichever language is currently
 * active.
 */
enum AppLocale {
    ENGLISH(Locale.ENGLISH, "English"),
    TRADITIONAL_CHINESE(Messages.ZH_TW, "繁體中文");

    final Locale locale;
    private final String nativeName;

    AppLocale(Locale locale, String nativeName) {
        this.locale = locale;
        this.nativeName = nativeName;
    }

    @Override
    public String toString() {
        return nativeName;
    }

    /** Maps a resolved {@link Messages#getCurrentLocale()} back to its selector entry. */
    static AppLocale from(Locale locale) {
        return TRADITIONAL_CHINESE.locale.equals(locale) ? TRADITIONAL_CHINESE : ENGLISH;
    }
}
