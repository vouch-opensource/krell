# krell

Stand alone, low configuration [ClojureScript](https://clojurescript.org)
tooling for [React Native](https://reactnative.dev). All features are provided
as a simple set of defaults over the standard ClojureScript compiler.

## Why Krell?

There are two other relatively mature ClojureScript tools for React Native,
[re-natal](https://github.com/drapanjanas/re-natal) and
[shadow-cljs](https://github.com/thheller/shadow-cljs). re-natal is oldest and
likely most widely used. Unfortunately, re-natal is Leiningen-centric and has
some historical design decisions that lead to a stateful CLI API. Still, it is a
direct inspiration for Krell, and re-natal solved many tricky edge-cases early
on. shadow-cljs also offers react-native integration, but provides that as part
of a full featured package rather than an a la carte tool.

Krell fills the gap by providing a stand alone tool with few dependencies. It
does only one thing - extend the standard ClojureScript compiler to make
developing React Native simpler (and easier).

It does not attempt to paper over the React Native CLI workflow at all. Krell
just provides minimal sensible defaults for development and production and
allows you to switch between these defaults via the familiar ClojureScript
`:optimizations` settings.

With little configuration beyond typical ClojureScript web config, you get a
React Native REPL-based workflow. Because Krell uses the ClojureScript compiler
to index `node_modules`, you can idiomatically require anything you've installed
via `yarn` or `npm` just like any ClojureScript library.

If you specify a higher optimization setting like `:simple` or `:advanced`,
Krell generates a single file output without the REPL dependencies.

## Requirements

* Node >= 10.16.0
* Latest release of ClojureScript
* Java SDK 8+

Using React Native >= 0.60 is highly recommended as autolinking simplifies usage
greatly. If you must use an older version of React Native refer to the
documentation for the REPL support dependencies:
[react-native-tcp-socket](https://www.npmjs.com/package/react-native-tcp-socket),
[react-native-zeroconf](https://github.com/balthazar/react-native-zeroconf).

## Install REPL Dependencies

Install the REPL support dependencies:

```
clj -m cljs.main --install-deps
```

Switch into the `ios` directory of your project and run `pod install`.

Ensure that you have the following permissions set in your `AndroidManifest.xml`:

```
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
```

## REPL

First build your project:

```
clj -m krell.main -v -co build.edn -c
```

Start a REPL:

```
clj -m krell.main -co build.edn -r
```

You can of course combine these steps just as with plain `cljs.main`:

```
clj -m krell.main -co build.edn -c -r
```

## Assets & Arbitrary Node Library Requires

Krell supports arbitrary `js/require` of assets and Node.js dependencies. The
asset support is intended to align with React Native's own documentation - you
must use static relative paths. The additional support for Node.js dependencies
is useful when transitioning away from re-natal to Krell.

It's important to note that adding a new asset or new Node library requires
restarting the REPL.

Other than that there are no other limitations. The handling of `js/require` is
implemented as an analyzer pass so if you want to create macros to generate
asset requires that will work.

## Examples

See the [Reagent example tutorial](https://github.com/vouchio/krell/wiki/Reagent-Tutorial) 
in the wiki.

## Tooling Integration

[See the wiki](https://github.com/vouch-opensource/krell/wiki/Tooling-Integration---Emacs%2C-Cursive%2C-etc.).

## Contributing

Currently Krell is only taking bug reports. If you find a bug or would like
to see an enhancement that aligns with the following design principles 
please file a Github issue!

#### Design Principles

* No ClojureScript React library integration templating. The documentation 
  should make it pretty clear how to integrate with any particular ClojureScript 
  React library.

* Basic React Native only, we're not interested in Expo or any other similar
  tooling

## License ##

    Copyright (c) Vouch, Inc. All rights reserved. The use and
    distribution terms for this software are covered by the Eclipse
    Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
    which can be found in the file epl-v10.html at the root of this
    distribution. By using this software in any fashion, you are
    agreeing to be bound by the terms of this license. You must
    not remove this notice, or any other, from this software.
