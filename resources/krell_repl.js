import { Platform } from "react-native";
import TcpSocket from "react-native-tcp-socket";
import { npmDeps } from "./npm_deps.js";
import { krellNpmDeps } from "./krell_npm_deps.js";
import { assets } from "./krell_assets.js";
import AsyncStorage from "@react-native-community/async-storage";

var CONNECTED = false;
var RECONNECT_INTERVAL = 3000;

var SERVER_IP = "$KRELL_SERVER_IP";
var SERVER_PORT = $KRELL_SERVER_PORT;

const IS_ANDROID = Platform.OS === "android";
const KRELL_VERBOSE = $KRELL_VERBOSE;

const evaluate = eval;
var libLoadListeners = {};
var reloadListeners = [];
var cacheInvalidateListeners = [];
var pendingLoads_ = [];

// =============================================================================
// Caching Support

var MEM_CACHE = new Map();
const CACHE_PREFIX = "krell_cache:";

const isKrellKey = (x) => {
    return x.indexOf(CACHE_PREFIX) === 0;
};

const krellPrefix = (x) => {
    if(isKrellKey(x)) {
        return x;
    } else if (typeof x === "string") {
        return CACHE_PREFIX + x;
    } else {
        throw Error("Invalid cache key: " + x);
    }
};

const removePrefix = (x) => {
    if(isKrellKey(x)) {
        return x.substring(CACHE_PREFIX.length);
    } else {
        return x;
    }
};

const cacheInit = async () => {
    let keys = await AsyncStorage.getAllKeys();
    try {
        for (let key of keys) {
            if (isKrellKey(key)) {
                let path = removePrefix(key);
                MEM_CACHE.set(path, JSON.parse(await AsyncStorage.getItem(key)));
            }
        }
    } catch(e) {
        console.error(e);
        return false;
    }
    KRELL_CACHE.ready = true;
    return true;
};

const cachePut = (path, entry) => {
    MEM_CACHE.set(path, entry);
    if (KRELL_VERBOSE) console.log("CACHE PUT:", path, entry.modified);
    AsyncStorage.setItem(krellPrefix(path), JSON.stringify(entry))
        .catch((err) => {
            console.log("Could not cache path:", path, "error:", err);
        });
};

const cacheGet = (path) => {
    return MEM_CACHE.get(path);
};

global.CLOSURE_BASE_PATH = "$CLOSURE_BASE_PATH";

function toPath(path) {
    return CLOSURE_BASE_PATH.replace("goog/", "") + path;
}

// these things are going to change during a single REPL session
const excludes = {
    [toPath("goog/base.js")]: true,
    [toPath("goog/deps.js")]: true,
    [toPath("cljs_deps.js")]: true,
    [toPath("krell_repl_deps.js")]: true
};

const cacheClear = async (all) => {
    let cacheKeys = MEM_CACHE.keys();
    MEM_CACHE = new Map();
    if (all === true) {
        AsyncStorage.clear();
    } else {
        for (let cacheKey of cacheKeys) {
            if(!excludes[cacheKey]) {
                if (KRELL_VERBOSE) console.log("CACHE DELETE:", cacheKey);
                AsyncStorage.removeItem(krellPrefix(cacheKey));
            }
        }
    }
};

const cacheHas = (path) => {
    return MEM_CACHE.has(path);
};

const cacheIsStale = (index) => {
    if(typeof index == "string") {
        return index === "invalidate";
    } else {
        for (let path of MEM_CACHE.keys()) {
            let entry = MEM_CACHE.get(path);
            if (entry) {
                //console.log(path, entry.modified, index[path]);
                if (entry.modified < index[path]) {
                    return true;
                }
            } else {
                return true;
            }
        }
        return false;
    }
};

const onKrellCacheInvalidate = (cb) => {
    cacheInvalidateListeners.push(cb);
};

const notifyCacheInvalidationListeners = () => {
    cacheInvalidateListeners.forEach((cb) => cb());
};

global.KRELL_CACHE = {
    mem: () => MEM_CACHE,
    init: cacheInit,
    put: cachePut,
    get: cacheGet,
    has: cacheHas,
    clear: cacheClear,
    ready: false
};

cacheInit();

// =============================================================================
// REPL Server

var loadFileSocket = null;
var scheduled = null;

const loadFile = (socket, path) => {
    let req = {
            type: "load-file",
            value: path
        },
        payload = JSON.stringify(req)+"\0";
    if (!IS_ANDROID) {
        socket.write(payload);
    } else {
        pendingLoads_.push(req);
        if(scheduled) clearTimeout(scheduled);
        scheduled = setTimeout(() => flushLoads_(loadFileSocket), 250);
    }
};

const exists_ = (obj, xs) => {
    if(typeof xs == "string") {
        xs = xs.split(".");
    }
    if(xs.length >= 1) {
        let key = xs[0],
            hasKey = obj.hasOwnProperty(key);
        if (xs.length === 1) {
            return hasKey;
        } else {
            if(hasKey) {
                return exists_(obj[key], xs.slice(1));
            }
        }
        return false;
    } else {
        return false;
    }
};

const flushLoads_ = (socket) => {
    let allPending = pendingLoads_.map(function(req) {
        return JSON.stringify(req)+"\0";
    });
    socket.write(allPending.join(""));
    pendingLoads_ = [];
};

global.CLOSURE_NO_DEPS = true;

// NOTE: CLOSURE_LOAD_FILE_SYNC not needed as ClojureScript now transpiles
// offending goog.module files that would need runtime transpiler support
global.CLOSURE_IMPORT_SCRIPT = function(path, optContents) {
    if (optContents) {
        try {
            evaluate(optContents);
        } catch (e) {
            console.error("Could not eval ", path, ":", e);
            throw e;
        }
        return true;
    } else {
        var cached = KRELL_CACHE.get(path);
        if(cached) {
            try {
                evaluate(cached.source);
            } catch (e) {
                console.error("Could not load ", path, " from cache:", e);
                throw e;
            }
            notifyListeners({value: path});
        } else {
            loadFile(loadFileSocket, path, optContents);
        }
        return true;
    }
};

global.require = function(x) {
    return npmDeps[x] || krellNpmDeps[x] || assets[x];
};

const notifyListeners = (request) => {
    let path = request.value,
        xs = libLoadListeners[path] || [];

    xs.forEach(function (x) {
        x();
    });
};

const notifyReloadListeners = () => {
    reloadListeners.forEach(function(x) {
        x();
    });
};

const onSourceLoad = (path, cb) => {
    if(typeof libLoadListeners[path] === "undefined") {
        libLoadListeners[path] = [];
    }
    libLoadListeners[path].push(cb);
};

const onKrellReload = (cb) => {
    reloadListeners.push(cb);
};

const errString = (err) => {
    if(typeof cljs !== "undefined") {
        if(typeof cljs.repl !== "undefined") {
            cljs.repl.error__GT_str(err)
        } else {
            return err.toString();
        }
    } else {
        return err.toString();
    }
};

const handleMessage = (socket, data) => {
    var req = null,
        err = null,
        ret = null;

    data = data.replace(/\0/g, "");

    try {
        let msg = JSON.parse(data);
        req = msg.request;
        if(msg.form) {
            ret = evaluate(msg.form);
        }
        // output forwarding
        if (typeof ret == "function") {
            if (ret.name === "cljs$user$redirect_output") {
                ret(socket);
            }
        }
        if(req) {
            if(req.type === "load-file") {
                let path = req.value;
                KRELL_CACHE.put(path, {
                    source: msg.form,
                    path: path,
                    modified: req.modified
                });
                if (typeof goog !== "undefined") {
                    goog.debugLoader_.written_[req.value] = true;
                }
            }
            if(req.type === "cache-compare") {
                if(cacheIsStale(req.index)) {
                    KRELL_CACHE.clear();
                    notifyCacheInvalidationListeners();
                    console.log("Krell cache is stale");
                } else {
                    console.log("Krell cache is up-to-date")
                }
            }
        }
    } catch (e) {
        console.error(e);
        if(req && req.type === "load-file") {
            console.log("Failed to load file:", req.value);
        }
        err = e;
    }

    if (err) {
        socket.write(
            JSON.stringify({
                type: "result",
                status: "exception",
                value: errString(err)
            })+"\0"
        );
    } else {
        if (ret !== undefined && ret !== null) {
            socket.write(
                JSON.stringify({
                    type: "result",
                    status: "success",
                    value: ret.toString(),
                })+"\0"
            );
        } else {
            socket.write(
                JSON.stringify({
                    type: "result",
                    status: "success",
                    value: null,
                })+"\0"
            );
        }

        if (req) {
            notifyListeners(req);
            // log individual file reloads
            if(req.reload) {
                console.log(req);
            }
            // all changed files are done reloading, refresh
            if(req.type === "reload") {
                notifyReloadListeners();
            }
        }
    }
};

const initSocket = (socket) => {
    var buffer = "";
    // it doesn't matter which socket we use for loads
    loadFileSocket = socket;

    socket.on("data", data => {
        if (data[data.length - 1] !== 0) {
            buffer += data;
        } else {
            data = buffer + data;
            buffer = "";
            handleMessage(socket, data);
        }
    });

    socket.on("error", error => {
        socket.destroy();
        console.log("An error occurred with client socket:", error);
    });

    socket.on("close", error => {
        socket.destroy();
        if (CONNECTED) {
            console.log("Closed connection with", socket.address());
        }
        CONNECTED = false;
        setTimeout(tryConnection, RECONNECT_INTERVAL);
    });
};

// Loop to connect from client to server
const tryConnection = () => {
    if(!CONNECTED) {
        var socket = TcpSocket.createConnection({
            host: SERVER_IP,
            port: SERVER_PORT,
            tls: false,
        }, function(address) {
            console.log("Connected to Krell REPL Server");
            CONNECTED = true;
        });
        initSocket(socket);
    } else {
        setTimeout(tryConnection, RECONNECT_INTERVAL);
    }
};

tryConnection();

module.exports = {
    evaluate: evaluate,
    onKrellReload: onKrellReload,
    onKrellCacheInvalidate: onKrellCacheInvalidate,
    onSourceLoad: onSourceLoad
};
