import TcpSocket from "react-native-tcp-socket";
import Zeroconf from "react-native-zeroconf";
import { getApplicationName, getDeviceId, getSystemName } from 'react-native-device-info';
import {npmDeps} from "./rt.js";

var IS_ANDROID = (getSystemName() === "Android");
var REPL_PORT = IS_ANDROID ? 5003 : 5002;
var evaluate = eval;
var libLoadListeners = {};
var reloadListeners = [];

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

zeroconf.on("resolved", service => {
    console.log("Service resolved:", JSON.stringify(service));
});

zeroconf.scan("http", "tcp", "local.");

zeroconf.publishService("http", "tcp", "local.", bonjourName(), REPL_PORT);

// =============================================================================
// REPL Server

var loadFileSocket = null;

var loadFile = function(socket, path) {
    var req = {
        type: "load-file",
        value: path
    };
    socket.write(JSON.stringify(req)+"\0");
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

global.require = function(lib) {
    return npmDeps[lib];
};

var notifyListeners = function(request) {
    var path = request.value,
        xs = libLoadListeners[path] || [];

    xs.forEach(function (x) {
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

var handleMessage = function(socket, data){
    var req = null,
        err = null,
        ret = null;

    data = data.replace(/\0/g, "");

    if (data === ":cljs/quit") {
        socket.destroy();
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
        } catch (e) {
            console.log(e, obj.form);
            err = e;
        }
    }

    if (err) {
        socket.write(
            JSON.stringify({
                type: "result",
                status: "exception",
                value: (typeof cljs != "undefined") ? cljs.repl.error__GT_str(err) : err.toString()
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
        }
    }
};

var server = TcpSocket.createServer(function (socket) {
    var buffer = "";

    // it doesn't matter which socket we use for loads
    loadFileSocket = socket;

    socket.write("ready\0");

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
    });
}).listen({port: REPL_PORT, host: "0.0.0.0"});

server.on("error", error => {
    console.log("An error ocurred with the server", error);
});

server.on("close", () => {
    console.log("Server closed connection");
});

module.exports = {
    onSourceLoad: onSourceLoad,
    onKrellReload: onKrellReload
};
