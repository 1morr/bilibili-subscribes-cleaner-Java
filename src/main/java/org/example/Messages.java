package org.example;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;

/**
 * Central access point for the UI's translated strings.
 * <p>
 * Backed by {@link ResourceBundle} over {@code messages.properties} (English,
 * the default/root bundle) and {@code messages_zh_TW.properties} (Traditional
 * Chinese). Both files are UTF-8. {@code ResourceBundle.getBundle} has read
 * {@code .properties} files as UTF-8 since Java 9 (verified empirically
 * against this project's JDK 17 - a round-tripped Traditional Chinese string
 * came back byte-for-byte correct), so no custom {@code Control} or explicit
 * {@code InputStreamReader} is needed here.
 * <p>
 * Locale resolution: an explicit choice made via {@link #setLocale(Locale)}
 * is persisted with {@link Preferences} and wins on the next launch. With no
 * saved choice, the app looks at {@link Locale#getDefault()} itself (see
 * {@link #matchesZhTw}) and decides between exactly two locales it ever hands
 * to {@code ResourceBundle.getBundle}: {@link #ZH_TW} or {@link Locale#ROOT}.
 * <p>
 * That last point is deliberate, not stylistic: {@code getBundle} is only
 * reliable here for those two exact locales. Verified empirically (JDK 17) -
 * on a JVM whose own default locale is zh_TW, requesting any locale that is
 * neither {@code ZH_TW} nor {@code Locale.ROOT} (including plain
 * {@code Locale.ENGLISH}) does not fall through the candidate chain to the
 * root bundle as documented; {@code Control.getFallbackLocale} instead hands
 * back {@code Locale.getDefault()}, so English requested that way silently
 * comes back as Traditional Chinese. {@code Locale.ROOT} and the exact
 * {@code ZH_TW} instance are unaffected by that fallback and resolve
 * correctly under any JVM default, so this class routes every lookup through
 * one of those two rather than through {@code Locale.ENGLISH}.
 */
public final class Messages {

    private static final String BASE_NAME = "messages";
    private static final String PREF_KEY_LOCALE = "ui.locale";

    /** The only non-default locale this application ships a bundle for. */
    static final Locale ZH_TW = new Locale("zh", "TW");

    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private static volatile ResourceBundle bundle;
    private static volatile Locale currentLocale;

    static {
        setLocale(resolveInitialLocale(), false);
    }

    private Messages() {
    }

    private static Locale resolveInitialLocale() {
        String saved = Preferences.userNodeForPackage(Messages.class).get(PREF_KEY_LOCALE, null);
        if ("zh_TW".equals(saved)) {
            return ZH_TW;
        }
        if ("en".equals(saved)) {
            return Locale.ENGLISH;
        }
        return Locale.getDefault();
    }

    /**
     * Switches the active language, persists the choice, and notifies every
     * registered listener synchronously (on whatever thread calls this - the
     * language selector's action listener runs on the EDT, so UI listeners
     * are safe to update Swing components directly from theirs).
     */
    public static void setLocale(Locale locale) {
        setLocale(locale, true);
    }

    private static synchronized void setLocale(Locale locale, boolean persist) {
        boolean zhTw = matchesZhTw(locale);
        currentLocale = zhTw ? ZH_TW : Locale.ENGLISH;
        // Only ever request ZH_TW or Locale.ROOT from getBundle - see class javadoc.
        bundle = ResourceBundle.getBundle(BASE_NAME, zhTw ? ZH_TW : Locale.ROOT);

        if (persist) {
            Preferences.userNodeForPackage(Messages.class)
                    .put(PREF_KEY_LOCALE, zhTw ? "zh_TW" : "en");
        }
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    private static boolean matchesZhTw(Locale locale) {
        return ZH_TW.getLanguage().equals(locale.getLanguage())
                && ZH_TW.getCountry().equals(locale.getCountry());
    }

    /** The locale actually in effect (always {@link Locale#ENGLISH} or {@link #ZH_TW}). */
    public static Locale getCurrentLocale() {
        return currentLocale;
    }

    /** Registers a callback invoked every time the active language changes. */
    public static void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    public static String get(String key) {
        return bundle.getString(key);
    }

    /**
     * Formats a bundle pattern with {@link MessageFormat}. Callers should
     * pass numeric counts pre-stringified (e.g. {@code String.valueOf(n)})
     * to avoid MessageFormat's locale-sensitive number grouping kicking in
     * on plain counts.
     */
    public static String format(String key, Object... args) {
        return new MessageFormat(bundle.getString(key), currentLocale).format(args);
    }
}
