package com.tj.linkmeter;

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Tablet only: the strip at the top of every tab while the phone rings or a call is on (docs/PHONE_CALLS.md): who, how long, and the
 * button to answer or end it. Tapping the words opens the Calls tab. Hidden on the Calls tab itself (that shows the whole call) and
 * when the user switched the strip off. The headset is on the phone; this only controls it.
 */
final class CallBanner extends LinearLayout {
    private final MainActivity act;
    private final CallWatch watch;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextView title;
    private final TextView detail;
    private IconButton answer;
    private IconButton hang;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (getVisibility() == VISIBLE) {
                paintText();
                ui.postDelayed(this, 1000);
            }
        }
    };

    CallBanner(MainActivity a, CallWatch w) {
        super(a);
        act = a;
        watch = w;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8));
        setBackgroundColor(0xFF16301F);
        setVisibility(GONE);

        IconButton icon = CallsViews.round(a, Icons.Kind.HANDSET, CallsViews.CALL_GREEN, 40, 0.5f, "Call", null);
        addView(icon);
        LinearLayout words = new LinearLayout(a);
        words.setOrientation(VERTICAL);
        title = Ui.text(a, "", 16, Ui.FG, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        detail = Ui.text(a, "", 12.5f, 0xFFB8D8C2, false);
        words.addView(title);
        words.addView(detail);
        words.setClickable(true);
        words.setOnClickListener(v -> act.openTab(Tab.CALLS));
        LayoutParams wp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wp.leftMargin = Ui.dp(a, 12);
        addView(words, wp);

        answer = CallsViews.round(a, Icons.Kind.HANDSET, CallsViews.CALL_GREEN, 46, 0.5f, "Answer", v -> {
            answer.setEnabled(false);
            watch.answer((r, e) -> {
                answer.setEnabled(true);
                CallsViews.report(act, r, e);
            });
        });
        hang = CallsViews.round(a, Icons.Kind.HANGUP, CallsViews.END_RED, 46, 0.5f, "End call", v -> {
            hang.setEnabled(false);
            watch.hangup((r, e) -> {
                hang.setEnabled(true);
                CallsViews.report(act, r, e);
            });
        });
        LayoutParams bp = new LayoutParams(Ui.dp(a, 46), Ui.dp(a, 46));
        bp.leftMargin = Ui.dp(a, 8);
        addView(hang, bp);
        LayoutParams ap = new LayoutParams(Ui.dp(a, 46), Ui.dp(a, 46));
        ap.leftMargin = Ui.dp(a, 8);
        addView(answer, ap);
    }

    private boolean ringing() {
        Calls.Call c = watch.lead();
        return c != null && Calls.RINGING.equals(c.state);
    }

    private void paintText() {
        Calls.Call c = watch.lead();
        if (c == null) {
            return;
        }
        title.setText(CallsViews.shownName(act, c.number, c.name, c.incoming));
        detail.setText(CallRules.status(c, watch.elapsed(c)));
    }

    /** Looks at the phone's latest picture and shows, changes or hides the strip. */
    void refresh() {
        Calls.Call c = watch.lead();
        boolean show = watch.status() == CallWatch.OK && c != null && Cfg.callsBanner(act) && !Tab.CALLS.equals(act.shownTitle());
        ui.removeCallbacks(tick);
        if (!show) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        boolean ring = ringing();
        setBackgroundColor(ring ? 0xFF1F4A2C : 0xFF16301F);
        answer.setVisibility(ring ? VISIBLE : GONE);
        hang.setContentDescription(ring ? "Decline" : "End call");
        paintText();
        ui.postDelayed(tick, 1000);
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }
}
