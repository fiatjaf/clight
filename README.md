<a href="https://nbd.wtf"><img align="right" height="196" src="https://user-images.githubusercontent.com/1653275/194609043-0add674b-dd40-41ed-986c-ab4a2e053092.png" /></a>

poncho
======

_provider of credit-based channels for lightning nodes_

![](https://i.pinimg.com/originals/63/6b/46/636b46410c8e0166bb6d8fe20dbe23f5.jpg)

This is an **early alpha** software that turns your CLN node into a [hosted channels](https://sbw.app/posts/scaling-ln-with-hosted-channels/) provider.

## Installation

### Using the prebuilt binary

Grab a binary from the [Releases page](https://github.com/fiatjaf/poncho/releases), call `chmod +x` on it so it is executable, then put it inside your CLN plugin directory (`~/.lightning/plugins/`) -- or start `lightningd` with `--plugin <path-to-poncho>`.

This is entirely statically-linked and has no dependencies.

### Building the dynamically-linked library from source

Install [`libuv`](https://github.com/libuv/libuv) and [`libsecp256k1`](https://github.com/bitcoin-core/secp256k1). `libuv` can be installed from your default OS package manager, but `libsecp256k1` likely not, since it needs to be compiled with the extra modules (why these things aren't built by default is beyond my wildest imagination), call `./autogen.sh` then `./configure --enable-module-recovery --enable-module-schnorrsig` and finally `make` and `sudo make install`.

Here are two commands for downloading and building both (you may need all the things from the default C toolchain):

```
git clone https://github.com/libuv/libuv && cd libuv && ./autogen.sh && ./configure && make && make install
git clone https://github.com/bitcoin-core/secp256k1 && cd secp256k1 && ./autogen.sh && ./configure --enable-module-schnorrsig --enable-module-recovery && make && make install
```

After that, head over to the `poncho/` directory and call `sbt nativeLink`. After the build is successful binary will be at `target/scala-3.2.0/poncho-out`.

### Building the statically-linked binary

You'll need [podman](https://podman.io) (or Docker should be similar, but I don't know anything about this). So we'll build a container image that will compile libuv and libsecp256k1 then produce a poncho binary executable that is standalone, independent, and statically linked and you don't have to touch Java ever in your life again.

```
podman build . -t poncho-builder
podman run --rm -it -v "$(pwd)":'/poncho' poncho-builder
```

The result will be a binary that doesn't require Java to run. It will be under `target/scala-3.2.0/poncho-out` (yes, it will appear magically there because your poncho source directory was mounted inside the container). Put that in your `lightningd` plugins directory as above.

To delete the image later you can use:

```
podman image rm poncho-builder
```

## Operation

`poncho` requires your `hsm_secret` to _not_ be encrypted. If it is encrypted it won't work, at least for now.

It doesn't require any manual input to work, you just start it and it will automatically be listening for hosted channel requests from clients. The only time when you'll have to do anything manually is when there is a conflict between host and client, which shouldn't happen very often, but sometimes it happens -- just like when a normal Lightning channel is force-closed because the peers couldn't agree on something.

The CLN plugin provides these RPC methods:

- `hc-list`: lists all hosted channels.
- `hc-channel <peerid>`: shows detailed information about the channel for the given peer.
- `hc-override <peerid> <msatoshi>`: if the channel for this peer is in an error state, proposes overriding it to a new state in which the _local_ balance is the given.
- `hc-resize <peerid> <msatoshi>`: prepares a channel to accept a resize proposal to be sent by this peer -- up to the given amount (as the total channel capacity).
- `add-hc-secret <secret>`: adds a one-time secret for when `"requireSecret"` is true.
- `remove-hc-secret <secret>`: the opposite of the above.
- `parse-lcss <peerid> <last_cross_signed_state_hex>`: if a client manually shares a channel state with you this method can be used to decode it.

### Configuration

No configuration is needed for a quick test, but you can write a file at `$LIGHTNING_DIR/bitcoin/poncho/config.json` with the following properties, all optional. All amounts are in millisatoshis. The defaults are given below:

```json
{
  "cltvExpiryDelta": "143",
  "feeBase": 1000, // these fee parameters work the same as in normal channels, they will be used in the route hint appended to invoices from clients
  "feeProportionalMillionths": 1000,
  "maxHtlcValueInFlightMsat": 100000000, // this and the two settings below can be used to make the process of manual debugging errored HCs less problematic, prevent spam and resource usage
  "htlcMinimumMsat": 1000,
  "maxAcceptedHtlcs": 12,
  "channelCapacityMsat": 100000000, // this controls the size of all new hosted channels
  "initialClientBalanceMsat": 0, // keep this at zero unless you know what you're doing

  "contactURL": "", // this will be shown by wallets
  "logoFile": "", // this will be shown by wallets
  "hexColor": "#ffffff",

  "isDev": true, // controls log level and where are logs displayed

  "requireSecret": false, // setting this to true will make it so only clients with this secret can get hosted channels
  "permanentSecrets": [], // you can specify static secrets here that can be used by clients when "requireSecret" is true
  "disablePreimageChecking": true // setting this to false will make it so poncho will fetch and parse all bitcoin blocks
}
```

The branding information won't be used unless contact URL and logo file are set. The logo should be a PNG file also placed under `$LIGHTNING_DIR/bitcoin/poncho/` and specified as a relative path.

Setting `disablePreimageChecking` to `true` will drastically decrease resource consumption since it won't fetch and parse all Bitcoin blocks. It is recommended if you are offering hosted channels only to trusted people or yourself. If you are a public entity offering channels to random people it is not recommended since it opens up the possibility of _reputational_ damage against you, see [this part of the spec about the preimage checking](https://github.com/lightning/blips/blob/master/blip-0017.md#dealing-with-problems).

(Remember to remove the JSON comments in the file above otherwise it won't work.)

### Storage

When running on CLN, the plugin will create a folder at `$LIGHTNING_DIR/bitcoin/poncho/` and fill it with the following files:

```
~/.lightning/bitcoin/poncho/
├── channels
│   ├── 02dcfffc447b201bd83fa0a359f62acf33206f43c83aaeafc6082ac2e245f775a0.json
│   ├── 03ff908df11aef1b5fcf16c015ab28cab0bdb81d221a65065d7cf59dc59652af95.json
│   ├── 025964eb1b5cf60de72447b87eee7fe987a68e51d71a4bb2919a7ac06817b6b51e.json
│   └── 03362a35434a25651c05dbf3960f39a55084adada0dcac64f1744f4ab6fa6861e9.json
├── htlc-forwards.json
└── preimages.json
```

Each channel's `last_cross_signed_state` and other metadata is stored in a file under `channels/` named with the pubkey of the remote peer. The other two files are helpers that should be empty most of the time and only have values in them while payments are in flight (so they can be recovered after a restart).

## FAQ

- **What are hosted channels?**

Hosted Channels are virtual channels backed by trust, they work very much like Lightning Channels, and hosted channel users can interact seamlessly with the broader Lightning Network. Since they don't touch the Bitcoin chain they are basically free to create and do not require any collateral.

Here is a better (very short and high-level) explanation and description of its benefits from the inventor of Hosted Channels, Anton Kumaigorodskiy: https://archive.ph/HFXUK; and here is another explanation from Fanis Michalakis: https://fanismichalakis.fr/posts/what-are-hosted-channels/

The Hosted Channels protocol are also standardized as [bLIP-0017](https://github.com/lightning/blips/blob/master/blip-0017.md). Check that if you want to know more about the low-level details.

- **What can I do with `poncho` and hosted channels?**

You can:
  - offer fully private zero-cost channels from your node to your friends and family;
  - use [SBW](https://sbw.app/) to access your own node running at home over Tor through a hosted channel, in a segregated bucket;
  - use along with [cliché](https://github.com/fiatjaf/cliche) to power Lightning services without putting your entire node funds at risk.

- **How can I get a hosted channel from a node running `poncho`?**

For now, you can use https://sbw.app/ and paste a string containing `nodeid@host:port` or scan a QR code containing that same data, such as the QR codes from https://amboss.space/); or use https://github.com/fiatjaf/cliche and specify that same data using the `request-hc` command.

- **How can I have a whitelist of nodes that can get a channel from my `poncho`?**

To achieve this you must use the `"requireSecret"` option on your `config.json`. See [Configuration](#configuration) above. Then instead of giving your node address to your friends and family, you must conjure a [LUD-07 lnurl](https://github.com/fiatjaf/lnurl-rfc/blob/luds/07.md) and ask them to paste/scan that with their [SBW](https://sbw.app/). The secret must be specified in the lnurl response. Then the wallet will use it when invoking the hosted channel and it will work. You can use this flow to create any kind of arbitrary restriction to your hosted channels, including selling them, giving them dynamically or programmatically, or just restricting them to a fixed set of people to whom you give the secret lnurl.

- **What happens if I lose the channel states?**

If you ever lose a channel cross-signed state, the client, when reconnecting, will automatically send you their latest state and you will accept those -- as they would be signed by your public key --, trusting that they are indeed the latest states, and store them.

If a client sends a state with an invalid signature it won't be accepted. If you have the latest state and the client sends a state with a smaller update number it also won't be accepted.
