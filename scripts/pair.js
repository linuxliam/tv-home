// One-off pairing: node scripts/pair.js <tv-ip>
// Shows a code on the TV; type it here. Saves the client certificate to .pairing/cert.json.
const fs = require('fs');
const path = require('path');
const readline = require('readline');
const { AndroidRemote } = require('androidtv-remote');

const host = process.argv[2] || '10.120.0.107';
const certPath = path.join(__dirname, '..', '.pairing', 'cert.json');
const rl = readline.createInterface({ input: process.stdin, output: process.stdout });

const remote = new AndroidRemote(host, {
  pairing_port: 6467,
  remote_port: 6466,
  name: 'homebridge-hisense',
  cert: fs.existsSync(certPath) ? JSON.parse(fs.readFileSync(certPath, 'utf8')) : {},
});

remote.on('secret', () => {
  rl.question('Code shown on the TV: ', (code) => remote.sendCode(code.trim().toUpperCase()));
});

remote.on('ready', () => {
  fs.mkdirSync(path.dirname(certPath), { recursive: true });
  fs.writeFileSync(certPath, JSON.stringify(remote.getCertificate()), { mode: 0o600 });
  console.log('Paired. Certificate saved to', certPath);
  remote.stop();
  process.exit(0);
});

remote.on('error', (e) => console.error('Error:', e));
remote.start();
