package com.tj.linkmeter;

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Tablet only: what the Calls tab shows while the phone rings or a call is on: who, how long, where the sound goes, Mute, the audio
 * button (headset, speaker, phone), the phone's own screen (for the keypad of a company's menu, which only the phone's own Phone app
 * may play) and a large End button; Answer and Decline while it rings. The voice itself is on the headset connected to the PHONE.
 */
final class InCallView extends LinearLayout {
    private final MainActivity act;
    private final CallWatch watch;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextView avatar;
    private final TextView name;
    private final TextView number;
    private final TextView status;
    private final TextView sound;
    private final LinearLayout buttons;
    private final LinearLayout ring;
    private IconButton mute;
    private TextView muteLabel;
    private IconButton audio;
    private TextView audioLabel;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (isAttachedToWindow()) {
                paintStatus();
                ui.postDelayed(this, 1000);
            }
        }
    };

    InCallView(MainActivity a, CallWatch w) {
        super(a);
        act = a;
        watch = w;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(Ui.dp(a, 16), Ui.dp(a, 24), Ui.dp(a, 16), Ui.dp(a, 16));

        avatar = TextsViews.avatar(a, 96);
        avatar.setTextSize(36);
        addView(avatar, new LayoutParams(Ui.dp(a, 96), Ui.dp(a, 96)));
        name = Ui.text(a, "", 28, Ui.FG, true);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(name, Ui.lp(a, 14));
        number = Ui.text(a, "", 14, Ui.DIM, false);
        number.setGravity(Gravity.CENTER);
        addView(number, Ui.lp(a, 2));
        status = Ui.text(a, "", 20, Ui.GREEN, false);
        status.setGravity(Gravity.CENTER);
        addView(status, Ui.lp(a, 8));
        sound = Ui.text(a, "", 12.5f, Ui.DIM, false);
        sound.setGravity(Gravity.CENTER);
        addView(sound, Ui.lp(a, 4));

        buttons = new LinearLayout(a);
        buttons.setOrientation(HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buildButtons();
        addView(buttons, Ui.lp(a, 36));

        ring = new LinearLayout(a);
        ring.setOrientation(HORIZONTAL);
        ring.setGravity(Gravity.CENTER);
        addView(ring, Ui.lp(a, 36));
        update();
    }

    private LinearLayout labelled(IconButton b, TextView label) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.addView(b);
        label.setGravity(Gravity.CENTER);
        col.addView(label, Ui.lp(act, 6));
        LayoutParams p = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        buttons.addView(col, p);
        return col;
    }

    private void buildButtons() {
        mute = CallsViews.round(act, Icons.Kind.MIC, Ui.CARD_HI, 68, 0.46f, "Mute", v -> toggleMute());
        muteLabel = Ui.text(act, "Mute", 12.5f, Ui.FG, false);
        labelled(mute, muteLabel);
        audio = CallsViews.round(act, Icons.Kind.VOL_UP, Ui.CARD_HI, 68, 0.46f, "Where the sound goes", v -> nextRoute());
        audioLabel = Ui.text(act, "Audio", 12.5f, Ui.FG, false);
        labelled(audio, audioLabel);
        IconButton screen = CallsViews.round(act, Icons.Kind.KEYPAD, Ui.CARD_HI, 68, 0.46f, "The phone's screen", v -> act.openTab(Tab.SCREEN));
        TextView screenLabel = Ui.text(act, "Phone screen", 12.5f, Ui.FG, false);
        labelled(screen, screenLabel);
    }

    private void toggleMute() {
        final boolean want = !watch.latest().muted;
        mute.setEnabled(false);
        watch.mute(want, (r, e) -> {
            mute.setEnabled(true);
            CallsViews.report(act, r, e);
        });
    }

    private void nextRoute() {
        Calls.Now n = watch.latest();
        final String to = HeadsetRules.nextRoute(n.route, !n.headset.isEmpty());
        audio.setEnabled(false);
        watch.route(to, (r, e) -> {
            audio.setEnabled(true);
            CallsViews.report(act, r, e);
        });
    }

    private void paintStatus() {
        Calls.Call c = watch.lead();
        if (c != null) {
            status.setText(CallRules.status(c, watch.elapsed(c)));
        }
    }

    /** Looks at the phone's latest picture. */
    void update() {
        Calls.Call c = watch.lead();
        if (c == null) {
            return;
        }
        Calls.Now n = watch.latest();
        String shown = CallsViews.shownName(act, c.number, c.name, c.incoming);
        TextsViews.bindAvatar(avatar, shown, c.number.isEmpty() ? shown : ContactBook.matchKey(c.number), false);
        name.setText(shown);
        String pretty = CallRules.pretty(c.number);
        number.setText(pretty.equals(shown) ? "" : pretty);
        number.setVisibility(pretty.isEmpty() || pretty.equals(shown) ? GONE : VISIBLE);
        boolean ringing = Calls.RINGING.equals(c.state);
        status.setTextColor(ringing ? Ui.AMBER : Ui.GREEN);
        paintStatus();
        sound.setText(ringing ? "" : HeadsetRules.route(n.route, n.headset));
        buttons.setVisibility(ringing ? GONE : VISIBLE);
        mute.set(n.muted ? Icons.Kind.MIC_OFF : Icons.Kind.MIC, n.muted ? Ui.BG : 0xFFFFFFFF, n.muted ? 0xFFFFFFFF : Ui.CARD_HI);
        muteLabel.setText(n.muted ? "Unmute" : "Mute");
        boolean hs = !n.headset.isEmpty();
        audio.set(Calls.ROUTE_HEADSET.equals(n.route) ? Icons.Kind.HEADSET : Icons.Kind.VOL_UP, 0xFFFFFFFF,
                Calls.ROUTE_SPEAKER.equals(n.route) ? Ui.ACCENT : Ui.CARD_HI);
        audioLabel.setText(HeadsetRules.routeButton(n.route, hs));
        ring.removeAllViews();
        if (ringing) {
            ring.addView(endButton("Decline", Icons.Kind.HANGUP, CallsViews.END_RED, true), big());
            ring.addView(endButton("Answer", Icons.Kind.HANDSET, CallsViews.CALL_GREEN, false), big());
        } else {
            ring.addView(endButton("End call", Icons.Kind.HANGUP, CallsViews.END_RED, true), big());
        }
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 1000);
    }

    private LayoutParams big() {
        LayoutParams p = new LayoutParams(0, Ui.dp(act, 76), 1f);
        p.setMargins(Ui.dp(act, 6), 0, Ui.dp(act, 6), 0);
        return p;
    }

    private View endButton(final String label, Icons.Kind kind, int color, final boolean hangup) {
        LinearLayout b = new LinearLayout(act);
        b.setOrientation(HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(CallsViews.ripple(color, Ui.dp(act, 38)));
        b.setClickable(true);
        b.setContentDescription(label);
        IconButton icon = new IconButton(act, kind, 0xFFFFFFFF, 0, label, null);
        icon.setScale(0.5f);
        b.addView(icon, new LayoutParams(Ui.dp(act, 44), Ui.dp(act, 44)));
        TextView t = Ui.text(act, label, 18, 0xFFFFFFFF, true);
        b.addView(t);
        b.setOnClickListener(v -> {
            b.setEnabled(false);
            CallWatch.Done d = (r, e) -> {
                b.setEnabled(true);
                CallsViews.report(act, r, e);
            };
            if (hangup) {
                watch.hangup(d);
            } else {
                watch.answer(d);
            }
        });
        return b;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 1000);
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }
}
