# Handoff: turning on Google Translate for Live chat

Google is a built relay translator that production does not use yet. Production order is `LIVE_TRANSLATE_PROVIDERS=azure`. Its key is already in the box's `dp-relay-shared/.env`. Read this before adding `google` to the order.

Rules: [attribution](https://docs.cloud.google.com/translate/attribution), [markup](https://docs.cloud.google.com/translate/markup). Google's [service terms](https://cloud.google.com/terms/service-terms) (section 25) make both binding.

## Done in code

- Google's unaltered badge (`white-regular@3x` from its attribution pack) replaces the "Translated" label while a Google line is visible: in game (`client/live/LiveChatTranslatedPill`) and on the Live page, linked to translate.google.com.
- In game it draws after the rest of the HUD on an opaque backing, and stays on screen at the top edge. The dev-only HUD still draws over it when chat is very tall (cause unknown; release builds hide that HUD). An actual overlap with Jade's panel, for example in a narrow window, has not been seen, so check that before relying on it.
- Live page lines Google translated carry `lang="<to>-x-mtfrom-<from>"`.
- The required warranty disclaimer and a Cloud Translation link are on the AI policy page (`public/dungeontrain/ai-policy/`) and the wiki.

## Before enabling

Brennan's account steps:

1. **Billing cut-off.** The project's daily character quota is 40,000 by choice, so it does not guarantee staying inside the free 500,000 a month (up to about US$15 a month over). The relay's 450,000 monthly stop is the main protection. Budgets alone only alert, so set one up to turn billing off: create a budget on the billing account, connect it to a Pub/Sub topic, and deploy a Cloud Function that disables billing for `dt-live-translate` when the cost exceeds the budget. Follow [Google's guide](https://cloud.google.com/billing/docs/how-to/disable-billing-with-notifications).
2. **Lock the key.** In APIs & Services, Credentials, set the existing key's application restriction to IP addresses and enter only the relay's IP. Reuse this key: never generate a new one.

Release steps:

3. **Ship the mod with the badge** and let players update. Older mods show plain "Translated" with no Google credit, so a gap remains until they do. Decide whether to accept or wait it out.
4. **Listings.** Add a line to the CurseForge and Modrinth descriptions saying Google Translate powers translations, with a link to the Cloud Translation site.

## Enabling

Set `LIVE_TRANSLATE_PROVIDERS=azure,google` in `dp-relay-shared/.env`, then restart the relay. Check `/health` under `liveChat.translate.providers`. The order is a setting, so going back is the same edit.

## Open risks (accepted by Brennan, 2026-11-07)

- **Chat-wide badge.** The rule says the graphic must be "adjacent any translation results", and Google's chat sample puts it under each message. A label above the chat is a reasonable reading that only Google could confirm. If challenged, move to one badge per Google line.
- **Keyless browser fallback.** `public/dungeontrain/live/live-translate.js` falls back to Google's keyless `translate.googleapis.com` endpoint, which is not the Cloud Translation API and has no published terms for this use that we found. Browsers also use it to translate for other readers via sharing (dp-relay #1040), and those readers see plain "Translated". Kept as is by choice. It's flagged, not cleared.
- **Violent content rule.** Google bars its marks on interfaces that "promote violence". Game combat is read as outside that. It's a judgement call.
