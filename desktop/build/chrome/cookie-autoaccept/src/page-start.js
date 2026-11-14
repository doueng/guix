// Main frame, document_start: tells the service worker a new page began, before any of its subframes exist,
// so the tab's report is fresh and keeps what those frames report.
chrome.runtime.sendMessage({ type: 'pageStart' }).catch(() => {});
