# krell

Stand alone, low configuration ClojureScript tooling for React Native. All features
are provided as a simple set of defaults over the standard ClojureScript compiler.

## Why Krell?

There are two other relatively mature ClojureScript tools for React Native:
re-natal, shadow-cljs. re-natal is the oldest and reflects that by being
oriented around Leiningen and as well as being encumbered by historical design 
decisions that lead to a less functional API. shadow-cljs also offers react-native 
integration, but provides that as part of a full featured package instead of 
an a la carte tool.

Krell fills the gap by providing a stand alone tool with few dependencies. It
does only one thing - extend the standard ClojureScript compiler to make
developing React Native simpler (and easier).

It does not attempt to paper over the React Native CLI workflow at all. Krell
just provides minimal sensible defaults for development and production and allows
you to switch between these defaults via the familiar ClojureScript `:optimizations`
settings.

With no configuration at all, you get a REPL based workflow. Because Krell uses
the ClojureScript compiler to index `node_modules`, you can idiomatically require 
anything you've installed via `yarn` or `npm` as a ClojureScript library.

If you specify a higher optimization setting like `:simple` or `:advanced`,
Krell generates a single file output without the REPL affordances.

## REPL Dependencies

If you intend to use the REPL you should install the following into your React
Native project:

* [react-native-tcp-socket](https://github.com/Rapsssito/react-native-tcp-socket)
* [react-native-zero-conf](https://github.com/balthazar/react-native-zeroconf)

## REPL

```
clj -m krell.main -co build.edn -r
```

## Production Build

```

clj -m krell.main -O simple -co build.edn -c
```
