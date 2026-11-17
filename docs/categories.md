# Categories

`Category` has four values. A source belongs to exactly one of them, and both
the source chips and the feed are scoped to the selected value:

| Category | Sources | Gate |
|---|---|---|
| `LIVE` | Pornhub, XVideos, Eporner, SpankBang, xHamster, XNXX, 141tube, Avgle | no extra gate |
| `JAV` | MISSAV, KissJAV, 85po, JAV-NONI, JAVSB, TOKYO Motion, MRDOUGA, JAV-FUN | 18+ gate |
| `HENTAI` | HentaiHaven, HentaiCloud, Iwara, Iwara.ai, OHentai, Hanime1 | 18+ gate |
| `ARCHIVE` | Internet Archive | no extra gate |

`HomeScreen` is keyed by category in `VaultApp`, so switching tabs resets the
page, tag selection and source filter instead of carrying them into a feed where
that source does not exist. `Category.requiresGate` drives the confirmation
dialog; the acceptance is stored once in `calc_state`.

Tag chips appear for `HENTAI` and `JAV`. They are a single selection; the
canonical label is sent as the `SEARCH` query to every source in the active
category, which is the one listing mode all registered adapters implement.

Follows and watch affinity are still owned by `sources.primary()` (Pornhub),
which remains first in `Container.sources`. Other categories therefore get
ranking and diversity, but not follow affinity.
