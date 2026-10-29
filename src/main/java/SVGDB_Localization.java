import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Time Calculator, based on SVG_3012_javafx_V2.
 *
 * Two localization layers:
 *  - UI texts: ResourceBundle (Messages_bundle*.properties), same keys as V2
 *    (distance, speed, time, message).
 *  - Unit names (kilometre, kilometres per hour, hour): read from the database with
 *    the DB localization approach chosen in the second combo box:
 *    field, row, table or translation table (see localization_demo.sql).
 *
 * Farsi switches the window and the text fields to right-to-left and shows Persian digits.
 * Numbers may be typed with Latin (0-9), Persian (۰-۹) or Arabic-Indic (٠-٩) digits.
 */
public class SVGDB_Localization extends Application {

    // Local MariaDB by default; Docker / Kubernetes set DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD
    private static final String DB_URL = "jdbc:mariadb://" + env("DB_HOST", "localhost")
            + ":" + env("DB_PORT", "3306") + "/" + env("DB_NAME", "svg_db_i10n")
            + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
    private static final String DB_USER = env("DB_USER", "root");
    private static final String DB_PASSWORD = env("DB_PASSWORD", "Test12");

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    // ------------------------------------------------------------ languages

    private record Lang(String label, String code, Locale locale, boolean rtl) {
        @Override public String toString() { return label; }
    }

    private static final List<Lang> LANGUAGES = List.of(
            new Lang("English", "en", Locale.ENGLISH, false),
            new Lang("Finnish", "fi", Locale.forLanguageTag("fi-FI"), false),
            new Lang("Farsi", "fa", Locale.forLanguageTag("fa-IR"), true));

    /** Language codes that may be put into SQL text (table and column names cannot be parameters). */
    private static final Set<String> KNOWN_LANGS = Set.of("en", "fi", "sv", "ja", "ar", "fa");

    private static void requireKnown(String lang) {
        if (!KNOWN_LANGS.contains(lang)) {
            throw new IllegalArgumentException("Unsupported language: " + lang);
        }
    }

    // ----------------------------------------- the four DB localization approaches

    private static final List<String> UNIT_CODES = List.of("KM", "KMH", "H");

    private enum Approach {

        /** One column per language: unit_field(code, name_en, name_fi, ...). */
        FIELD("1. Field (one column per language)") {
            @Override String name(Connection c, String code, String lang) throws SQLException {
                requireKnown(lang);
                return first(c, "SELECT COALESCE(name_" + lang + ", name_en) FROM unit_field WHERE code = ?", code);
            }
        },

        /** One row per unit and language: unit_row(code, lang, name, ...). */
        ROW("2. Row (one row per language)") {
            @Override String name(Connection c, String code, String lang) throws SQLException {
                requireKnown(lang);
                String n = first(c, "SELECT name FROM unit_row WHERE code = ? AND lang = ?", code, lang);
                return n != null ? n : first(c, "SELECT name FROM unit_row WHERE code = ? AND lang = 'en'", code);
            }
        },

        /** One table per language: unit_en, unit_fi, unit_fa, ... */
        TABLE("3. Table (one table per language)") {
            @Override String name(Connection c, String code, String lang) throws SQLException {
                requireKnown(lang);
                return first(c, "SELECT name FROM unit_" + lang + " WHERE code = ?", code);
            }
        },

        /** Neutral unit table plus unit_translation(unit_id, lang, name): JOIN with English fallback. */
        TRANSLATION_TABLE("4. Translation table (JOIN)") {
            @Override String name(Connection c, String code, String lang) throws SQLException {
                requireKnown(lang);
                return first(c, """
                        SELECT COALESCE(t.name, d.name)
                        FROM unit u
                        LEFT JOIN unit_translation t ON t.unit_id = u.id AND t.lang = ?
                        LEFT JOIN unit_translation d ON d.unit_id = u.id AND d.lang = 'en'
                        WHERE u.code = ?
                        """, lang, code);
            }
        };

        private final String label;

        Approach(String label) { this.label = label; }

        abstract String name(Connection c, String code, String lang) throws SQLException;

        @Override public String toString() { return label; }

        static String first(Connection c, String sql, String... params) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setString(i + 1, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }
    }

    // ---------------------------------------------------------------- state

    private ResourceBundle rs;
    private Lang currentLang = LANGUAGES.get(0);
    private Approach currentApproach = Approach.TRANSLATION_TABLE;
    private Map<String, String> unitNames = new HashMap<>();

    private VBox root;

    private final Label languageLabel = new Label("Language:");
    private final Label approachLabel = new Label("DB localization:");
    private final Label distanceLabel = new Label();
    private final Label speedLabel = new Label();
    private final Label resultLabel = new Label();
    private final Label messageLabel = new Label();
    private final Label dbLabel = new Label();
    private final TextField distanceField = new TextField();
    private final TextField speedField = new TextField();
    private final Button calcButton = new Button();

    // --------------------------------------------------------------- digits

    /** Persian (۰-۹) and Arabic-Indic (٠-٩) digits and their separators to plain ASCII. */
    static String normalizeDigits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '۰' && c <= '۹') {
                sb.append((char) ('0' + (c - '۰')));
            } else if (c >= '٠' && c <= '٩') {
                sb.append((char) ('0' + (c - '٠')));
            } else if (c == '٫' || c == ',' || c == '،') {
                sb.append('.');
            } else if (c == '٬' || Character.isWhitespace(c)) {
                // thousands separator and spaces are dropped
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** ASCII digits and decimal point to Persian digits and the Persian decimal separator. */
    static String toPersianDigits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') {
                sb.append((char) ('۰' + (c - '0')));
            } else if (c == '.') {
                sb.append('٫');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private boolean isFarsi() {
        return currentLang.code().equals("fa");
    }

    private String localizeDigits(String s) {
        return isFarsi() ? toPersianDigits(s) : s;
    }

    /** Accepts only digits of any script, a decimal separator and a minus sign. */
    private static TextFormatter<String> numericFormatter() {
        UnaryOperator<TextFormatter.Change> filter = change -> {
            for (char c : change.getText().toCharArray()) {
                boolean ok = (c >= '0' && c <= '9')
                        || (c >= '۰' && c <= '۹')
                        || (c >= '٠' && c <= '٩')
                        || c == '.' || c == ',' || c == '٫' || c == '،' || c == '-';
                if (!ok) {
                    return null;
                }
            }
            return change;
        };
        return new TextFormatter<>(filter);
    }

    private static double parseNumber(String text) {
        return Double.parseDouble(normalizeDigits(text.trim()));
    }

    // ------------------------------------------------------------------- UI

    @Override
    public void start(Stage stage) {
        ComboBox<Lang> langBox = new ComboBox<>();
        langBox.getItems().addAll(LANGUAGES);
        langBox.setValue(LANGUAGES.get(0));
        langBox.setOnAction(e -> updateLanguage(langBox.getValue()));

        ComboBox<Approach> approachBox = new ComboBox<>();
        approachBox.getItems().addAll(Approach.values());
        approachBox.setValue(Approach.TRANSLATION_TABLE);
        approachBox.setOnAction(e -> {
            currentApproach = approachBox.getValue();
            refreshTexts();
        });

        distanceField.setTextFormatter(numericFormatter());
        speedField.setTextFormatter(numericFormatter());

        calcButton.setOnAction(e -> calculate());

        root = new VBox(10,
                languageLabel, langBox,
                approachLabel, approachBox,
                distanceLabel, distanceField,
                speedLabel, speedField,
                calcButton, resultLabel, messageLabel, dbLabel);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.CENTER_LEFT);

        updateLanguage(LANGUAGES.get(0));

        stage.setTitle("Time Calculator_DB_Localization");
        stage.setScene(new Scene(root, 400, 520));
        stage.show();
    }

    /** Safe lookup: a missing key shows !key! instead of crashing the application. */
    private String msg(String key) {
        return rs.containsKey(key) ? rs.getString(key) : "!" + key + "!";
    }

    private void updateLanguage(Lang lang) {
        currentLang = lang;

        NodeOrientation orientation = lang.rtl()
                ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT;
        root.setNodeOrientation(orientation);
        distanceField.setNodeOrientation(orientation);   // numbers are typed right-to-left in Farsi
        speedField.setNodeOrientation(orientation);

        convertFieldDigits(distanceField);
        convertFieldDigits(speedField);

        // No fallback to the computer's default locale: English uses the base bundle (or _en)
        rs = ResourceBundle.getBundle("Messages_bundle", lang.locale(),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));

        resultLabel.setText("");
        messageLabel.setText("");
        refreshTexts();
    }

    private void convertFieldDigits(TextField field) {
        String text = field.getText();
        if (text != null && !text.isEmpty()) {
            field.setText(localizeDigits(normalizeDigits(text)));
        }
    }

    /** Reads the unit names with the chosen approach and rewrites the labels. */
    private void refreshTexts() {
        unitNames = new HashMap<>();
        dbLabel.setText("");
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            for (String code : UNIT_CODES) {
                String n = currentApproach.name(conn, code, currentLang.code());
                if (n != null) {
                    unitNames.put(code, n);
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            dbLabel.setText("Database error: " + ex.getMessage());
        }

        distanceLabel.setText(msg("distance") + " (" + unitName("KM") + ")");
        speedLabel.setText(msg("speed") + " (" + unitName("KMH") + ")");
        calcButton.setText("OK");
    }

    private String unitName(String code) {
        return unitNames.getOrDefault(code, code);       // code shown if the name is unavailable
    }

    private void calculate() {
        try {
            double distance = parseNumber(distanceField.getText());
            double speed = parseNumber(speedField.getText());
            double time = calTime(distance, speed);

            String timeText = localizeDigits(String.format(Locale.ROOT, "%.2f", time));
            resultLabel.setText(msg("time") + " " + timeText + " " + unitName("H"));
            messageLabel.setText(msg("message"));

            if (distance >= 0 && speed > 0) {
                saveToDatabase(distance, speed, time, currentLang.code());
                dbLabel.setText("Saved to database. (names: " + currentApproach + ")");
            } else {
                dbLabel.setText("Not saved: distance must be >= 0 and speed > 0.");
            }
        } catch (NumberFormatException ex) {
            resultLabel.setText("Invalid number");
            messageLabel.setText("");
            dbLabel.setText("");
        } catch (SQLException ex) {
            ex.printStackTrace();
            dbLabel.setText("Database error: " + ex.getMessage());
        }
    }

    /** Saves the calculation; the units are stored as foreign keys to unit(id), not as text. */
    private static void saveToDatabase(double distance, double speed, double time, String lang)
            throws SQLException {
        String sql = """
                INSERT INTO calculation
                    (distance, speed, time_hours, language, distance_unit_id, speed_unit_id)
                VALUES (?, ?, ?, ?,
                    (SELECT id FROM unit WHERE code = 'KM'),
                    (SELECT id FROM unit WHERE code = 'KMH'))
                """;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, distance);
            ps.setDouble(2, speed);
            ps.setDouble(3, time);
            ps.setString(4, lang);
            ps.executeUpdate();
        }
    }

    public static double calTime(double distance, double speed) {
        if (distance < 0 || speed <= 0) {
            return 0; // Avoid negative values or division by zero
        }
        return distance / speed;
    }

    public static void main(String[] args) {
        launch(args);
    }
}