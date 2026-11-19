package com.tj.linkmeter;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Tablet only: the floating phone's face, a small dark glass card (docs/FLOATING_PHONE.md): who and how long, the headset's battery,
 * the Bluetooth buttons for the phone and for this tablet, a green Call and a red End button, and a dial pad that opens and closes
 * (the "mini phone"). It decides nothing: what to show comes in as a {@link Model} (built by the service from the phone's picture and
 * {@link FloatRules}), what the user presses goes out through the {@link Host}. A drag on the top strip moves the whole card.
 */
final class MiniPhoneView extends LinearLayout {
    /** What the floating phone does for the user. All called on the screen's thread. */
    interface Host {
        void answer();

        void hangup();

        /** The number on the pad passed {@link CallRules#check} already; the phone checks again. */
        void dial(String number);

        /** The headset is not connected to the phone and the user asked to be warned: show the warning and wait for a second press. */
        void warnHeadset();

        /** The digits on the pad changed (a warning that was shown no longer fits). */
        void typedChanged();

        /** The headset's link to the phone: connect it when it is not, disconnect it when it is. */
        void phoneBluetooth();

        /** The headset's link to this tablet: the same. */
        void tabletBluetooth();

        /** The card was dragged by this much (pixels). */
        void dragBy(int dx, int dy);

        /** The finger was lifted after a drag. */
        void dragEnd();

        /** The pad opened or closed: the window has another size. */
        void sizeChanged(boolean expanded);

        /** The user pressed the cross (only offered when no call is on). */
        void closed();
    }

    /** Everything the card shows. */
    static final class Model {
        /** The call's state: "", {@link Calls#RINGING}, {@link Calls#DIALING}, {@link Calls#ACTIVE} or {@link Calls#HELD}. */
        String call = "";
        String title = "Phone";
        String subtitle = "";
        /** The watch could not hear the phone (so the call buttons are off). */
        boolean offline;
        boolean headsetOnPhone;
        String headsetName = "";
        /** The battery icon shows: the headset is connected to the phone (or to this tablet) and a level, or its absence, is worth saying. */
        boolean batteryShown;
        int battery = Calls.BATTERY_UNKNOWN;
        boolean phoneBusy;
        /** The headset is connected to THIS tablet / the tablet's Bluetooth is able to carry it (paired and on). */
        boolean tabletOn;
        boolean tabletAble;
        boolean tabletBusy;
        /** The "ask before calling without the headset" setting, and whether the user already saw the warning. */
        boolean askFirst;
        boolean armed;
        /** A line to read (a refusal, a warning, what a switch did): "" for none. */
        String message = "";
        boolean messageIsBad;
    }

    static final int WIDTH_DP = 252;
    private static final int ACCENT_ON = 0xFF2F6BFF;

    private final Host host;
    private final GradientDrawable bg;
    private final View dot;
    private final TextView title;
    private final TextView sub;
    private final BatteryIconView battery;
    private final IconButton chevron;
    private final IconButton close;
    private final LinearLayout header;
    private final LinearLayout padBox;
    private final TextView typedView;
    private final TextView message;
    private IconButton btPhone;
    private IconButton btTablet;
    private IconButton green;
    private IconButton red;
    private TextView btPhoneLabel;
    private TextView btTabletLabel;
    private TextView greenLabel;
    private TextView redLabel;
    private boolean expanded;
    private String typed = "";
    private Model model = new Model();
    private ValueAnimator pulse;
    private ValueAnimator halo;
    private int touchSlop;

    MiniPhoneView(Context c, Host h) {
        super(c);
        host = h;
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        setOrientation(VERTICAL);
        setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 8));
        bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xF51C2330, 0xF50E1218});
        bg.setCornerRadius(Ui.dp(c, 22));
        bg.setStroke(Ui.dp(c, 1), 0x44FFFFFF);
        setBackground(bg);
        setElevation(Ui.dp(c, 8));

        // ---- the top strip: a grip, who, how long, the battery, the pad's arrow, the cross
        header = new LinearLayout(c);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(Ui.dp(c, 40));
        header.setOnTouchListener(this::dragTouch);
        header.setContentDescription("Floating phone. Drag to move, tap to open the dial pad.");
        dot = new View(c);
        dot.setBackground(Ui.round(0xFF8A8F98, Ui.dp(c, 6)));
        header.addView(dot, new LayoutParams(Ui.dp(c, 12), Ui.dp(c, 12)));
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(VERTICAL);
        title = Ui.text(c, "Phone", 14.5f, Ui.FG, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        sub = Ui.text(c, "", 12, Ui.DIM, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);
        words.addView(sub);
        LayoutParams wp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wp.leftMargin = Ui.dp(c, 9);
        header.addView(words, wp);
        battery = new BatteryIconView(c, 0.9f);
        battery.setVisibility(GONE);
        LayoutParams bp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = Ui.dp(c, 4);
        header.addView(battery, bp);
        chevron = icon(c, Icons.Kind.CHEVRON_DOWN, 0, 0.62f, "Open the dial pad", v -> setExpanded(!expanded));
        LayoutParams cp = new LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28));
        cp.leftMargin = Ui.dp(c, 4);
        header.addView(chevron, cp);
        close = icon(c, Icons.Kind.CLOSE, 0, 0.55f, "Close the floating phone", v -> host.closed());
        LayoutParams xp = new LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24));
        xp.leftMargin = Ui.dp(c, 2);
        header.addView(close, xp);
        addView(header, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- the dial pad: the number, then twelve keys (closed until the arrow is pressed)
        padBox = new LinearLayout(c);
        padBox.setOrientation(VERTICAL);
        padBox.setVisibility(GONE);
        LinearLayout numRow = new LinearLayout(c);
        numRow.setOrientation(HORIZONTAL);
        numRow.setGravity(Gravity.CENTER_VERTICAL);
        typedView = Ui.text(c, "", 22, Ui.FG, false);
        typedView.setSingleLine(true);
        typedView.setEllipsize(TextUtils.TruncateAt.START);
        typedView.setGravity(Gravity.CENTER);
        numRow.addView(typedView, new LayoutParams(0, Ui.dp(c, 40), 1f));
        IconButton back = icon(c, Icons.Kind.BACKSPACE, 0, 0.6f, "Delete a digit", v -> {
            typed = CallRules.backspace(typed);
            typedChanged();
        });
        back.setOnLongClickListener(v -> {
            typed = "";
            typedChanged();
            return true;
        });
        numRow.addView(back, new LayoutParams(Ui.dp(c, 34), Ui.dp(c, 34)));
        padBox.addView(numRow);
        for (char[] row : CallRules.KEYS) {
            LinearLayout r = new LinearLayout(c);
            r.setOrientation(HORIZONTAL);
            for (final char k : row) {
                r.addView(key(c, k), keyParams(c));
            }
            padBox.addView(r, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        addView(padBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        message = Ui.text(c, "", 11.5f, Ui.AMBER, false);
        message.setGravity(Gravity.CENTER);
        message.setVisibility(GONE);
        LayoutParams mp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = Ui.dp(c, 4);
        addView(message, mp);

        buildActions(c);
        paintTyped();
        bind(model);
    }

    // ------------------------------------------------------------------ building

    private static IconButton icon(Context c, Icons.Kind kind, int bgColor, float scale, String label, View.OnClickListener l) {
        IconButton b = new IconButton(c, kind, Ui.FG, bgColor, label, l);
        b.setScale(scale);
        return b;
    }

    private static LayoutParams keyParams(Context c) {
        LayoutParams p = new LayoutParams(0, Ui.dp(c, 44), 1f);
        p.setMargins(Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3));
        return p;
    }

    private View key(Context c, final char k) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setBackground(CallsViews.ripple(0xFF262C36, Ui.dp(c, 14)));
        v.setContentDescription(k == '*' ? "star" : k == '#' ? "pound" : String.valueOf(k));
        TextView digit = Ui.text(c, String.valueOf(k), 20, Ui.FG, false);
        digit.setGravity(Gravity.CENTER);
        v.addView(digit);
        String letters = k == '0' ? "+" : CallRules.letters(k);
        if (!letters.isEmpty()) {
            TextView s = Ui.text(c, letters, 8, Ui.DIM, true);
            s.setGravity(Gravity.CENTER);
            v.addView(s);
        }
        v.setClickable(true);
        v.setOnClickListener(x -> {
            typed = CallRules.press(typed, k);
            typedChanged();
        });
        if (k == '0') {
            v.setOnLongClickListener(x -> {
                typed = CallRules.afterLongZero(typed); // a long press on 0 types the plus, as on the Calls tab
                typedChanged();
                return true;
            });
        }
        return v;
    }

    private LinearLayout column(Context c, IconButton b, TextView label) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(b, new LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        col.addView(label, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return col;
    }

    private void buildActions(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        btPhone = icon(c, Icons.Kind.BLUETOOTH, Ui.CARD_HI, 0.5f, "Headset Bluetooth link to the phone", v -> host.phoneBluetooth());
        btPhoneLabel = Ui.text(c, "Phone", 10, Ui.DIM, true);
        green = icon(c, Icons.Kind.HANDSET, CallsViews.CALL_GREEN, 0.52f, "Call", v -> greenPressed());
        greenLabel = Ui.text(c, "Call", 10, Ui.DIM, true);
        red = icon(c, Icons.Kind.HANGUP, CallsViews.END_RED, 0.52f, "End call", v -> host.hangup());
        redLabel = Ui.text(c, "End", 10, Ui.DIM, true);
        btTablet = icon(c, Icons.Kind.BLUETOOTH, Ui.CARD_HI, 0.5f, "Headset Bluetooth link to this tablet", v -> host.tabletBluetooth());
        btTabletLabel = Ui.text(c, "Tablet", 10, Ui.DIM, true);
        LayoutParams cell = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(column(c, btPhone, btPhoneLabel), cell);
        row.addView(column(c, green, greenLabel), new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(column(c, red, redLabel), new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(column(c, btTablet, btTabletLabel), new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LayoutParams rp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Ui.dp(c, 6);
        addView(row, rp);
    }

    // ------------------------------------------------------------------ the pad

    boolean expanded() {
        return expanded;
    }

    String typed() {
        return typed;
    }

    void clearTyped() {
        typed = "";
        paintTyped();
    }

    void setExpanded(boolean open) {
        if (expanded == open) {
            return;
        }
        expanded = open;
        padBox.setVisibility(open ? VISIBLE : GONE);
        chevron.set(open ? Icons.Kind.UP : Icons.Kind.CHEVRON_DOWN, Ui.FG, 0);
        chevron.setContentDescription(open ? "Close the dial pad" : "Open the dial pad");
        if (!open) {
            typed = "";
            paintTyped();
        }
        bind(model);
        host.sizeChanged(open);
    }

    private void typedChanged() {
        model.message = ""; // what was said about the old number no longer fits
        model.armed = false;
        paintTyped();
        host.typedChanged();
    }

    private void paintTyped() {
        String shown = CallRules.pretty(typed);
        typedView.setText(shown.isEmpty() ? "Enter a number" : shown);
        typedView.setTextColor(shown.isEmpty() ? Ui.DIM : Ui.FG);
        typedView.setTextSize(shown.isEmpty() ? 14 : shown.length() > 14 ? 17 : 22);
        bind(model);
    }

    private void greenPressed() {
        FloatRules.Green g = FloatRules.green(model.call, expanded, typed, model.headsetOnPhone, model.askFirst, model.armed);
        switch (g) {
            case ANSWER:
                host.answer();
                break;
            case OPEN_PAD:
                setExpanded(true);
                break;
            case DIAL:
            case ASK_HEADSET: {
                CallRules.Dial d = CallRules.check(typed);
                if (!d.ok()) {
                    model.message = d.why(); // an emergency number, a code, letters: said here, nothing is sent
                    model.messageIsBad = true;
                    bind(model);
                    return;
                }
                if (g == FloatRules.Green.ASK_HEADSET) {
                    host.warnHeadset();
                } else {
                    host.dial(d.number);
                }
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ dragging

    private float lastRawX;
    private float lastRawY;
    private float downRawX;
    private float downRawY;
    private boolean moved;

    private boolean dragTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = lastRawX = e.getRawX();
                downRawY = lastRawY = e.getRawY();
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!moved && Math.hypot(e.getRawX() - downRawX, e.getRawY() - downRawY) > touchSlop) {
                    moved = true;
                }
                if (moved) {
                    host.dragBy(Math.round(e.getRawX() - lastRawX), Math.round(e.getRawY() - lastRawY));
                    lastRawX = e.getRawX();
                    lastRawY = e.getRawY();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (moved) {
                    host.dragEnd();
                } else {
                    setExpanded(!expanded);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (moved) {
                    host.dragEnd();
                }
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ painting what the model says

    /** Shows what the model says. Cheap: called at every change and once a second during a call. */
    void bind(Model m) {
        model = m;
        boolean ringing = Calls.RINGING.equals(m.call);
        boolean inCall = !m.call.isEmpty();
        title.setText(m.title);
        sub.setText(m.subtitle);
        sub.setTextColor(ringing ? Ui.AMBER : inCall ? Ui.GREEN : m.offline ? Ui.AMBER : Ui.DIM);
        dot.setBackground(Ui.round(ringing ? Ui.AMBER : inCall ? Ui.GREEN : m.offline ? Ui.RED : 0xFF8A8F98, Ui.dp(getContext(), 6)));
        battery.setVisibility(m.batteryShown ? VISIBLE : GONE);
        if (m.batteryShown) {
            battery.setLevel(m.battery, true);
            battery.setContentDescription(BatteryRules.words(m.battery, m.headsetName));
        }
        close.setVisibility(inCall ? GONE : VISIBLE);

        // the two Bluetooth buttons: lit blue while the headset is connected there
        btPhone.set(Icons.Kind.BLUETOOTH, m.headsetOnPhone ? 0xFFFFFFFF : Ui.DIM, m.headsetOnPhone ? ACCENT_ON : Ui.CARD_HI);
        btPhone.setAlpha(m.phoneBusy ? 0.5f : 1f);
        btPhone.setEnabled(!m.phoneBusy && !m.offline);
        btPhoneLabel.setText(m.phoneBusy ? "…" : m.headsetOnPhone ? "Phone on" : "Phone off");
        btPhoneLabel.setTextColor(m.headsetOnPhone ? 0xFF8FB0FF : Ui.DIM);
        btTablet.set(Icons.Kind.BLUETOOTH, m.tabletOn ? 0xFFFFFFFF : Ui.DIM, m.tabletOn ? ACCENT_ON : Ui.CARD_HI);
        btTablet.setAlpha(m.tabletBusy ? 0.5f : m.tabletAble ? 1f : 0.55f);
        btTablet.setEnabled(!m.tabletBusy);
        btTabletLabel.setText(m.tabletBusy ? "…" : m.tabletOn ? "Tablet on" : "Tablet off");
        btTabletLabel.setTextColor(m.tabletOn ? 0xFF8FB0FF : Ui.DIM);

        // the green and the red button
        FloatRules.Green g = FloatRules.green(m.call, expanded, typed, m.headsetOnPhone, m.askFirst, m.armed);
        boolean greenOn = !m.offline && g != FloatRules.Green.NONE;
        green.setAlpha(greenOn ? 1f : 0.35f);
        green.setEnabled(greenOn);
        green.setContentDescription(FloatRules.greenLabel(g));
        greenLabel.setText(g == FloatRules.Green.ANSWER ? "Answer" : g == FloatRules.Green.ASK_HEADSET ? "Anyway" : g == FloatRules.Green.OPEN_PAD ? "Dial" : "Call");
        greenLabel.setTextColor(greenOn ? 0xFF7BE0A4 : Ui.DIM);
        boolean redOn = !m.offline && FloatRules.redOn(m.call);
        red.setAlpha(redOn ? 1f : 0.35f);
        red.setEnabled(redOn);
        red.setContentDescription(FloatRules.redLabel(m.call));
        redLabel.setText(ringing ? "Decline" : "End");
        redLabel.setTextColor(redOn ? 0xFFFF8A8A : Ui.DIM);

        message.setText(m.message);
        message.setTextColor(m.messageIsBad ? Ui.RED : Ui.AMBER);
        message.setVisibility(m.message.isEmpty() ? GONE : VISIBLE);
        animate(ringing);
    }

    /** The card breathes while the phone rings: an amber rim, and the green button swells. */
    private void animate(boolean ringing) {
        if (!ringing || !isAttachedToWindow()) {
            if (pulse != null) {
                pulse.cancel();
                pulse = null;
                bg.setStroke(Ui.dp(getContext(), 1), 0x44FFFFFF);
            }
            if (halo != null) {
                halo.cancel();
                halo = null;
                green.setScaleX(1f);
                green.setScaleY(1f);
            }
            return;
        }
        if (pulse == null) {
            pulse = ValueAnimator.ofObject(new ArgbEvaluator(), 0x44FFFFFF, 0xFFFFB300);
            pulse.setDuration(650);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.addUpdateListener(a -> bg.setStroke(Ui.dp(getContext(), 2), (Integer) a.getAnimatedValue()));
            pulse.start();
        }
        if (halo == null) {
            halo = ValueAnimator.ofFloat(1f, 1.16f);
            halo.setDuration(500);
            halo.setRepeatMode(ValueAnimator.REVERSE);
            halo.setRepeatCount(ValueAnimator.INFINITE);
            halo.addUpdateListener(a -> {
                float s = (Float) a.getAnimatedValue();
                green.setScaleX(s);
                green.setScaleY(s);
            });
            halo.start();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animate(Calls.RINGING.equals(model.call));
    }

    @Override
    protected void onDetachedFromWindow() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        if (halo != null) {
            halo.cancel();
            halo = null;
        }
        super.onDetachedFromWindow();
    }
}
