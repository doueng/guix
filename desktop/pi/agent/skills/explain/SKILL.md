---
name: explain
description: Explain a tool, system or idea from the ground up, in plain English, as a Tufte-style HTML page. Use when the person asks to explain something, asks how X works or what X really is, wants it from the ground up or bottom up, or says they never quite got it.
---

# Explain from the ground up

The goal is one feeling: after one read, "wow, I really get it this time". Every choice serves that. The model is /home/engstrand/guix/docs/guix-explained.html

## Before writing

1. Find the **bottom**: the one fact the reader already knows that the whole topic grows from ("a program is a file that needs other files"). Step 1 starts there.
2. List the ideas the topic needs and order them so each uses only the ones before it. That order is the table of contents. Aim for 8 to 16 steps; a step that needs subheadings is two steps.
3. Pick the **running example** from the person's own setup: their repo, their machine, a bug they hit. Run the commands now, also when rewriting an old page, and keep the real output. Date what will change (counts, versions, what is running). Facts you cannot check on this machine, check in the source and name it.

## Writing

- **Short sentences, plain words.** One idea per sentence. Say "folder", not "directory hierarchy".
- **No word before its meaning.** A new term appears in bold at the moment it is defined, after the reader has seen why it is needed. Never use it earlier, not even in a heading.
- **Why before what.** Show the problem the next idea solves, then the idea.
- **Real commands, real output.** Each step that can be seen on the machine shows a command that only looks, and its output trimmed to the lines that matter.
- **One `So:` line closes each step**: the takeaway in one sentence a reader could repeat.
- **Cut** anything that does not move the reader toward getting it: history, option lists, edge cases, hedges. An aside that is true and useful but breaks the flow goes in a sidenote, never a footnote.
- **End with the whole picture**: every step again in a paragraph or one labelled diagram, then where the person's own setup touches each part.

## The page (Tufte)

Copy assets/tufte.html next to the doc and fill it in; keep its `<style>` unchanged. It is self-contained (no network, no script) and collapses the margin into the text on a phone.

- **Words, numbers and pictures together.** A diagram sits beside the sentence that uses it, labelled directly on the marks. A number appears in the sentence, with its unit and source.
- **Data-ink.** Every line, box and colour carries information. No shadows, gradients, icons, legends, borders around text, or decoration. Red is the only accent, for the one thing to look at.
- **Draw SVGs at phone size**: a viewBox about 360 wide with text at 12 or more, so labels stay readable when the page is 390px wide. Quote attribute values: `stroke-width=2/>` leaves the element open and hides the rest of the picture.
- **Small multiples** when showing change: the same small picture repeated, one thing different in each.
- **Sidenotes** (numbered) for asides and sources, **margin notes** (unnumbered) for glosses and figure labels.
- Tables are light: a rule under the header, numbers right-aligned.
- **The look is the Financial Times'** on a Tufte layout: its paper #fff1e5, text #33302e, teal links #0d7680, claret accent #990f3d and hairlines #ccc1b7, read from ft.com's own CSS (Nov 2026); every text colour passes WCAG AA on the paper (#33302e 11.8:1, grey #66605c 5.6:1, teal 4.8:1, claret 7.6:1). In SVG attributes, where CSS variables do not work, use those hex values. The FT's fonts, Financier Display and Metric, are commercial Klim fonts, so the template embeds free ones (SIL OFL) as WOFF2 Latin subsets, about 90 KB in all. The title is Newsreader's display cut: Production Type drew it for on-screen news, and it has Financier Display's sharp, high-contrast newspaper serif. Body text is Source Serif 4, a serif rather than a sans like the FT's Metric, because the layout leans on true italics, small caps and oldstyle figures, which Source Serif has and Newsreader lacks, and long prose reads better in a serif. Georgia is the fallback. Leading is tight, 1.35 for body text and 1.25 in the margin. The font line is long; copy the file rather than retyping it.

Put the file in /home/engstrand/guix/docs as NAME-explained.html, or in /tmp for a scratch answer. Set the description meta and, from bbh, the bbh-doc meta to the thread so the phone lists it. Name it in the reply by its full path.

## Self-review before finishing

Read the page top to bottom as the person would, once, and fix every no:

1. Does step 1 start from something the person already knows?
2. Does each step use only words and ideas from earlier steps? Search for every bold term and check nothing, headings included, uses it before.
3. Does every step end with a `So:` line, and do the `So:` lines alone tell the story?
4. Is the running example the person's own setup, and was every command run here, with its real output?
5. Is every sentence short and plain? Rewrite any that needs a second read.
6. Did you cut each paragraph, picture and note that does not add to getting it?
7. Does each picture show something the words alone could not, labelled in place, with no chartjunk?
8. Are asides in sidenotes and none in the main text?
9. Screenshot it at 390px and 1280px wide. Do notes sit inline on the phone and beside their line on the laptop, does nothing overflow, is every picture whole, is the text easy to read?
10. Does the closing picture let someone who read only it get the gist?
