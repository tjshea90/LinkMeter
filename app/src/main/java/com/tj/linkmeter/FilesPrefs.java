package com.tj.linkmeter;

import android.content.Context;
import android.content.SharedPreferences;

/** What the user chose in the file manager (sort order, list or grid, hidden files), kept between runs. */
final class FilesPrefs {
    private final SharedPreferences sp;

    FilesPrefs(Context c) {
        sp = c.getSharedPreferences("files", Context.MODE_PRIVATE);
    }

    FileSort.Order order() {
        try {
            return FileSort.Order.valueOf(sp.getString("order", "NAME"));
        } catch (IllegalArgumentException e) {
            return FileSort.Order.NAME;
        }
    }

    /** True: A to Z, smallest, oldest. The default for the date is newest first, so it is stored per order. */
    boolean ascending() {
        FileSort.Order o = order();
        return sp.getBoolean("asc_" + o.name(), o == FileSort.Order.NAME || o == FileSort.Order.KIND);
    }

    void setOrder(FileSort.Order o, boolean ascending) {
        sp.edit().putString("order", o.name()).putBoolean("asc_" + o.name(), ascending).apply();
    }

    boolean grid() {
        return sp.getBoolean("grid", false);
    }

    void setGrid(boolean g) {
        sp.edit().putBoolean("grid", g).apply();
    }

    boolean hidden() {
        return sp.getBoolean("hidden", false);
    }

    void setHidden(boolean h) {
        sp.edit().putBoolean("hidden", h).apply();
    }
}
