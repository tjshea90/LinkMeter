# Translating texts into Spanish (Mexico)

**The user's request (2026-11-12):** "Add a feature in text feature to translate my texting to Mexican Spanish so that my Mexican friend can
understand it. Make sure it uses good translation logic. I tried to use Gboard translate but it doesn't work with linkmeter. Diagnose that
or build a translator just as good."

**Status:** built and tested offline (rules, the screens against a pretend translator, deliberate breakages), **not tried on the tablet**.
Notes of the work: `tools/progress.sh --show translate`.

## Why Gboard's translation did not work: the tablet is an Android Go device

Google's own Gboard page lists "Translate as you type in the keyboard" and adds that it is **not supported on Android Go devices**
(https://goo.gle/gboard; low-RAM Go devices get the lighter Gboard Go). The tablet runs Android 10 **Go edition** (MediaTek MT8168 Go), so
Gboard's translation does not exist there, in any app; nothing LinkMeter does can switch it on. LinkMeter's message box is a plain Android
text box (`TextThread`: `TYPE_CLASS_TEXT | MULTI_LINE | CAP_SENTENCES`, no incognito flag, no "no suggestions" flag): it blocks nothing.
**Confirmed by the user (2026-11-12): "Gboard translate isn't working anywhere"** on the tablet, not only in LinkMeter.
(Not the cause either: the internet. Texts only work over the phone's hotspot, `RemoteRoutes` has no `/sms` route over Bluetooth, so the
tablet is on the internet whenever Texts work.)

## The services, tried with real requests (2026-11-12)

- **MyMemory** (`api.mymemory.translated.net`, by Translated; free, no account: 5,000 characters a day, at most 500 bytes per question).
  Asked for `en|es-MX` it writes Spanish **as spoken in Mexico**: "Vamos por jugo y papas", "El mesero fue grosero", "Voy a rentar un auto",
  "Mi celular está descargado", "Mi departamento es pequeño", "Compré tenis nuevos", "Los niños están en la alberca", "Trae una chamarra,
  hace frío", and "u coming 2nite? idk if i can make it tbh" became "¿Vienes esta noche? La verdad, no sé si voy a poder". It keeps emoji,
  links, phone numbers, times and line breaks. The other way (`es-MX|en`) it understands Mexican slang: "Qué onda güey, ¿vas a venir a la
  carne asada mañana? Ahorita no puedo, estoy chambeando" became "What's up, dude? Are you coming to the barbecue tomorrow? I can't right
  now, I'm working." **One trap:** by default a sentence that somebody once translated by hand wins over the machine translation, and those
  are often program buttons ("Take it" came back as "Aceptar", "Can you drive?" as the formal "¿Puede conducir?"). Asked with
  `onlyprivate=1` it answers with its machine translation only ("Tómalo", "¿Sabes conducir?"). LinkMeter always asks that way.
- **Google Translate** (`translate.googleapis.com/translate_a/single?client=gtx`, the engine of Gboard's translation; free, no account, no
  daily allowance). Neutral Spanish with words from Spain: "patatas", "camarero", "conducir", "alquilar", "coche", "nevera", "bolígrafo",
  "piscina", "apartamento", "zapatillas", "móvil", "chaqueta"; it left "tbh" as it was and made "2nite" "at night"; lowercase input
  sometimes came back without accents. A long text in the address was refused (the text goes in the body of a POST now). After about five
  questions from this sandbox's shared address it answered with a redirect to its picture puzzle (`google.com/sorry`) instead of a
  translation: a phone's own connection is far less likely to see that, but it is handled ("busy").

So: **MyMemory first, Google when MyMemory is used up, busy or out of reach**, and a safety net over the Spanish of either.

## The logic (`Translation`, pure, `tools/test/TranslationTest.java`)

- **Pieces.** A text longer than 450 bytes is cut at a line break, else after the end of a sentence, else between words, else between
  letters (never inside an emoji); the pieces put back together give the text exactly. Spaces and line breaks around a piece stay where they
  were, pieces without letters (emoji, numbers) are not sent at all.
- **Answers.** MyMemory: its "used up for today" warning is recognised (never taken for a translation), a refused question says why, HTML
  entities are written out. Google: the sentences are joined, the picture puzzle and HTTP 429 are "busy". A "busy" MyMemory makes the
  tablet ask Google; when both fail, the reason that helps most is said ("The tablet is not on the internet ... is the phone's mobile data
  on?", or "Both free translation services are busy or used up for today").
- **Texting shorthand** is written out for Google only (MyMemory reads it well): u, r, k, idk, tbh, ngl, imo, btw, lmk, wyd, hbu, brb,
  nvm, omw, 2nite, tmrw, rn, b4, gr8, bday, ppl, bc/cuz, w/, w/o and more; whole words only, capitals kept, a letter in quotes is a letter.
- **Spanish made right for Mexico** (`toMexican`, applied to every translation into Spanish, whichever service wrote it): the words that
  read wrong there are changed, only as whole words and only where they can mean nothing else.
  - Two would embarrass: **coger** (to take, to catch) is vulgar in Mexico: every form becomes **agarrar** (coge, cojo, cogí, cogiendo,
    cógelo -> agarra, agarro, agarré, agarrando, agárralo, with the accent the new word needs), except "cojo"/"coja" meaning limping ("está
    cojo"); **chaqueta** (jacket) is slang there: **chamarra**. And **zapatillas** are a woman's heels there: **tenis**.
  - The rest only sound foreign: conducir -> manejar (not "conduce a la playa", which means leads to), alquilar -> rentar, aparcar ->
    estacionar, enfadar -> enojar (all their forms), patata -> papa, camarero -> mesero, coche -> carro, piscina -> alberca, apartamento ->
    departamento, zumo -> jugo, melocotón -> durazno, aparcamiento -> estacionamiento, guay -> padre, nevera -> refrigerador, bolígrafo ->
    pluma, tarta -> pastel, gambas -> camarones, ordenador -> computadora, gafas -> lentes, móvil -> celular (only as the phone: "mi
    móvil", "teléfono móvil", never "datos móviles"), "vale" as OK -> "ok" (never "vale la pena", "¿cuánto vale?").
  - A noun whose gender changes takes its article and its adjective along: "la nevera está vacía" -> "el refrigerador está vacío", "de la
    nevera" -> "del refrigerador", "unas zapatillas blancas" -> "unos tenis blancos", "un bolígrafo nuevo" -> "una pluma nueva".
  - Mexico has no vosotros: vosotros, vuestro and their verb forms become the ustedes ones.
  - Spanish that is already Mexican is left exactly as it was, and the result never changes when it is run again.

## On the screen (`TextThread`, `TranslateFlow` pure)

- A conversation's menu (the three dots) > **Translate my texts to Spanish (Mexico)**. It first says what it does and what leaves the tablet
  (see below), and is kept per conversation (`TextsLocal`, encrypted with the tablet's other choices). The box then says "Write in
  English" and a line above it says the conversation is translated.
- **Send** translates your English and puts the Spanish **in the box for you to check** (the line above names the service and shows your
  English); **Send again sends it**, also with a word you changed. **Undo** gives your English back; **Send my English** sends it as you
  wrote it. Nothing is ever sent that you have not seen. A failure says why, sends nothing, and offers Send my English. A text that failed
  to go is sent again as it was (never translated a second time). Quick replies go through the same check; emoji replies need none.
- A text you **received**: press and hold it > **Translate to English**: the English appears under it ("English, by MyMemory: ..."),
  kept in memory only.
- **Translate my texts** is switched off with the same menu ("Stop translating to Spanish").

## What leaves the tablet

Only when you press Send in a translated conversation, or Translate to English on a received text: the words of that one text, over
HTTPS, to MyMemory (or Google Translate). No name, no number, no other message. The Texts connection log (Texts menu > Connection details)
shows "translate (MyMemory)" with the time and the result, never the words. `docs/SAFETY.md`, section 10, says the same.

## Tests

- `tools/test/TranslationTest.java` (in `tools/test.sh`): pieces (3,000 random texts back exactly, never too long, never inside an emoji),
  the services' real answers (MyMemory's translation, its used-up warning and its refusal, Google's sentences and its picture puzzle),
  shorthand, the Mexican rules (every rule, the real Google outputs above, Spanish that is already Mexican left alone, 4,000 random texts
  stable and with no form of coger left), and `TranslateFlow` (what Send does, Undo, Send my English, a late answer, a changed box, a
  failure, switching off). About 56,000 checks.
- `TextsPreviewTest` (tools/uipreview, Robolectric, a pretend translator `Translator.testEngine`): the request with a service's Spain
  Spanish made Mexican before you see it, Undo and Send my English, a failure that sends nothing, the switch per conversation and kept, a
  received text into English, switched off nothing is translated, a resent Spanish text not translated again. Pictures:
  `texts-translate-ask`, `texts-translate-check`, `texts-translate-sent`, `texts-translate-failed`, `texts-translate-received`.
- `python3 tools/mutate_translate.py`: 36 breakages of the rules and 11 of the screens, each must be caught (commit first: the screens'
  are broken in a git worktree).

## Not known until the tablet tries

Whether the tablet reaches both services through the phone's hotspot (it should: plain HTTPS); how often MyMemory's free 5,000 characters
a day run out (then Google answers); whether Google ever shows the phone's connection its picture puzzle.

## Not built (ask first)

- Translating your friend's texts into English **automatically** as they arrive (now: press and hold, one by one). Each one would use the
  free allowance, so it was left to a press.
- A translation by a large language model (it writes the most natural Mexican texting style) would need an API key of the user's and costs
  a little per text.
