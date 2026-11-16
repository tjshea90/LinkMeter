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

    // ------------------------------------------------------------------ drawing

    private Button button(HeadsetRules.Button b) {
        final HeadsetRules.Do what = b.what;
        Button v = b.main ? Ui.primary(act, b.label, x -> press(what)) : Ui.button(act, b.label, x -> press(what));
        v.setEnabled(!working);
        return v;
    }

    private void render() {
        col.removeAllViews();
        LinearLayout why = Ui.card(act, "The headset connects to the phone", null);
        why.addView(Ui.hint(act, "The phone makes the call, so your headset is paired with the phone, not with this tablet: the voice "
                + "goes straight from the headset to the phone, and keeps going if this tablet sleeps. This screen only helps you connect it. "
                + "Keep the phone within a few metres of where you sit."), Ui.lp(act, 4));
        col.addView(why);

        Calls.Headsets h = tab.headsets();
        HeadsetRules.View v = HeadsetRules.of(h, tab.headsetProblem());
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
}
