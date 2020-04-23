import TcpSocket from "react-native-tcp-socket";
import Zeroconf from "react-native-zeroconf";
import { getApplicationName, getDeviceId, getSystemName } from 'react-native-device-info';
import { npmDeps } from "./npm_deps.js";
import { assets } from "./krell_assets.js";

var CONNECTED = false;
var IPV4 = /^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$/;
var RECONNECT_INTERVAL = 3000;

var SERVER_IP = "$KRELL_SERVER_IP";
var SERVER_PORT = $KRELL_SERVER_PORT;

var IS_ANDROID = (getSystemName() === "Android");
var REPL_PORT = IS_ANDROID ? 5003 : 5002;

var evaluate = eval;
var libLoadListeners = {};
var reloadListeners = [];
var pendingLoads_ = [];
var repub_ = 0;

// =============================================================================
// ZeroConf Service Publication / Discovery

const zeroconf = new Zeroconf();

var bonjourName = function() {
    return "krell.repl" + getApplicationName() + " " + getDeviceId();
};

zeroconf.on("start", () => {
    console.log("Scan started");
});

zeroconf.on("stop", () => {
    console.log("Scan stopped");
});

zeroconf.on("found", name => {
    console.log("Service found:", JSON.stringify(name));
});

zeroconf.on("resolved", service => {
    console.log("Service resolved:", JSON.stringify(service));
    // TODO: do this only if not already connected
    if(service.name.indexOf("Krell-REPL-Server") !== -1) {
        SERVER_IP = service.addresses.find(x => x.match(IPV4));
        SERVER_PORT = service.port;
    }
});

zeroconf.scan("http", "tcp", "local.");

var repubTimeout_ = function() {
    if(repub_ < 10) {
        repub_ = repub_ + 1;
        return (250 * (2**repub_));
    } else {
        return 256000;
    }
};

var publishReplService = function() {
    zeroconf.publishService("http", "tcp", "local.", bonjourName(), REPL_PORT);
    if(IS_ANDROID) {
        setTimeout(publishReplService, repubTimeout_());
    }
};

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

// NOTE: CLOSURE_LOAD_FILE_SYNC not needed as ClojureScript now transpiles
// offending goog.module files that would need runtime transpiler support
global.CLOSURE_IMPORT_SCRIPT = function(path, optContents) {
    if (optContents) {
        eval(optContents);
        return true;
    } else {
        loadFile(loadFileSocket, path, optContents);
        return true;
    }
};

global.require = function(x) {
    return npmDeps[x] || assets[x];
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

    if (data === ":cljs/quit") {
        socket.destroy();
        CONNECTED = false;
        return;
    } else {
        try {
            var obj = JSON.parse(data);
            req = obj.request;
            ret = evaluate(obj.form);
            // output forwarding
            if(typeof ret == "function") {
                if(ret.name === "cljs$user$redirect_output") {
                    ret(socket);
                }
            }
            /*
            if(req && req.type === "load-file") {
                console.log("LOAD FILE:", req.value);
            }
            */
            if(pendingLoads_.length > 0) {
                flushLoads_(socket);
            }
        } catch (e) {
            console.error(e, obj.form);
            err = e;
        }
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
            if(req.reload) {
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
        console.log("Closed connection with ", socket.address());
        CONNECTED = false;
        setTimeout(tryConnection, RECONNECT_INTERVAL);
    });
};

// TODO: remove, we can use the Krell published service to know what
// to connect to
var server = TcpSocket.createServer(function (socket) {
    socket.write("ready\0");
    initSocket(socket);
}).listen({port: REPL_PORT, host: "0.0.0.0"});

server.on("error", error => {
    console.log("An error ocurred with the server", error);
});

server.on("close", () => {
    console.log("Server closed connection");
});

// Loop to connect from client to server
var tryConnection = function() {
    if(!CONNECTED) {
        var socket = TcpSocket.createConnection({
            host: SERVER_IP,
            port: SERVER_PORT,
            localAddress: "127.0.0.1",
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
    onKrellReload: onKrellReload,
    publishReplService: publishReplService
};
