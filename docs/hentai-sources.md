# Hentai category

The `HENTAI` category now contains six native sources:

| Source | Adapter | Stream extraction |
|---|---|---|
| HentaiHaven | dedicated | series page → latest episode → `<source src="…m3u8">` |
| HentaiCloud | dedicated | `<source src>` MP4 variants (`hd` / `iphone`) |
| Iwara | dedicated | `api.iwara.tv` → `fileUrl` → rendition JSON |
| Iwara.ai | Iwara subclass | same API with the `iwara.ai` origin |
| OHentai | `GenericHtmlSource` | `/detail.php` page → `<source>`/media links |
| Hanime1 | `GenericHtmlSource` | `/watch` page → `<source>`/media links |

`HentaiTvSource` and `HentaiMamaSource` still exist but are not registered:
their players need JavaScript and no WebView-backed source is registered.
`PendingSources` records `hstream.moe` (card selector unresolved) and
`hanime.tv` (Cloudflare challenge) as known gaps.

The embedded WebView screen remains for future sources: it enables JavaScript,
blocks ad/tracker hosts, and allows navigation only to the exact player host or
one of its subdomains. None of the currently registered sources use it.

See [categories.md](categories.md) for the full category model and
[the README](../README.md#sources) for all 23 registered sources.
