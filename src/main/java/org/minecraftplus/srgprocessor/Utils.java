package org.minecraftplus.srgprocessor;

import java.util.regex.Pattern;

public class Utils {

    public static final String PRIMITIVE_TYPES = "ZCBSIJFDV";
    public static final Pattern DESC = Pattern.compile("\\[*L(?<cls>[^;]+);|([" + PRIMITIVE_TYPES + "])");

    public static String[] splitCase(String input) {
        return input.split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])");
    }
}
