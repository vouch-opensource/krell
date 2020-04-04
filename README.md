# krell

Stand alone, low configuration ClojureScript tooling for React Native. All features
are provided as a simple set of defaults over the standard ClojureScript compiler.

## Why Krell?

There are two other relatively mature ClojureScript tools for React Native,
re-natal and shadow-cljs. re-natal is oldest and likely most widely used. 
Unfortunately, re-natal is Leiningen-centric and has some historical design 
decisions that lead to a stateful CLI API. Still, it is a direct inspiration 
for Krell, and re-natal solved many tricky edge-cases early on. shadow-cljs 
also offers react-native integration, but provides that as part of a full 
featured package rather than an a la carte tool.

Krell fills the gap by providing a stand alone tool with few dependencies. It
does only one thing - extend the standard ClojureScript compiler to make
developing React Native simpler (and easier).

It does not attempt to paper over the React Native CLI workflow at all. Krell
just provides minimal sensible defaults for development and production and allows
you to switch between these defaults via the familiar ClojureScript `:optimizations`
settings.

With little configuration beyond typical ClojureScript web config, you get 
a React Native REPL-based workflow. Because Krell uses the ClojureScript compiler 
to index `node_modules`, you can idiomatically require anything you've installed 
via `yarn` or `npm` just like any ClojureScript library.

If you specify a higher optimization setting like `:simple` or `:advanced`,
Krell generates a single file output without the REPL dependencies.

## Quick Start

Create a new React Native project:

```
npx react-native init --version 0.61.5 AwesomeApp
```

React Native `0.62.X` does not currently work due to a dependency conflict
with Flipper and `react-native-tcp-socket` around the native `CocoaAsyncSocket`
dependency.

## REPL Dependencies

If you intend to use the REPL you must install the following into your React
Native project:

* [react-native-tcp-socket](https://github.com/Rapsssito/react-native-tcp-socket)
* [react-native-zeroconf](https://github.com/balthazar/react-native-zeroconf)
* [react-native-device-info](https://github.com/react-native-community/react-native-device-info#getdeviceid)

The reinstall your pods:

```
rm -rf Pods Podfile.lock; pod install
``` 

## REPL

```
clj -m krell.main -co build.edn -r
```

## Production Build

```

clj -m krell.main -O simple -co build.edn -c
```

## Examples

### Plain React Native

### Reagent
