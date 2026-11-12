# Translate texts to Mexican Spanish (the user's request, 2026-11-12) — progress notes

STATUS: WORKING (branch ccr-6685b0bb-kms4dg; main = 714e62e = v0.2.171 at the start)

THE USER'S WORDS: "Add a feature in text feature to translate my texting to Mexican Spanish so that my Mexican friend can understand it. Make sure
it uses good translation logic. I tried to use Gboard translate but it doesn't work with linkmeter. Diagnose that or build a translator just as good"

DIAGNOSIS (Gboard): Google's own Gboard page lists "Translate as you type in the keyboard" as "Not supported on Android Go devices" (goo.gle/gboard).
The tablet is Android 10 GO edition (MT8168 Go). So Gboard translate does not exist on that tablet, in any app. LinkMeter's Texts box is a plain
EditText (TYPE_CLASS_TEXT|MULTI_LINE|CAP_SENTENCES, no incognito / no-suggestions flags): nothing in LinkMeter blocks it. Texts only run over the
hotspot (RemoteRoutes has no /sms over Bluetooth), so the tablet has internet when texting.

ENGINE RESEARCH (real requests from the sandbox, 2026-11-12):
- Google free web endpoint translate.googleapis.com/translate_a/single?client=gtx: same engine as Gboard; Spanish is neutral/LatAm but kept Spain
  words (patatas, camarero, conducir, alquilar, coche, nevera, bolígrafo, piscina, apartamento, zapatillas, movil, chaqueta), left "tbh", dropped
  accents on lowercase input; after ~5 requests from the sandbox's shared IP it answered 302 -> google.com/sorry (CAPTCHA). Long GET failed: use POST.
- MyMemory api.mymemory.translated.net/get?q=..&langpair=en|es-MX&onlyprivate=1&mt=1: official free API (5,000 chars/day anonymous, q max 500
  bytes); with onlyprivate=1 it returns ONLY its neural MT (without it, public TM matches win: "Take it" -> "Aceptar"). es-MX output is truly
  Mexican: papas, mesero, rentar, celular está descargado, departamento, tenis nuevos, alberca, chamarra, "tbh" -> "La verdad". Keeps emoji, URLs,
  phone numbers, times, line breaks. es-MX|en understands Mexican slang (qué onda güey, carne asada, ahorita, chambeando).
PLAN:
1. pure Translation (no Android): chunks <= 450 UTF-8 bytes at line/sentence/space boundaries (join == original), MyMemory URL + parser (quota,
   warning, status), Google POST body + parser + CAPTCHA detection, Mexican safety layer (coger->agarrar family, chaqueta->chamarra, zapatillas->
   tenis, conducir->manejar except "lead to", patata->papa, camarero->mesero, alquilar->rentar, coche->carro, móvil(noun)->celular, nevera->
   refrigerador, bolígrafo->pluma, piscina->alberca, apartamento->departamento, vale interjection), shorthand expansion for the Google fallback.
2. Translator (tablet glue): MyMemory first, Google if it fails; LinkLog line (never the words).
3. TextsLocal per-conversation switch; TextThread: menu switch with a first-time explanation, Send translates and shows the Spanish in the box
   with a strip (from your English, Undo, Send my English), second Send sends; long-press a received text -> Translate to English (inline).
4. tests: tools/test/TranslationTest.java (+ tools/test.sh), breakages, TextsPreviewTest scenarios with a pretend engine; pictures.
5. docs: docs/TRANSLATE.md, HANDOFF, USER_GUIDE, SAFETY (words leave the tablet only when you use Translate). Release.

DONE (2026-11-12, wip commits on the branch): Translation + TranslateFlow (TranslationTest 56,381 checks, in tools/test.sh), Translator,
TextsLocal.isTranslated, TextThread (menu entry + explanation, strip, Undo / Send my English, resend untouched, Translate to English on a
received text), 7 TextsPreviewTest scenarios pass, pictures texts-translate-* LOOKED AT, tools/mutate_translate.py: 36 pure caught (two tests
added, one dead aparcar guard removed); glue 11 RUNNING (log scratchpad/tr-glue.log). Docs: docs/TRANSLATE.md, SAFETY.md section 10,
USER_GUIDE, APP_REFERENCE, HANDOFF bullet.
NEXT: glue result; tools/test.sh; assembleRelease; whole preview; closing plain checkpoint (release); run + release; note: commit with the number.
