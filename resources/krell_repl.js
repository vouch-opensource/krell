import { Platform } from "react-native";
import TcpSocket from "react-native-tcp-socket";
import { npmDeps } from "./npm_deps.js";
import { krellNpmDeps } from "./krell_npm_deps.js";
import { assets } from "./krell_assets.js";
import AsyncStorage from "@react-native-community/async-storage";

var CONNECTED = false;
var IPV4 = /^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$/;
var RECONNECT_INTERVAL = 3000;

var SERVER_IP = "$KRELL_SERVER_IP";
var SERVER_PORT = $KRELL_SERVER_PORT;

var IS_ANDROID = Platform.OS === "android";
var REPL_PORT = IS_ANDROID ? 5003 : 5002;

var evaluate = eval;
var libLoadListeners = {};
var reloadListeners = [];
var pendingLoads_ = [];

// =============================================================================
// Caching Support

var MEM_CACHE = new Map();
var CACHE_PREFIX = "krell_cache:";

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
        for (let key in keys) {
            if (isKrellKey(key)) {
                MEM_CACHE.set(removePrefix(key), JSON.parse(await AsyncStorage.getItem(key)));
            }
        }
    } catch(e) {
        console.error(e);
        return false;
    }
    return true;
};

const cachePut = (path, entry) => {
    MEM_CACHE.set(path, entry);
    AsyncStorage.setItem(krellPrefix(path), JSON.stringify(entry));
};

const cacheGet = (path) => {
    return MEM_CACHE.get(path);
};

const cacheClear = async (path) => {
    let cacheKeys = MEM_CACHE.keys();
    MEM_CACHE = new Map();
    for(let cacheKey in cacheKeys) {
        AsyncStorage.removeItem(cacheKeys);
    }
};

global.KRELL_CACHE = {
    mem: MEM_CACHE,
    init: cacheInit,
    put: cachePut,
    get: cacheGet,
    clear: cacheClear
};

cacheInit();

// =============================================================================
// REPL Server

var loadFileSocket = null;

var loadFile = function(socket, path) {
    var req = {
            type: "load-file",
            value: path
        },
        payload = JSON.stringify(req)+"\0";
    if (!IS_ANDROID) {
        socket.write(payload);
    } else {
        pendingLoads_.push(req)
    }
};

var exists_ = function(obj, xs) {
    if(typeof xs == "string") {
        xs = xs.split(".");
    }
    if(xs.length >= 1) {
        var key = xs[0],
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

var pathToIds_ = function() {
    var pathToIds = {};
    for(var id in goog.debugLoader_.idToPath_) {
        var path = goog.debugLoader_.idToPath_[id];
        if(pathToIds[path] == null) {
            pathToIds[path] = [];
        }
        pathToIds[path].push(id);
    }
    return pathToIds;
};

var isLoaded_ = function(path, index) {
    var ids = index[path];
    for(var i = 0; i < ids.length; i++) {
        if(exists_(global, ids[i])) {
            return true;
        }
    }
    return false;
};

var flushLoads_ = function(socket) {
    var index    = pathToIds_(),
        filtered = pendingLoads_.filter(function(req) {
                       return !isLoaded_(req.value, index);
                   }).map(function(req) {
                       return JSON.stringify(req)+"\0";
                   });
    socket.write(filtered.join(""));
    pendingLoads_ = [];
};

global.CLOSURE_NO_DEPS = true;
global.CLOSURE_BASE_PATH = "$CLOSURE_BASE_PATH";

// NOTE: CLOSURE_LOAD_FILE_SYNC not needed as ClojureScript now transpiles
// offending goog.module files that would need runtime transpiler support
global.CLOSURE_IMPORT_SCRIPT = function(path, optContents) {
    if (optContents) {
        evaluate(optContents);
        return true;
    } else {
        var cached = KRELL_CACHE.get(path);
        if(cached) {
            evaluate(cached.source);
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

var notifyListeners = function(request) {
    var path = request.value,
        xs = libLoadListeners[path] || [];

    xs.forEach(function (x) {
        x();
    });
};

var notifyReloadListeners = function() {
    reloadListeners.forEach(function(x) {
        x();
    });
};

var onSourceLoad = function(path, cb) {
    if(typeof libLoadListeners[path] === "undefined") {
        libLoadListeners[path] = [];
    }
    libLoadListeners[path].push(cb);
};

var onKrellReload = function(cb) {
    reloadListeners.push(cb);
};

var errString = function(err) {
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

var handleMessage = function(socket, data){
    var req = null,
        err = null,
        ret = null;

    data = data.replace(/\0/g, "");

    try {
        var msg = JSON.parse(data);
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
        if(req && req.type === "load-file") {
            let path = req.value;
            KRELL_CACHE.put(path, {
                source: msg.form,
                path: path,
                modified: req.modified
            });
            if(typeof goog !== "undefined") {
                goog.debugLoader_.written_[req.value] = true;
            }
        }
        // Android-specific hack to batch loads
        if (pendingLoads_.length > 0) {
            flushLoads_(socket);
        }
    } catch (e) {
        console.error(e, msg.form);
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

var initSocket = function(socket) {
    var buffer = "";
    // it doesn't matter which socket we use for loads
    loadFileSocket = socket;

    socket.on("data", data => {
        if (data[data.length - 1] !== 0) {
            buffer += data;
        } else {
            data = buffer + data;
            buffer = "";

            if (data) {
                socket.write(JSON.stringify({type: "ack"}) + "\0", "utf8", function () {
                    // on Android must serialize the write to avoid out of
                    // order arrival, also callback never gets invoked on iOS
                    // - bug in react-native-tcp-socket
                    if(IS_ANDROID) handleMessage(socket, data);
                });
                // No issues with multiple write in one turn of the event loop
                // on iOS and avoids the bug mentioned in above comment
                if(!IS_ANDROID) handleMessage(socket, data);
            }
        }
    });

    socket.on("error", error => {
        console.log("An error ocurred with client socket ", error);
    });

    socket.on("close", error => {
        if (CONNECTED) {
            console.log("Closed connection with ", socket.address());
        }
        CONNECTED = false;
        setTimeout(tryConnection, RECONNECT_INTERVAL);
    });
};

// Loop to connect from client to server
var tryConnection = function() {
    if(!CONNECTED) {
        var socket = TcpSocket.createConnection({
            host: SERVER_IP,
            port: SERVER_PORT,
            localPort: IS_ANDROID ? REPL_PORT : undefined,
            tls: false
        }, function(address) {
            CONNECTED = true;
        });
        initSocket(socket);
    } else {
        setTimeout(tryConnection, RECONNECT_INTERVAL);
    }
};

tryConnection();

module.exports = {
    onSourceLoad: onSourceLoad,
    onKrellReload: onKrellReload
};
