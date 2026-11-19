package com.tj.linkmeter;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tablet only: the headset screen of the Calls tab: whether the headset is connected to the PHONE, a button that connects it (or
 * says in plain words why it cannot and what to do instead), the list of paired headsets to choose from, and pairing a new one. What
 * to say and which buttons to show is decided by {@link HeadsetRules}. The sound of a call never goes through the tablet: the headset
 * belongs to the phone.
 */
final class HeadsetPanel extends LinearLayout {
    private final CallsTab tab;
    private final MainActivity act;
    private final LinearLayout col;
    private boolean working;
    private String workingText = "";
    private Calls.Found found;
    private int gen;
    // the headset's link to THIS tablet (the second card): what the last try said, and whether one is running
    private String tabletProblem = "";
    private boolean tabletWorking;
    private String tabletWorkingText = "";
    private final Runnable tabletChanged = this::render;

    HeadsetPanel(CallsTab t) {
        super(t.act);
        tab = t;
        act = t.act;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BG);
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 4));
        head.addView(TextsViews.icon(act, Icons.Kind.BACK, "Back", v -> tab.closeHeadset()));
        head.addView(Ui.text(act, "Headset", 20, Ui.FG, true), new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addView(head);
        col = Ui.column(act);
        addView(Ui.scroll(act, col), new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        render();
        reload();
    }

    /** Asks the phone how the headset is and draws what it says. */
    void reload() {
        final int my = ++gen;
        tab.net().execute(() -> {
            Calls.Headsets h = null;
            IOException bad = null;
            try {
                h = tab.remote().headsets();
            } catch (IOException e) {
                bad = e;
            }
            final Calls.Headsets fh = h;
            final IOException fb = bad;
            act.ui.post(() -> {
                if (my != gen) {
                    return;
                }
                if (fh != null) {
                    tab.setHeadsets(fh);
                } else {
                    CallsViews.report(act, null, fb);
                }
                render();
            });
        });
    }

    private interface Job {
        Calls.Result go(RemoteCalls rc) throws IOException;
    }

    /** Runs one request at the phone with a "working" line, then says what came of it and looks again. */
    private void work(String what, final Job job, final boolean connecting) {
        if (working) {
            return;
        }
        working = true;
        workingText = what;
        render();
        tab.net().execute(() -> {
            Calls.Result r = null;
            IOException bad = null;
            try {
                r = job.go(tab.remote());
            } catch (IOException e) {
                bad = e;
            }
            final Calls.Result fr = r;
            final IOException fb = bad;
            act.ui.post(() -> {
                working = false;
                if (connecting) {
                    tab.setHeadsetProblem(fr != null ? (fr.ok ? "" : fr.why) : TextsTab.explain(fb));
                } else if (fr != null && !fr.ok || fb != null) {
                    CallsViews.report(act, fr, fb);
                } else if (fr != null && !fr.out.isEmpty()) {
                    Toast.makeText(act, fr.out, Toast.LENGTH_SHORT).show();
                }
                reload();
            });
        });
    }

    private void press(HeadsetRules.Do what) {
        switch (what) {
            case ALLOW:
                work("Asking the phone to allow Bluetooth…", rc -> {
                    Calls.State s = rc.grant();
                    return s.bluetooth ? Calls.Result.ok("Bluetooth is allowed.") : Calls.Result.fail(s.note.isEmpty() ? "The phone did not allow it." : s.note);
                }, false);
                break;
            case CONNECT:
                work("Connecting the headset to the phone… (up to 25 seconds)", rc -> rc.headsetConnect(""), true);
                break;
            case DISCONNECT:
                work("Disconnecting…", RemoteCalls::headsetDisconnect, false);
                break;
            case CHOOSE:
                choose();
                break;
            case PAIR:
                scan();
                break;
            case SETTINGS:
                work("Opening the phone's Bluetooth settings…", rc -> {
                    Calls.Result r = rc.bluetoothSettings();
                    if (r.ok) {
                        act.ui.post(() -> act.openTab(Tab.SCREEN)); // the phone's screen, to finish there
                    }
                    return r;
                }, false);
                break;
            case SCREEN:
                act.openTab(Tab.SCREEN);
                break;
            default:
                reload();
                break;
        }
    }

    private void choose() {
        Calls.Headsets h = tab.headsets();
        if (h == null || h.devices.isEmpty()) {
            return;
        }
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        for (final Calls.Device d : h.devices) {
            labels.add(d.name + (d.connected ? " (connected)" : ""));
            acts.add(() -> work("Choosing " + d.name + "…", rc -> {
                Calls.Result r = rc.headsetChoose(d.addr);
                if (r.ok && !d.connected) {
                    return rc.headsetConnect(d.addr);
                }
                return r;
            }, true));
        }
        TextsViews.menu(act, "Choose your headset", labels.toArray(new String[0]), acts.toArray(new Runnable[0]));
    }

    // ------------------------------------------------------------------ pairing a new headset

    private void scan() {
        if (working) {
            return;
        }
        found = new Calls.Found();
        found.scanning = true;
        found.note = "Put your headset in pairing mode (hold its power button until it flashes).";
        render();
        final int my = ++gen;
        tab.net().execute(() -> {
            try {
                Calls.Result r = tab.remote().headsetScan();
                if (!r.ok) {
                    act.ui.post(() -> {
                        found = null;
                        CallsViews.report(act, r, null);
                        render();
                    });
                    return;
                }
                for (int i = 0; i < 12 && my == gen; i++) {
                    Thread.sleep(1500);
                    final Calls.Found f = tab.remote().headsetFound();
                    act.ui.post(() -> {
                        if (my == gen) {
                            found = f;
                            render();
                        }
                    });
                    if (!f.scanning) {
                        break;
                    }
                }
            } catch (IOException e) {
                act.ui.post(() -> {
                    found = null;
                    CallsViews.report(act, null, e);
                    render();
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void pair(final Calls.Device d) {
        gen++; // stops the search's updates
        work("Pairing with " + d.name + "… (up to 25 seconds)", rc -> rc.headsetPair(d.addr), true);
        found = null;
    }

    // ------------------------------------------------------------------ the battery (the phone's reading of the headset)

    private LinearLayout batteryCard(Calls.Device d) {
        LinearLayout card = Ui.card(act, "Headset battery", null);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        BatteryIconView icon = new BatteryIconView(act, 2.2f);
        icon.setLevel(d.battery, false);
        row.addView(icon);
        card.addView(row, Ui.lp(act, 8));
        TextView line = Ui.body(act, BatteryRules.words(d.battery, d.name));
        line.setTextColor(BatteryRules.known(d.battery) ? Ui.FG : Ui.DIM);
        card.addView(line, Ui.lp(act, 6));
        return card;
    }

    // ------------------------------------------------------------------ the headset on THIS tablet

    private void tabletWork(String what) {
        tabletWorking = true;
        tabletWorkingText = what;
        render();
    }

    private void tabletDone(TabletHeadsetRules.Way way, String say) {
        tabletWorking = false;
        tabletProblem = way == TabletHeadsetRules.Way.FAILED ? say : "";
        if (way != TabletHeadsetRules.Way.DONE || !say.isEmpty()) {
            Toast.makeText(act, say, Toast.LENGTH_LONG).show();
        }
        render();
    }

    private void pressTablet(TabletHeadsetRules.Do what) {
        if (tabletWorking) {
            return;
        }
        final TabletHeadset tb = TabletHeadset.of(act);
        final TabletHeadsetRules.Facts f = tb.facts(tab.headsets());
        final TabletHeadsetRules.Dev d = TabletHeadsetRules.pick(f);
        switch (what) {
            case BLUETOOTH_ON:
                tabletWork("Switching the tablet's Bluetooth on…");
                tb.switchOn(act, act::inFront, way -> tabletDone(way, way == TabletHeadsetRules.Way.DONE ? "Bluetooth is on." : way == TabletHeadsetRules.Way.ASKED
                        ? "Android's Bluetooth list is open: switch Bluetooth on there." : "The tablet would not switch Bluetooth on."));
                break;
            case CONNECT:
                if (d != null) {
                    tabletWork("Connecting " + d.name + " to this tablet…");
                    tb.connect(act, d.addr, act::inFront, way -> tabletDone(way, TabletHeadsetRules.afterConnect(way, d.name)));
                }
                break;
            case DISCONNECT:
                if (d != null) {
                    tabletWork("Disconnecting " + d.name + " from this tablet…");
                    tb.disconnect(act, d.addr, act::inFront, way -> tabletDone(way, TabletHeadsetRules.afterDisconnect(way, d.name)));
                }
                break;
            case CHOOSE: {
                List<String> labels = new ArrayList<>();
                List<Runnable> acts = new ArrayList<>();
                for (final TabletHeadsetRules.Dev x : f.bonded) {
                    labels.add(x.name + (x.connected ? " (connected)" : ""));
                    acts.add(() -> {
                        tb.choose(x.addr);
                        render();
                    });
                }
                TextsViews.menu(act, "Choose the headset for this tablet", labels.toArray(new String[0]), acts.toArray(new Runnable[0]));
                break;
            }
            case PAIR:
            case SETTINGS:
                if (!act.inFront() || !TabletHeadset.openList(act)) {
                    Toast.makeText(act, "Open Settings > Connected devices > Bluetooth on the tablet.", Toast.LENGTH_LONG).show();
                }
                break;
            default:
                break;
        }
    }

    private LinearLayout tabletCard(Calls.Headsets phone) {
        TabletHeadset tb = TabletHeadset.of(act);
        TabletHeadsetRules.Facts f = tb.facts(phone);
        TabletHeadsetRules.View v = TabletHeadsetRules.of(f, tabletProblem);
        LinearLayout card = Ui.card(act, "This tablet", null);
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        IconButton icon = new IconButton(act, Icons.Kind.BLUETOOTH, v.tone == TabletHeadsetRules.Tone.GOOD ? Ui.GREEN : Ui.DIM, 0, "Bluetooth", null);
        icon.setScale(0.6f);
        head.addView(icon, new LayoutParams(Ui.dp(act, 30), Ui.dp(act, 30)));
        TextView title = Ui.text(act, v.title, 15, Ui.FG, true);
        LayoutParams tp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = Ui.dp(act, 8);
        head.addView(title, tp);
        card.addView(head, Ui.lp(act, 2));
        if (!v.line.isEmpty()) {
            TextView line = Ui.body(act, v.line);
            line.setTextColor(v.tone == TabletHeadsetRules.Tone.GOOD ? Ui.GREEN : v.tone == TabletHeadsetRules.Tone.WARN ? Ui.AMBER : Ui.FG);
            card.addView(line, Ui.lp(act, 6));
        }
        if (v.device != null && v.device.connected) {
            int level = tb.battery(v.device.addr);
            if (BatteryRules.known(level)) {
                BatteryIconView ic = new BatteryIconView(act, 1.3f);
                ic.setLevel(level, false);
                card.addView(ic, Ui.lp(act, 8));
            }
        }
        if (tabletWorking) {
            card.addView(Ui.text(act, tabletWorkingText, 14, Ui.ACCENT, true), Ui.lp(act, 8));
        }
        for (final TabletHeadsetRules.Button b : v.buttons) {
            Button btn = b.main ? Ui.primary(act, b.label, x -> pressTablet(b.what)) : Ui.button(act, b.label, x -> pressTablet(b.what));
            btn.setEnabled(!tabletWorking);
            card.addView(btn, Ui.lp(act, 8));
        }
        return card;
    }

    // ------------------------------------------------------------------ drawing

    private Button button(HeadsetRules.Button b) {
        final HeadsetRules.Do what = b.what;
        Button v = b.main ? Ui.primary(act, b.label, x -> press(what)) : Ui.button(act, b.label, x -> press(what));
        v.setEnabled(!working);
        return v;
    }

    private void render() {
        col.removeAllViews();
        Calls.Headsets hp = tab.headsets();
        Calls.Device now = hp == null || !hp.allowed ? null : hp.current();
        if (now != null && now.connected) {
            col.addView(batteryCard(now), Ui.lp(act, 0)); // the headset's battery, while it is connected to the phone
        }
        LinearLayout why = Ui.card(act, "The headset connects to the phone", null);
        why.addView(Ui.hint(act, "The phone makes the call, so your headset is paired with the phone, not with this tablet: the voice "
                + "goes straight from the headset to the phone, and keeps going if this tablet sleeps. This screen only helps you connect it. "
                + "Keep the phone within a few metres of where you sit."), Ui.lp(act, 4));
        col.addView(why, Ui.lp(act, now != null && now.connected ? 12 : 0));

        Calls.Headsets h = tab.headsets();
        HeadsetRules.View v = HeadsetRules.of(h, tab.headsetProblem());
        if (h != null && h.allowed) {
            col.addView(new Ui.Toggle(act, "Ask before calling without the headset", "When your headset is not connected to the phone, a call "
                    + "would play on the phone itself. LinkMeter then asks whether to connect the headset first.", Cfg.callsHeadsetAsk(act),
                    (b, on) -> Cfg.setCallsHeadsetAsk(act, on)).view, Ui.lp(act, 12));
        }
        LinearLayout status = Ui.card(act, v.title.isEmpty() ? "Headset" : v.title, null);
        if (!v.line.isEmpty()) {
            TextView line = Ui.body(act, v.line);
            line.setTextColor(v.tone == HeadsetRules.Tone.GOOD ? Ui.GREEN : v.tone == HeadsetRules.Tone.WARN ? Ui.AMBER : Ui.FG);
            status.addView(line, Ui.lp(act, 6));
        }
        if (working) {
            status.addView(Ui.text(act, workingText, 14, Ui.ACCENT, true), Ui.lp(act, 8));
        }
        for (HeadsetRules.Button b : v.buttons) {
            if (b.what == HeadsetRules.Do.PAIR && found != null) {
                continue; // already searching
            }
            status.addView(button(b), Ui.lp(act, 8));
        }
        col.addView(status, Ui.lp(act, 12));
        if (h != null && h.allowed) {
            col.addView(tabletCard(h), Ui.lp(act, 12));
        }

        if (h != null && !h.devices.isEmpty()) {
            LinearLayout list = Ui.card(act, "Paired with the phone", null);
            for (final Calls.Device d : h.devices) {
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8));
                IconButton icon = new IconButton(act, Icons.Kind.HEADSET, d.connected ? Ui.GREEN : Ui.DIM, 0, d.name, null);
                icon.setScale(0.6f);
                row.addView(icon, new LayoutParams(Ui.dp(act, 36), Ui.dp(act, 36)));
                LinearLayout words = new LinearLayout(act);
                words.setOrientation(VERTICAL);
                words.addView(Ui.text(act, d.name, 15, Ui.FG, true));
                boolean mine = d.addr.equalsIgnoreCase(h.chosen);
                words.addView(Ui.text(act, (d.connected ? "Connected" : "Not connected") + (mine ? " · your headset" : ""), 12.5f,
                        d.connected ? Ui.GREEN : Ui.DIM, false));
                LayoutParams wp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                wp.leftMargin = Ui.dp(act, 10);
                row.addView(words, wp);
                row.setClickable(true);
                row.setOnClickListener(x -> work(d.connected ? "Choosing " + d.name + "…" : "Connecting " + d.name + "…", rc -> {
                    Calls.Result r = rc.headsetChoose(d.addr);
                    return r.ok && !d.connected ? rc.headsetConnect(d.addr) : r;
                }, true));
                list.addView(row);
            }
            col.addView(list, Ui.lp(act, 12));
        }

        if (found != null) {
            LinearLayout f = Ui.card(act, found.scanning ? "Looking for headsets…" : "Headsets found", null);
            if (!found.note.isEmpty()) {
                f.addView(Ui.hint(act, found.note), Ui.lp(act, 2));
            }
            for (final Calls.Device d : found.devices) {
                f.addView(Ui.primary(act, "Pair " + d.name, x -> pair(d)), Ui.lp(act, 8));
            }
            if (!found.scanning) {
                f.addView(Ui.button(act, "Look again", x -> scan()), Ui.lp(act, 8));
            }
            col.addView(f, Ui.lp(act, 12));
        }
        if (h != null && h.allowed && found == null && !v.has(HeadsetRules.Do.PAIR)) {
            col.addView(Ui.quiet(act, "Pair another headset", x -> press(HeadsetRules.Do.PAIR)), Ui.lp(act, 12));
        }
    }

    View view() {
        return this;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        TabletHeadset.of(act).add(tabletChanged); // the tablet's own Bluetooth: heard only while this screen is up
    }

    @Override
    protected void onDetachedFromWindow() {
        TabletHeadset.of(act).remove(tabletChanged);
        super.onDetachedFromWindow();
    }
}
