// CEF's windowless renderer denies native pointer lock. This bridge is enabled only by the player's capture key.
(() => {
  if (window.__screenCapture) { window.__screenCapture.report(); return; }
  let enabled = false, locked = null;
  let x = 0, y = 0, buttons = 0;
  const report = value => window.cefQuery?.({request: 'screen-capture:' + value});
  const changed = () => {
    report(locked ? 'locked' : 'unlocked');
    document.dispatchEvent(new Event('pointerlockchange'));
  };
  const unlock = () => {
    if (!locked) return;
    locked = null;
    changed();
  };
  const request = Element.prototype.requestPointerLock;
  Element.prototype.requestPointerLock = function(options) {
    if (!enabled) return request.call(this, options);
    if (!this.isConnected || (!locked && !navigator.userActivation.isActive)) {
      document.dispatchEvent(new Event('pointerlockerror'));
      return Promise.reject(new DOMException('Click the captured page to lock the pointer.', 'NotAllowedError'));
    }
    locked = this;
    // Match the native API's asynchronous change notification.
    queueMicrotask(changed);
    return Promise.resolve();
  };
  const exit = Document.prototype.exitPointerLock;
  Document.prototype.exitPointerLock = function() {
    if (locked) unlock(); else exit.call(this);
  };
  const element = Object.getOwnPropertyDescriptor(Document.prototype, 'pointerLockElement');
  Object.defineProperty(Document.prototype, 'pointerLockElement', {
    configurable: true, get() { return locked || element.get.call(this); }
  });
  // Keep button events native. Relative movement goes to the locked element even beyond the page's edges.
  for (const type of ['mousemove', 'mousedown', 'mouseup']) document.addEventListener(type, event => {
    if (!event.isTrusted) return;
    x = event.clientX; y = event.clientY; buttons = event.buttons;
    if (locked && type === 'mousemove') event.stopImmediatePropagation();
  }, true);
  window.addEventListener('blur', unlock);
  window.__screenCapture = {
    report() { report(locked ? 'locked' : 'ready'); },
    enable(value) { enabled = value; if (!enabled) unlock(); },
    move(dx, dy) {
      if (!enabled || !locked) return;
      if (!locked.isConnected) { unlock(); return; }
      const init = {bubbles: true, cancelable: true, composed: true, clientX: x, clientY: y,
        movementX: dx, movementY: dy, buttons};
      locked.dispatchEvent(new PointerEvent('pointermove', {...init, pointerId: 1, pointerType: 'mouse', isPrimary: true}));
      locked.dispatchEvent(new MouseEvent('mousemove', init));
    }
  };
  report('ready');
})();
