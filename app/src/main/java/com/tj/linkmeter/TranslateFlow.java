package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/TranslationTest.java). What Send does in a conversation whose texts are translated into
 * Spanish (Mexico) before they go. The first press translates your English and puts the Spanish into the box for you to check, the way
 * a keyboard's translation would; your English is kept (Undo puts it back, "Send my English" sends it instead). The next press sends
 * what is in the box, also when you changed a word of the Spanish. Nothing is ever sent that you have not seen.
 */
final class TranslateFlow {
    enum Do {
        /** Nothing to do (an empty box, or a translation is on its way). */
        NOTHING,
        /** Send what is in the box. */
        SEND,
        /** Translate what is in the box first. */
        TRANSLATE
    }

    private boolean on;
    /** Your English while its Spanish is in the box to be checked; null when nothing is being checked. */
    private String english;
    /** The English being translated right now; null when nothing is. */
    private String asked;
    /** The number of the latest question, to know an answer that came too late. */
    private int seq;
    private String by = "";
    private String why = "";

    boolean on() {
        return on;
    }

    /** The conversation's switch. Switched off, everything that was going on is forgotten (a late answer changes nothing). */
    void setOn(boolean on) {
        this.on = on;
        if (!on) {
            reset();
        }
    }

    /** Is the Spanish in the box waiting for you to check it? */
    boolean checking() {
        return english != null;
    }

    boolean translating() {
        return asked != null;
    }

    /** Your English, while it is being checked (else null). */
    String english() {
        return english;
    }

    /** Who translated it ("MyMemory", "Google Translate"); "" before anything was translated. */
    String by() {
        return by;
    }

    /** Why the last translation did not work, a whole sentence; "" when it did or none was asked for. */
    String why() {
        return why;
    }

    /** Send was pressed with this in the box (attachments without words are the caller's: they are simply sent). */
    Do send(String box) {
        if (box == null || box.trim().isEmpty()) {
            return Do.NOTHING;
        }
        if (!on || checking()) {
            return Do.SEND;
        }
        return translating() ? Do.NOTHING : Do.TRANSLATE;
    }

    /** A translation of this English starts; the number to give back with its answer. */
    int start(String englishText) {
        asked = englishText;
        why = "";
        return ++seq;
    }

    /**
     * The answer to question n. box: what is in the box now. The text to put into the box (the Spanish), or null to leave the box alone:
     * an older question, a translation that did not work ({@link #why} says why), or a box you changed meanwhile.
     */
    String answer(int n, String box, boolean ok, String text, String byWhom, String whyNot) {
        if (n != seq || asked == null) {
            return null;
        }
        String a = asked;
        asked = null;
        if (!ok) {
            why = whyNot == null || whyNot.isEmpty() ? "The text could not be translated." : whyNot;
            return null;
        }
        if (box == null || !box.equals(a)) {
            why = "Your text changed while it was being translated. Press Send to translate it again.";
            return null;
        }
        english = a;
        by = byWhom == null ? "" : byWhom;
        why = "";
        return text;
    }

    /** Undo: your English, to put back into the box. Nothing is being checked any more. */
    String undo() {
        String e = english;
        reset();
        return e;
    }

    /** "Send my English": what to send instead (your English while checking, else the box as it is). */
    String sendEnglish(String box) {
        String e = english != null ? english : box;
        reset();
        return e;
    }

    /** The message went. */
    void sent() {
        reset();
    }

    /** You changed the box: emptied while checking, the check is over (and the next text is translated again). */
    void boxChanged(String box) {
        if (checking() && (box == null || box.trim().isEmpty())) {
            reset();
        }
    }

    private void reset() {
        english = null;
        asked = null;
        why = "";
    }
}
