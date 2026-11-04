const path = require('path');
const { TvClient } = require('./tv-client');

const PLUGIN_NAME = 'homebridge-hisense-googletv';
const PLATFORM_NAME = 'HisenseGoogleTV';

const DEFAULT_INPUTS = [
  { name: 'Live TV', key: 'TV', package: 'com.vt.tv.tvcenter' },
  { name: 'HDMI 1', key: 'TV_INPUT_HDMI_1' },
  { name: 'HDMI 2', key: 'TV_INPUT_HDMI_2' },
  { name: 'HDMI 3', key: 'TV_INPUT_HDMI_3' },
  { name: 'YouTube', link: 'https://www.youtube.com', package: 'com.google.android.youtube.tv' },
  { name: 'Netflix', link: 'https://www.netflix.com', package: 'com.netflix.ninja' },
  { name: 'Prime Video', link: 'https://app.primevideo.com', package: 'com.amazon.amazonvideo.livingroom' },
  { name: 'Disney+', link: 'https://www.disneyplus.com', package: 'com.disney.disneyplus' },
];

// HAP RemoteKey characteristic values -> Android key names
const REMOTE_KEYS = {
  0: 'MEDIA_REWIND',
  1: 'MEDIA_FAST_FORWARD',
  2: 'MEDIA_NEXT',
  3: 'MEDIA_PREVIOUS',
  4: 'DPAD_UP',
  5: 'DPAD_DOWN',
  6: 'DPAD_LEFT',
  7: 'DPAD_RIGHT',
  8: 'DPAD_CENTER',
  9: 'BACK',
  10: 'HOME',
  11: 'MEDIA_PLAY_PAUSE',
  15: 'INFO',
};

class HisenseTvPlatform {
  constructor(log, config, api) {
    this.log = log;
    this.config = config;
    this.api = api;
    if (!config || !config.host) {
      log.error('No "host" configured; the plugin is idle.');
      return;
    }
    api.on('didFinishLaunching', () => this.setup());
    api.on('shutdown', () => this.tv && this.tv.stop());
  }

  setup() {
    const { Service, Characteristic, Categories } = this.api.hap;
    const storage = this.api.user.storagePath();
    const name = this.config.name || 'TV';
    this.inputs = this.config.inputs && this.config.inputs.length ? this.config.inputs : DEFAULT_INPUTS;

    this.tv = new TvClient({
      host: this.config.host,
      mac: this.config.mac,
      certPath: path.join(storage, 'hisense-tv-cert.json'),
      codePath: path.join(storage, 'hisense-tv-pair-code'),
      log: this.log,
    });

    const uuid = this.api.hap.uuid.generate(`${PLUGIN_NAME}:${this.config.host}`);
    const accessory = new this.api.platformAccessory(name, uuid, Categories.TELEVISION);

    accessory.getService(Service.AccessoryInformation)
      .setCharacteristic(Characteristic.Manufacturer, 'Hisense')
      .setCharacteristic(Characteristic.Model, 'Google TV')
      .setCharacteristic(Characteristic.SerialNumber, this.config.host);

    const tvService = accessory.addService(Service.Television, name);
    tvService
      .setCharacteristic(Characteristic.ConfiguredName, name)
      .setCharacteristic(Characteristic.SleepDiscoveryMode, Characteristic.SleepDiscoveryMode.ALWAYS_DISCOVERABLE);

    tvService.getCharacteristic(Characteristic.Active)
      .onGet(() => (this.tv.state.powered ? 1 : 0))
      .onSet((v) => this.tv.setPower(v === 1));

    tvService.getCharacteristic(Characteristic.RemoteKey).onSet((v) => {
      const key = REMOTE_KEYS[v];
      if (key) this.tv.sendKey(key);
    });

    tvService.getCharacteristic(Characteristic.ActiveIdentifier)
      .onGet(() => this.currentInputId())
      .onSet((id) => this.selectInput(id));

    this.inputs.forEach((input, i) => {
      const src = accessory.addService(Service.InputSource, `input${i}`, input.name);
      src
        .setCharacteristic(Characteristic.Identifier, i + 1)
        .setCharacteristic(Characteristic.ConfiguredName, input.name)
        .setCharacteristic(Characteristic.IsConfigured, Characteristic.IsConfigured.CONFIGURED)
        .setCharacteristic(Characteristic.InputSourceType,
          input.link ? Characteristic.InputSourceType.APPLICATION : Characteristic.InputSourceType.HDMI)
        .setCharacteristic(Characteristic.CurrentVisibilityState, Characteristic.CurrentVisibilityState.SHOWN);
      tvService.addLinkedService(src);
    });

    const speaker = accessory.addService(Service.TelevisionSpeaker, `${name} Speaker`);
    speaker
      .setCharacteristic(Characteristic.Active, Characteristic.Active.ACTIVE)
      .setCharacteristic(Characteristic.VolumeControlType, Characteristic.VolumeControlType.RELATIVE);
    speaker.getCharacteristic(Characteristic.VolumeSelector)
      .onSet((v) => this.tv.sendKey(v === Characteristic.VolumeSelector.INCREMENT ? 'VOLUME_UP' : 'VOLUME_DOWN'));
    speaker.getCharacteristic(Characteristic.Mute)
      .onGet(() => this.tv.state.muted)
      .onSet((mute) => {
        if (mute !== this.tv.state.muted) this.tv.sendKey('VOLUME_MUTE');
      });
    tvService.addLinkedService(speaker);

    this.tv.on('state', (s) => {
      tvService.updateCharacteristic(Characteristic.Active, s.powered ? 1 : 0);
      tvService.updateCharacteristic(Characteristic.ActiveIdentifier, this.currentInputId());
      speaker.updateCharacteristic(Characteristic.Mute, s.muted);
    });

    // Television accessories must be published separately for the Home app remote to work.
    this.api.publishExternalAccessories(PLUGIN_NAME, [accessory]);
    this.tv.start();
  }

  currentInputId() {
    const idx = this.inputs.findIndex((i) => i.package && i.package === this.tv.state.app);
    return idx >= 0 ? idx + 1 : 1;
  }

  selectInput(id) {
    const input = this.inputs[id - 1];
    if (!input) return;
    this.log.info(`Switching to ${input.name}`);
    if (input.link) this.tv.launch(input.link);
    else if (input.key) this.tv.sendKey(input.key);
  }
}

module.exports = { HisenseTvPlatform, PLATFORM_NAME, PLUGIN_NAME };
