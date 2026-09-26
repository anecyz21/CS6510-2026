package selfcheckout.api;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Json {
    private Json() { }
    public static String stringField(String json, String field) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(json == null ? "" : json);
        return matcher.find() ? matcher.group(1) : null;
    }
    public static String quote(String value) { return "\"" + (value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")) + "\""; }
}
