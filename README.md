# krell

Stand alone, low configuration ClojureScript tooling for React Native. All features
are provided as a simple set of defaults over the standard ClojureScript compiler.

## Why Krell?

There are two other relatively mature ClojureScript tools for React Native:
re-natal, shadow-cljs. re-natal is the oldest and reflects that by being
oriented around Leiningen and other historical design decisions that give it
a less functional API. shadow-cljs also offers react-native integration,
but provides that as part of a full featured package rather than as an a la carte 
tool.

Krell fills the gap by providing a stand alone tool with few dependencies. It
does only one thing - extend the standard ClojureScript compiler to make
developing React Native simpler (and easier). 

## REPL

```
clj -m krell.main -co build.edn -r
```

## Production Build

```

clj -m krell.main -O simple -co build.edn -c
```
