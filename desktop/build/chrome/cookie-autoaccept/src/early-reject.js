// Loaded first by the early content script when the mode is reject (the worker registers the file for the mode).
globalThis.cookieAutoaccept = Object.assign(globalThis.cookieAutoaccept || {}, { earlyMode: 'reject' });
