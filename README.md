# krell

Stand alone, low configuration [ClojureScript](https://clojurescript.org)
tooling for [React Native](https://reactnative.dev). All features are provided
as a simple set of defaults over the standard ClojureScript compiler.

## Releases & Dependency Information

```
io.vouch/krell {:mvn/version "0.5.4"}
```

## Why Krell?

There are two other relatively mature ClojureScript tools for React Native,
[re-natal](https://github.com/drapanjanas/re-natal) and
[shadow-cljs](https://github.com/thheller/shadow-cljs). re-natal is oldest and
likely most widely used. Unfortunately, re-natal is Leiningen-centric and has
some historical design decisions that lead to a stateful CLI API. Still, it is a
direct inspiration for Krell, and re-natal solved many tricky edge-cases early
on. shadow-cljs also offers react-native integration, but provides that as part
of a full featured package rather than an à la carte tool.

Krell fills the gap by providing a standalone tool with few dependencies. It
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
[react-native-tcp-socket](https://www.npmjs.com/package/react-native-tcp-socket).

## Install REPL Dependencies

Install the REPL support dependencies:

```
clj -M -m cljs.main --install-deps
```

If this fails try:

```
clj -M -m cljs.main -co "{:deps-cmd \"yarn\"}" --install-deps
```

Switch into the `ios` directory of your project and run `pod install`.

## REPL

First build your project:

```
clj -M -m krell.main -v -co build.edn -c
```

Run your React Native project and verify that it works. 

You can start a REPL and connect to the running app at any time:

```
clj -M -m krell.main -co build.edn -r
```

You can of course combine these steps just as with plain `cljs.main`.

```
clj -M -m krell.main -co build.edn -c -r
```

## Assets & Arbitrary Node Library Requires

Krell supports arbitrary `js/require` of assets and Node.js dependencies. The
asset support is intended to align with React Native's own documentation - you
must use static relative paths. The additional support for Node.js dependencies
is useful when transitioning away from re-natal to Krell.

It's important to note that adding a new asset or new Node library currently 
requires recompiling the project.

Other than that there are no other limitations. The handling of `js/require` is
implemented as an analyzer pass so if you want to create macros to generate
asset requires, that will work.

## Extending Krell

Integrating with tools like [Expo](https://expo.dev), and [Storybook.js](https://storybook.js.org)
require providing a custom `index.js` file. This can be provided by one of two
ways - either via the `--index-js` command line flag or by providing a file
called `krell_index.js` on the classpath. The later method opens the door to
extending Krell to other targets without requiring any configuration or wrapping
of Krell at all.

## Multiple App Instance Development

Sometimes it's necessary run multiple instances of the same application during 
development - i.e. a chat/messaging application. When Krell starts up it logs
the device ID, for example:

```
Krell sez howdy, Device ID: iPhone12,3
```

You can use this device ID to assign a specific Krell port in your `app.json`,
for example:

```
{
  "name": "MyAwesomeProject",
  "displayName": "My Awesome Project",
  "krellPortMap": {
    "iPhone12,3": 5002
  }
}
```

Then you can connect to it like so:

```
clj -M -m krell.main -co build.edn -p 5002 -r
```

*You should only have **one** hotloading REPL*. You can disable hot reloading 
like so:

```
clj -M -m krell.main -co build.edn -p 5002 -rc false -r
```

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
