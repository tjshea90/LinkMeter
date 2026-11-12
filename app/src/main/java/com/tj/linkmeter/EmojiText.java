package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/TextsLogicTest.java). Is a text only emoji (and nothing else but spaces)? Such a text is
 * drawn big, without the words' bubble, like the texting apps do: "👍", "😂😂", "❤️". The quick replies send exactly such texts.
 */
final class EmojiText {
    private EmojiText() {}

    /** The emoji a long press on a message offers to react with, in this order. */
    static final String[] QUICK = {"👍", "❤️", "😂", "😮", "😢", "🙏"};

    /** More emoji to react with (the "+" of a message's menu). */
    static final String[] MORE = {"😀", "😁", "🤣", "😊", "😍", "🥰", "😘", "😎", "🤩", "🥳", "🤗", "🤔", "😏", "🙄", "😬", "😴", "😳",
        "🥺", "😭", "😱", "😡", "🤯", "😇", "🤤", "👏", "🙌", "👌", "✌️", "🤞", "💪", "👋", "👀", "🔥", "✨", "🎉", "💯", "✅", "❌",
        "💔", "💙", "💚", "💜", "🤦", "🤷", "👎", "‼️", "❓", "😅", "😉"};

    private static boolean isModifier(int cp) {
        return cp == 0x200D || cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 || (cp >= 0x1F3FB && cp <= 0x1F3FF) || (cp >= 0xE0020 && cp <= 0xE007F);
    }

    private static boolean isBase(int cp) {
        return (cp >= 0x1F300 && cp <= 0x1FAFF) // pictographs, faces, hands, food, transport, symbols
                || (cp >= 0x1F000 && cp <= 0x1F2FF) // cards, tiles, enclosed marks, flags' letters
                || (cp >= 0x2600 && cp <= 0x27BF) // misc symbols and dingbats (the heart, the star, the check)
                || (cp >= 0x2B00 && cp <= 0x2BFF) // stars and arrows used as emoji
                || (cp >= 0x2190 && cp <= 0x21FF && cp != 0x2190) // some arrows are drawn as emoji
                || cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299;
    }

    /** How many emoji the text holds when it holds nothing else (spaces are ignored); 0 when it holds a word, a digit, or no emoji. */
    static int count(String s) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        boolean joined = false; // the previous mark was a joiner: the next emoji is part of the same picture
        boolean any = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                joined = false;
                continue;
            }
            if (isModifier(cp)) {
                if (!any) {
                    return 0;
                }
                joined = cp == 0x200D;
                continue;
            }
            if (!isBase(cp)) {
                return 0;
            }
            if (!joined) {
                n++;
            }
            joined = false;
            any = true;
        }
        return n;
    }

    /** True when the text is one to {@code max} emoji and nothing else. */
    static boolean only(String s, int max) {
        int n = count(s);
        return n >= 1 && n <= max;
    }
}
