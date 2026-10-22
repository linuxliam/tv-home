const EventEmitter = require('events');
const fs = require('fs');
const net = require('net');
const dgram = require('dgram');
const { AndroidRemote, RemoteKeyCode, RemoteDirection } = require('androidtv-remote');

const PROBE_INTERVAL_MS = 10000;

/**
 * Wraps androidtv-remote: loads/saves the pairing certificate, handles first-time
 * pairing via a code file, tracks reachability with a TCP probe, and exposes TV state.
 * Emits 'state' whenever any of reachable/powered/volume/muted/app changes.
 */
class TvClient extends EventEmitter {
  constructor({ host, mac, certPath, codePath, log }) {
    super();
    this.host = host;
    this.mac = mac;
    this.certPath = certPath;
    this.codePath = codePath;
    this.log = log;
    this.state = { reachable: false, powered: false, volume: 0, muted: false, app: null };
    this.remote = null;
    this.ready = false;
  }

  start() {
    this._connect();
    this._probe();
    this.probeTimer = setInterval(() => this._probe(), PROBE_INTERVAL_MS);
  }

  stop() {
    clearInterval(this.probeTimer);
    clearInterval(this.codeTimer);
    if (this.remote) this.remote.stop();
  }

  _connect() {
    let cert = {};
    try {
      cert = JSON.parse(fs.readFileSync(this.certPath, 'utf8'));
    } catch {
      this.log.warn('Not paired yet; starting pairing.');
    }
    const remote = new AndroidRemote(this.host, { pairing_port: 6467, remote_port: 6466, name: 'homebridge-hisense', cert });
    this.remote = remote;

    remote.on('secret', () => {
      this.log.warn(`The TV is showing a pairing code. Write it into ${this.codePath} (e.g. echo ABC123 > "${this.codePath}").`);
      this.codeTimer = setInterval(() => {
        try {
          const code = fs.readFileSync(this.codePath, 'utf8').trim().toUpperCase();
          if (!code) return;
          clearInterval(this.codeTimer);
          fs.unlinkSync(this.codePath);
          remote.sendCode(code);
        } catch { /* file not there yet */ }
      }, 1000);
    });

    remote.on('ready', () => {
      this.ready = true;
      try {
        fs.writeFileSync(this.certPath, JSON.stringify(remote.getCertificate()), { mode: 0o600 });
      } catch (e) {
        this.log.error('Could not save pairing certificate:', e.message);
      }
      this.log.info('Connected to TV');
    });
    remote.on('powered', (powered) => this._set({ powered, reachable: true }));
    remote.on('volume', (v) => this._set({ volume: v.level, muted: v.muted }));
    remote.on('current_app', (app) => this._set({ app }));
    remote.on('unpaired', () => this.log.error(`TV rejected our certificate. Delete ${this.certPath} and restart to pair again.`));
    remote.on('error', (e) => this.log.debug('Remote error:', JSON.stringify(e)));

    remote.start().catch((e) => this.log.debug('Initial connect failed (TV off?):', String(e)));
  }

  _probe() {
    const s = net.connect({ host: this.host, port: 6466, timeout: 3000 });
    const done = (reachable) => {
      s.destroy();
      this._set(reachable ? { reachable } : { reachable, powered: false });
    };
    s.once('connect', () => done(true));
    s.once('timeout', () => done(false));
    s.once('error', () => done(false));
  }

  _set(patch) {
    const changed = Object.keys(patch).some((k) => this.state[k] !== patch[k]);
    if (!changed) return;
    Object.assign(this.state, patch);
    this.emit('state', this.state);
  }

  sendKey(name) {
    const code = RemoteKeyCode[`KEYCODE_${name}`];
    if (code === undefined) return this.log.error(`Unknown key ${name}`);
    if (!this.ready) return this.log.warn(`TV not connected; dropped key ${name}`);
    this.remote.sendKey(code, RemoteDirection.SHORT);
  }

  launch(link) {
    if (!this.ready) return this.log.warn('TV not connected; dropped app launch');
    this.remote.sendAppLink(link);
  }

  setPower(on) {
    if (on === this.state.powered) return;
    if (on) {
      if (this.state.reachable && this.ready) this.sendKey('WAKEUP');
      else this._wake();
    } else {
      this.sendKey('SLEEP');
    }
  }

  _wake() {
    if (!this.mac) return this.log.warn('TV unreachable and no "mac" configured, cannot wake it');
    const macBytes = Buffer.from(this.mac.replace(/[:-]/g, ''), 'hex');
    const packet = Buffer.concat([Buffer.alloc(6, 0xff), ...Array(16).fill(macBytes)]);
    const sock = dgram.createSocket('udp4');
    sock.bind(() => {
      sock.setBroadcast(true);
      sock.send(packet, 9, '255.255.255.255', () => sock.close());
    });
  }
}

module.exports = { TvClient };
