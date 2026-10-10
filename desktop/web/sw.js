// SendViaLocalNet: always prefer the newest UI from the Windows host.
// Old PWA caches are removed on activation so iPhone/Safari cannot stay stuck
// on an obsolete interface after the Windows app is updated.
self.addEventListener('install', event => {
  self.skipWaiting();
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.map(key => caches.delete(key))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', event => {
  if (event.request.method !== 'GET') return;
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin) return;

  // Do not cache UI, manifest, service worker or API responses.
  event.respondWith(fetch(event.request, { cache: 'no-store' }));
});
