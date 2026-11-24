// Stand-in for @minecraft/server-ui: forms record their controls and
// resolve show() with whatever the test queued via __mock.respond().
const queued = [];
export const __mock = {
  shown: [],
  respond(r) { queued.push(r); },
};

class Form {
  constructor() { this.controls = []; }
  _add(kind, ...args) { this.controls.push([kind, ...args]); return this; }
  title(t) { this.titleText = t; return this; }
  body(t) { this.bodyText = t; return this; }
  show(player) {
    __mock.shown.push({ form: this, player });
    return Promise.resolve(queued.shift() ?? { canceled: true });
  }
}

export class ActionFormData extends Form {
  button(...a) { return this._add("button", ...a); }
}
export class MessageFormData extends Form {
  button1(t) { return this._add("button1", t); }
  button2(t) { return this._add("button2", t); }
}
export class ModalFormData extends Form {
  textField(...a) { return this._add("textField", ...a); }
  toggle(...a) { return this._add("toggle", ...a); }
  slider(...a) { return this._add("slider", ...a); }
  dropdown(...a) { return this._add("dropdown", ...a); }
  label(...a) { return this._add("label", ...a); }
  header(...a) { return this._add("header", ...a); }
  divider() { return this._add("divider"); }
  submitButton(t) { return this._add("submitButton", t); }
}
