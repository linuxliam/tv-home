package com.liam.homescreen;

/** The launcher's settings that have a fixed set of choices, shared by the TV menu and the phone page. */
final class Choices {
    static final class Choice {
        final String key;
        final String title;
        final String[] values;
        final String[] labels;
        final String def;

        Choice(String key, String title, String[] values, String[] labels, String def) {
            this.key = key;
            this.title = title;
            this.values = values;
            this.labels = labels;
            this.def = def;
        }
    }

    static final class Toggle {
        final String key;
        final String title;
        final boolean def;

        Toggle(String key, String title, boolean def) {
            this.key = key;
            this.title = title;
            this.def = def;
        }
    }

    static final String[] SIZE_VALUES = {"1.0", "1.2", "1.4", "1.6"};
    static final String[] SIZE_LABELS = {"Small", "Medium", "Large", "Extra large"};
    static final String[] ANIM_VALUES = {"0", "110", "180", "300"};
    static final String[] ANIM_LABELS = {"Off", "Fast", "Normal", "Slow"};
    static final String[] ICON_VALUES = {"auto", "white", "black"};
    static final String[] ICON_LABELS = {"Automatic", "White", "Black"};
    static final String[] BG_LABELS = {"Ribbon", "Simple ribbon", "Snow", "Bokeh", "None"};
    static final String[] BGSPEED_VALUES = {"0.25", "0.45", "0.8"};
    static final String[] BGSPEED_LABELS = {"Very slow", "Slow", "Normal"};
    static final String[] UNIT_VALUES = {"f", "c"};
    static final String[] UNIT_LABELS = {"Fahrenheit", "Celsius"};

    static String[] themeLabels() {
        String[] l = new String[XmbView.THEME_ORDER.length];
        for (int i = 0; i < l.length; i++) l[i] = XmbView.themeLabel(XmbView.THEME_ORDER[i]);
        return l;
    }

    static Choice[] appearance() {
        return new Choice[]{
                new Choice("theme", "Menu colour theme", XmbView.THEME_ORDER, themeLabels(), "electric_blue"),
                new Choice("bg", "Background", XmbView.BG_ORDER, BG_LABELS, "ribbon"),
                new Choice("bgspeed", "Background speed", BGSPEED_VALUES, BGSPEED_LABELS, "0.45"),
                new Choice("size", "Menu size", SIZE_VALUES, SIZE_LABELS, "1.2"),
                new Choice("icons", "Icon and text colour", ICON_VALUES, ICON_LABELS, "auto"),
                new Choice("anim", "Animation speed", ANIM_VALUES, ANIM_LABELS, "180"),
        };
    }

    static Toggle[] toggles() {
        return new Toggle[]{
                new Toggle("shadows", "Shadows", true),
                new Toggle("frost", "Frosted glass top edge", true),
                new Toggle("hints", "Button hints", true),
                new Toggle("sounds", "Menu sounds", false),
                new Toggle("clock24", "24-hour clock", false),
        };
    }

    static Choice units() {
        return new Choice("units", "Temperature", UNIT_VALUES, UNIT_LABELS, "f");
    }

    static boolean allowed(Choice c, String v) {
        for (String x : c.values) if (x.equals(v)) return true;
        return false;
    }
}
