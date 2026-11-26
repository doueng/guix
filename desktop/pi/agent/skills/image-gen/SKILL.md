---
name: image-gen
description: "Generate, edit, inpaint and vary images with bbh.image on the person's ChatGPT subscription, saved with their prompt and parameters and shown in the phone's Docs, view and Emacs. Use when the person asks for an image, illustration, icon, mockup or photo edit, or when a doc or explainer would be clearer with a generated picture. Not for screenshots (shot) or diagrams you can draw as inline SVG."
---

# Images with bbh.image

`bbh.image` calls the `image_generation` tool of the Codex Responses API under
Pi's `openai-codex` login, so images spend ChatGPT plan quota, not money. The
tool's model is the backend's (gpt-image-2-codex in Nov 2026). A call takes
20 to 40 seconds per image; batches run three at a time.

## Make, edit, vary

In a Worker REPL:

```clojure
(image! "A watercolor lighthouse at dusk" :thread "T12" :quality "low")
(image! "Add a full moon and make it night" :images ["/path/01.png"] :thread "T12")
(image! "Replace the sky only" :images ["/path/01.png"] :mask "/path/mask.png")
((requiring-resolve 'bbh.image/variations!) "/path/01.png" :n 3 :prompt "warmer light")
((requiring-resolve 'bbh.image/spend))   ; today's batches, images and estimate
```

From a shell or Emacs: `bbh image --quality low --thread T12 a watercolor lighthouse`
(`bbh image --help` lists the flags).

Options: `:n` (1), `:size` (`auto` `1024x1024` `1536x1024` `1024x1536`),
`:quality` (`low` `medium` `high` `auto`; default `medium`), `:format` (`png`
`jpeg` `webp`), `:background` (`auto` `opaque` `transparent`), `:images` (paths
to edit or combine), `:mask` (a PNG of the first image's size whose transparent
pixels mark where to edit), `:thread` and `:confirmed`.

The backend picks the exact pixels for a shape (square came back 1254x1254),
so the sidecar's `:actual-size` is the truth; bbh.image repeats `:size` in the
prompt because the tool setting alone was ignored.

The mask is a hint, not a stencil: in a Nov 2026 test a sky-only mask still
redrew the lighthouse below it. When the rest must stay pixel-exact, say so in
the prompt and check the result with `look`. The mask must match the source
image's pixel size.

It returns `{:dir :images :gallery :org :estimate-usd}`, plus `:errors` when
some images failed. The folder is `~/Pictures/bbh-images/DATE/TIME-slug/`
(`BBH_IMAGE_DIR` moves it): `01.png` with `01.edn` holding the prompt,
parameters, revised prompt and actual size; `gallery.html`; `index.org`.

## Show the person

- Phone: pass `:thread`; `gallery.html` is then in the Docs list, images inline.
- Desktop: `view /path/gallery.html` opens it in Chrome.
- Emacs: `index.org` shows the images inline in a GUI Emacs built with image
  support. The current build is terminal-only without it, so from Emacs point
  the person at `gallery.html` and `view`.

Give full paths, and look at the result yourself with `look` before you report it.

## Cost and confirmation

Every batch has an API-equivalent estimate (low $0.011, medium $0.042, high
or auto $0.167 per image, 1.5x for the wide and tall sizes). The subscription
does not bill it, but images use the same plan limits as Codex work, so:

- Start with one image at `low` or `medium`; go `high` only for a final pick.
- A batch over $0.50 or over 4 images throws `:needs-confirmation`. Then
  `super-ask!` with the count, quality and estimate, and retry with
  `:confirmed true` only after the person agrees. Never set it on your own.
- A failed Pi login says so; ask the person to log in to OpenAI Codex in Pi.

The Router has no execution authority: it starts a Worker for image work.

## Tests

`desktop/build/bbh/test/bbh/image_test.clj` runs against a fake provider
(`:provider` takes a function from request to `{:bytes :revised-prompt :size}`).
Never call the live provider from tests.
