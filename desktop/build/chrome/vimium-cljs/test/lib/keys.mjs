// Turns a Vim-style key sequence ("gg", "/needle<enter>", "<c-o>", "<esc>") into
// CDP Input.dispatchKeyEvent parameters, so the page gets trusted key events.
const named = {
  esc: ['Escape', 'Escape', 27], enter: ['Enter', 'Enter', 13, '\r'], bs: ['Backspace', 'Backspace', 8],
  tab: ['Tab', 'Tab', 9], space: [' ', 'Space', 32, ' '], left: ['ArrowLeft', 'ArrowLeft', 37],
  up: ['ArrowUp', 'ArrowUp', 38], right: ['ArrowRight', 'ArrowRight', 39], down: ['ArrowDown', 'ArrowDown', 40],
};
const punct = {
  '/': ['Slash', 191], '?': ['Slash', 191, 1], '`': ['Backquote', 192], '~': ['Backquote', 192, 1],
  '[': ['BracketLeft', 219], ']': ['BracketRight', 221], '-': ['Minus', 189], '=': ['Equal', 187],
  ';': ['Semicolon', 186], ':': ['Semicolon', 186, 1], "'": ['Quote', 222], ',': ['Comma', 188],
  '.': ['Period', 190], '<': ['Comma', 188, 1], '>': ['Period', 190, 1], '$': ['Digit4', 52, 1],
  '^': ['Digit6', 54, 1], '*': ['Digit8', 56, 1], '#': ['Digit3', 51, 1], ' ': ['Space', 32],
};

function charKey(c) {
  if (/^[a-z]$/.test(c)) return { key: c, code: 'Key' + c.toUpperCase(), vk: c.toUpperCase().charCodeAt(0), shift: false };
  if (/^[A-Z]$/.test(c)) return { key: c, code: 'Key' + c, vk: c.charCodeAt(0), shift: true };
  if (/^[0-9]$/.test(c)) return { key: c, code: 'Digit' + c, vk: c.charCodeAt(0), shift: false };
  const p = punct[c];
  if (p) return { key: c, code: p[0], vk: p[1], shift: !!p[2] };
  return { key: c, code: '', vk: 0, shift: false };
}

/** [[downParams, upParams], ...] for SEQ. */
export function keyEvents(seq) {
  const out = [];
  for (let i = 0; i < seq.length; ) {
    let mods = 0, k;
    const m = seq[i] === '<' && seq.slice(i).match(/^<((?:[acms]-)*)([a-z]+|.)>/);
    if (m && (m[1] || m[2].length > 1)) {
      for (const mod of m[1].split('-').filter(Boolean)) mods |= { a: 1, c: 2, m: 4, s: 8 }[mod];
      if (named[m[2]]) {
        const [key, code, vk, text] = named[m[2]];
        k = { key, code, vk, text: mods & 7 ? undefined : text };
      } else {
        const c = charKey(m[2]);
        if (c.shift) mods |= 8;
        k = { key: c.key, code: c.code, vk: c.vk, text: mods & 7 ? undefined : c.key };
      }
      i += m[0].length;
    } else {
      const c = charKey(seq[i]);
      if (c.shift) mods |= 8;
      k = { key: c.key, code: c.code, vk: c.vk, text: c.key };
      i += 1;
    }
    const base = { key: k.key, code: k.code, windowsVirtualKeyCode: k.vk, nativeVirtualKeyCode: k.vk, modifiers: mods };
    out.push([
      { ...base, type: k.text ? 'keyDown' : 'rawKeyDown', ...(k.text ? { text: k.text, unmodifiedText: k.text } : {}) },
      { ...base, type: 'keyUp' },
    ]);
  }
  return out;
}
